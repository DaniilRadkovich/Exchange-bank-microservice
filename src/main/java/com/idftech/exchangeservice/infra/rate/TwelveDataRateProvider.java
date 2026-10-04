package com.idftech.exchangeservice.infra.rate;

import com.idftech.exchangeservice.application.port.ExchangeRateProvider;
import com.idftech.exchangeservice.domain.ExchangeRate;
import com.idftech.exchangeservice.application.config.RateProviderProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Адаптер внешнего источника курсов — Twelve Data {@code /time_series}.
 *
 * <p>Запрашивается неделя вокруг целевой даты, а не один день: так одна и та же логика покрывает
 * и обычный торговый день, и выходной. Значение за целевую дату становится {@code close}, ближайшее
 * более раннее — {@code previousClose}, то есть ровно та семантика, которую требует ТЗ п.3.
 *
 * <p>Таймауты и повторные попытки заданы в {@link RateProviderProperties} и настраиваются через
 * переменные окружения. Итоговая стратегия описана в README.
 *
 * <p>Обращения к внешнему API измеряются Micrometer: {@code exchange.rates.provider.requests} с
 * тегом результата и {@code exchange.rates.provider.duration}. Это основной сигнал эксплуатации —
 * по нему видно и долю промахов кэша, и деградацию провайдера, не заглядывая в логи.
 */
public class TwelveDataRateProvider implements ExchangeRateProvider {

  private static final Logger log = LoggerFactory.getLogger(TwelveDataRateProvider.class);

  private static final String USD = "USD";
  private static final int HISTORY_DAYS = 7;

  private final RestClient restClient;
  private final RateProviderProperties properties;
  private final RetryingCaller retryingCaller;
  private final Timer requestTimer;
  private final Counter missCounter;
  private final Counter failureCounter;

  public TwelveDataRateProvider(
      RestClient restClient,
      RateProviderProperties properties,
      RetryingCaller retryingCaller,
      MeterRegistry meterRegistry) {
    this.restClient = restClient;
    this.properties = properties;
    this.retryingCaller = retryingCaller;

    this.requestTimer = Timer.builder("exchange.rates.provider.duration")
        .description("Wall-clock duration of external rate provider calls, including retries")
        .register(meterRegistry);
    this.missCounter = Counter.builder("exchange.rates.provider.requests")
        .description("External rate provider calls by outcome")
        .tag("outcome", "miss")
        .register(meterRegistry);
    this.failureCounter = Counter.builder("exchange.rates.provider.requests")
        .tag("outcome", "failure")
        .register(meterRegistry);
  }

  @Override
  public Optional<ExchangeRate> fetchDailyRate(String baseCurrency, LocalDate date) {
    Optional<ExchangeRate> direct = rateFor(baseCurrency + "/" + USD, baseCurrency, date);
    if (direct.isPresent()) {
      missCounter.increment();
      return direct;
    }
    // Обратная пара. Не все валюты котируются к USD напрямую: тенге, например, у Twelve Data есть
    // только как USD/KZT, а KZT/USD даёт 404 «symbol is missing or invalid». Брать курс из обратной
    // пары и обращать его — значит не терять операции из-за того, как провайдер нарезал символы.
    Optional<ExchangeRate> inverted = invertedRate(baseCurrency, date);
    if (inverted.isPresent()) {
      missCounter.increment();
      return inverted;
    }
    failureCounter.increment();
    return Optional.empty();
  }

  /**
   * Курс пары {@code symbol} за дату операции; пустой результат означает, что провайдер не дал
   * пригодного значения.
   */
  private Optional<ExchangeRate> rateFor(String symbol, String baseCurrency, LocalDate date) {
    LocalDate start = date.minusDays(HISTORY_DAYS);
    TimeSeriesResponse response =
        requestTimer.record(
            () -> retryingCaller.call(() -> request(symbol, start, date), properties.maxRetries(), "TwelveData"));

    if (response == null) {
      return Optional.empty();
    }
    if (response.status() != null && !"ok".equalsIgnoreCase(response.status())) {
      log.warn(
          "External rate provider returned status={} code={} message={}",
          response.status(),
          response.code(),
          response.message());
      return Optional.empty();
    }
    return toExchangeRate(baseCurrency, date, response);
  }

  /**
   * Курс {@code USD/BASE}, обращённый в {@code BASE/USD}.
   *
   * <p>Точность не теряется: {@code RATE_SCALE} десятичных знаков достаточно, чтобы обратный курс
   * тенге (около 0.0022) сохранил все значащие цифры, а округление по умолчанию здесь не годится —
   * {@link ExchangeRate#applicableRate()} требует {@code RATE_SCALE}.
   */
  private Optional<ExchangeRate> invertedRate(String baseCurrency, LocalDate date) {
    // toExchangeRate гарантирует, что хотя бы одно из значений пригодно, поэтому applicableRate()
    // здесь не бросает: нулевой курс от провайдера отсекается ещё в positive().
    Optional<ExchangeRate> inverse = rateFor(USD + "/" + baseCurrency, baseCurrency, date);
    if (inverse.isEmpty()) {
      return Optional.empty();
    }
    ExchangeRate rate = inverse.get();
    log.debug(
        "Direct pair {}/{} unavailable for {}; using inverted {}/{} close {}",
        baseCurrency,
        USD,
        date,
        USD,
        baseCurrency,
        rate.applicableRate());
    return Optional.of(
        new ExchangeRate(
            UUID.randomUUID(),
            Currency.getInstance(baseCurrency),
            Currency.getInstance(USD),
            rate.rateDate(),
            inverted(rate.close()),
            inverted(rate.previousClose())));
  }

  /** Обратная величина пригодного курса; {@code null} остаётся {@code null}, ноль и минус не делятся. */
  private static BigDecimal inverted(BigDecimal rate) {
    return rate == null || rate.signum() <= 0
        ? null
        : BigDecimal.ONE.divide(rate, ExchangeRate.RATE_SCALE, RoundingMode.HALF_UP);
  }

  private TimeSeriesResponse request(String symbol, LocalDate start, LocalDate end) {
    return restClient
        .get()
        .uri(
            uriBuilder -> uriBuilder
                .path("/time_series")
                .queryParam("symbol", symbol)
                .queryParam("interval", "1day")
                .queryParam("start_date", start.toString())
                .queryParam("end_date", end.toString())
                .queryParam("previous_close", true)
                .queryParam("order", "asc")
                .queryParam("outputsize", HISTORY_DAYS + 1)
                .build())
        .header("Authorization", "apikey " + properties.apiKey())
        .retrieve()
        .body(TimeSeriesResponse.class);
  }

  /**
   * Выбирает значение за целевую дату и ближайшее предыдущее закрытие.
   *
   * <p>Цена, которая не положительна, курсом не является: null, ноль и отрицательное значение отбрасываются,
   * иначе в кэш попала бы запись, которую нельзя применить, а ограничение схемы {@code close_rate > 0}
   * отвергло бы её уже в базе. Транзакция ждала бы дорасчёта до статуса {@code FAILED}, а в логе
   * причина выглядела бы как отказ внешнего API. Нет положительной цены — нет и курса: возвращается
   * пустой результат, и срабатывает штатный путь отложенного расчёта.
   */
  private Optional<ExchangeRate> toExchangeRate(String baseCurrency, LocalDate date, TimeSeriesResponse response) {
    if (response.values() == null || response.values().isEmpty()) {
      log.warn("External rate provider returned no values for {}/{} on {}", baseCurrency, USD, date);
      return Optional.empty();
    }

    List<TimeSeriesResponse.SeriesValue> values = response.values();
    BigDecimal previousClose = null;

    for (TimeSeriesResponse.SeriesValue value : values) {
      LocalDate valueDate = parseDate(value.datetime());
      if (valueDate == null) {
        continue;
      }
      if (valueDate.equals(date)) {
        BigDecimal close = positive(value.closeAsBigDecimal());
        BigDecimal fallback =
            previousClose != null ? previousClose : positive(value.previousCloseAsBigDecimal());
        if (close == null && fallback == null) {
          log.warn(
              "External rate provider has no usable close for {}/{} on {}",
              baseCurrency,
              USD,
              date);
          return Optional.empty();
        }
        return Optional.of(
            new ExchangeRate(
                UUID.randomUUID(),
                Currency.getInstance(baseCurrency),
                Currency.getInstance(USD),
                date,
                close,
                fallback));
      }
      if (valueDate.isBefore(date)) {
        BigDecimal close = positive(value.closeAsBigDecimal());
        if (close != null) {
          previousClose = close;
        }
      }
    }

    // Торгов за целевую дату не было (выходной или праздник): берём последнее доступное закрытие.
    // Именно последнее не позже целевой даты, а не последнее в ответе: провайдеру нельзя доверять
    // границы запроса, а закрытие будущего дня не имеет отношения к операции прошедшего дня.
    TimeSeriesResponse.SeriesValue last = lastNotAfter(values, date);
    BigDecimal fallback = last == null ? null : positive(last.closeAsBigDecimal());
    if (fallback == null && last != null) {
      fallback = positive(last.previousCloseAsBigDecimal());
    }
    if (fallback == null || fallback.signum() <= 0) {
      log.warn("External rate provider has no usable close for {}/{} on {}", baseCurrency, USD, date);
      return Optional.empty();
    }
    log.debug("No trading for {}/{} on {}; using previous close {}", baseCurrency, USD, date, fallback);
    return Optional.of(
        new ExchangeRate(
            UUID.randomUUID(),
            Currency.getInstance(baseCurrency),
            Currency.getInstance(USD),
            date,
            null,
            fallback));
  }

  /** Курс пригоден, только если цена положительна: ноль и отрицательное значение некурсовые. */
  private static BigDecimal positive(BigDecimal value) {
    return value != null && value.signum() > 0 ? value : null;
  }

  /** Последнее значение с датой не позже целевой, то есть самое свежее относящееся к операции. */
  private static TimeSeriesResponse.SeriesValue lastNotAfter(
      List<TimeSeriesResponse.SeriesValue> values, LocalDate date) {
    TimeSeriesResponse.SeriesValue last = null;
    LocalDate lastDate = null;
    for (TimeSeriesResponse.SeriesValue value : values) {
      LocalDate valueDate = parseDate(value.datetime());
      if (valueDate == null || valueDate.isAfter(date)) {
        continue;
      }
      if (lastDate == null || valueDate.isAfter(lastDate)) {
        last = value;
        lastDate = valueDate;
      }
    }
    return last;
  }

  private static LocalDate parseDate(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String trimmed = raw.trim();
    try {
      // Для interval=1day Twelve Data отдаёт дату, но строка может содержать время.
      return LocalDate.parse(trimmed.length() > 10 ? trimmed.substring(0, 10) : trimmed);
    } catch (DateTimeParseException e) {
      log.debug("Unparseable datetime from rate provider: {}", raw);
      return null;
    }
  }
}
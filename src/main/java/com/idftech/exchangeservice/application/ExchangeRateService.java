package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.port.ExchangeRateProvider;
import com.idftech.exchangeservice.application.port.RateCache;
import com.idftech.exchangeservice.domain.ExchangeRate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Сервис получения биржевого курса с приоритетом собственной БД.
 *
 * <p>Стратегия (ТЗ п.3, описана в README): сначала смотрим свою БД — там уже есть и {@code close},
 * и {@code previous_close}. Только при промахе обращаемся к платному внешнему API, результат
 * сохраняем в БД и используем его повторно. Это уменьшает и стоимость, и задержку обработки.
 *
 * <p>Для валют, отличных от USD, применяется кросс-курс через USD: USD всегда равен 1, поэтому
 * перевод любой валюты в USD — это деление на курс «валюта к USD» там, где провайдер отдаёт пару
 * «USD к валюте».
 *
 * <p>Счётчики попаданий и промахов кэша — главный способ увидеть в метриках, что кэш курсов
 * работает. Если доля промахов растёт, значит либо провайдер отдаёт даты, которых не было в базе,
 * либо база очищается; без этой метрики платность внешних запросов видна только по счёту.
 */
@Component
public class ExchangeRateService {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);

  private static final String USD = "USD";
  private static final int RATE_SCALE = 4;

  private final RateCache rateCache;
  private final ExchangeRateProvider rateProvider;
  private final Counter cacheHits;
  private final Counter cacheMisses;
  private final Counter unresolvedRates;

  public ExchangeRateService(
      RateCache rateCache,
      ExchangeRateProvider rateProvider,
      MeterRegistry meterRegistry) {
    this.rateCache = rateCache;
    this.rateProvider = rateProvider;
    this.cacheHits = counter(meterRegistry, "hit");
    this.cacheMisses = counter(meterRegistry, "miss");
    this.unresolvedRates = counter(meterRegistry, "unresolved");
  }

  /**
   * Курс перевода 1 единицы валюты транзакции в USD на дату операции.
   *
   * <p>Возвращает пустой результат, если курс получить не удалось: приём транзакций от этого не
   * должен теряться, транзакция остаётся в статусе {@code PENDING} и будет дорасчитана позже.
   */
  public Optional<BigDecimal> resolveUsdRate(String currencyCode, LocalDate date) {
    if (USD.equals(currencyCode)) {
      return Optional.of(BigDecimal.ONE.setScale(RATE_SCALE, RoundingMode.UNNECESSARY));
    }

    Optional<BigDecimal> cached = rateCache.findRate(currencyCode, date);
    if (cached.isPresent()) {
      cacheHits.increment();
      log.debug("Rate for {}/{} on {} served from own database: {}", currencyCode, USD, date, cached.get());
      return cached;
    }

    cacheMisses.increment();
    Optional<BigDecimal> fetched = fetchAndCache(currencyCode, date);
    if (fetched.isPresent()) {
      return fetched;
    }

    Optional<BigDecimal> fallback = rateCache.findLatestRateNotAfter(currencyCode, date);
    if (fallback.isPresent()) {
      log.warn("No rate for {}/{} on {}; using latest known rate not after this date: {}", currencyCode, USD, date, fallback.get());
    } else {
      unresolvedRates.increment();
      log.warn("No rate available for {}/{} on {}", currencyCode, USD, date);
    }
    return fallback;
  }

  private Optional<BigDecimal> fetchAndCache(String currencyCode, LocalDate date) {
    try {
      Optional<ExchangeRate> rate = rateProvider.fetchDailyRate(currencyCode, date);
      if (rate.isEmpty()) {
        return Optional.empty();
      }
      rateCache.save(rate.get());
      return Optional.of(usableRate(rate.get()));
    } catch (RuntimeException e) {
      // Внешний API недоступен: не роняем приём транзакций, а возвращаем пустой результат.
      log.warn("External rate provider failed for {}/{} on {}: {}", currencyCode, USD, date, e.getMessage());
      return Optional.empty();
    }
  }

  private BigDecimal usableRate(ExchangeRate rate) {
    return rate.applicableRate().setScale(RATE_SCALE, RoundingMode.HALF_UP);
  }

  private static Counter counter(MeterRegistry meterRegistry, String outcome) {
    return Counter.builder("exchange.rates.cache.lookups")
        .description("Own-database lookups by outcome: hit served locally, miss and unresolved went to the provider")
        .tag("outcome", outcome)
        .register(meterRegistry);
  }
}
package com.idftech.exchangeservice.infra.rate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/**
 * Ответ внешнего API курсов (формат Twelve Data {@code /time_series}).
 *
 * <p>Разбор намеренно терпимый к лишним полям: внешний источник может расширять контракт, и это не
 * повод валить приём транзакций. Нулевые и пустые значения трактуются как отсутствие данных.
 *
 * @param meta метаданные пары
 * @param values ряды наблюдений; {@code close} — цена закрытия, {@code previous_close} — предыдущая
 * @param status статус ответа источника
 * @param code код ошибки источника, если запрос не удался
 * @param message описание ошибки источника
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TimeSeriesResponse(Meta meta, List<SeriesValue> values, String status, Integer code, String message) {

  /**
   * Одно наблюдение дневного ряда.
   *
   * @param datetime дата наблюдения в формате {@code yyyy-MM-dd} (для {@code interval=1day})
   * @param close цена закрытия
   * @param previousClose предыдущее закрытие; приходит только при {@code previous_close=true}
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SeriesValue(
      String datetime,
      String close,
      @JsonProperty("previous_close") String previousClose) {

    /** Цена закрытия как число; пустое или нечисловое значение трактуется как отсутствие. */
    public BigDecimal closeAsBigDecimal() {
      return parse(close);
    }

    /** Предыдущее закрытие как число. */
    public BigDecimal previousCloseAsBigDecimal() {
      return parse(previousClose);
    }

    private static BigDecimal parse(String raw) {
      if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw.trim())) {
        return null;
      }
      try {
        return new BigDecimal(raw.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }

  /**
   * Метаданные пары.
   *
   * @param exchangeTimezone часовой пояс биржевого дня; нужен, чтобы дата наблюдения и дата применения
   *     курса совпадали при переводе операции в UTC
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Meta(
      String symbol,
      String interval,
      String currency,
      @JsonProperty("exchange_timezone") String exchangeTimezone) {}
}

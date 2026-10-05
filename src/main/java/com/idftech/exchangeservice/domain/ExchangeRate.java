package com.idftech.exchangeservice.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Дневной биржевой курс валютной пары к USD с закрытием и предыдущим закрытием.
 *
 * <p>Курс хранится в собственной БД (ТЗ п.3): каждый запрос к внешнему API платный и медленный,
 * поэтому в БД попадает и {@code close} текущего дня, и {@code previous_close} на случай
 * выходных и праздников.
 *
 * @param base валюта котируемой пары, например {@code KZT} в паре {@code KZT/USD}
 * @param quote котируемая валюта (всегда {@code USD})
 * @param rateDate дата торгов (в часовом поясе биржевого дня)
 * @param close цена закрытия: 1 единица {@code base} в валюте {@code quote}; может быть {@code null},
 *     если на {@code rateDate} торгов не было
 * @param previousClose цена предыдущего закрытия — резерв, если на {@code rateDate} торгов не было
 */
public record ExchangeRate(
    UUID id,
    Currency base,
    Currency quote,
    LocalDate rateDate,
    BigDecimal close,
    BigDecimal previousClose) {

  /**
   * Точность курса при хранении и округлении — HALF_UP.
   *
   * <p>Десяти знаков после точки, а не четырёх: курс к USD у тенге равен примерно 0.0025, у VND — 0.00004,
   * у IDR — 0.00006. Четыре знака дают относительную погрешность до 2% на тенге, а курс мельче
   * половины последнего разряда обнуляется вовсе и не проходит ограничение {@code close_rate > 0} —
   * валюта становится нерасчётной навсегда. Значение также продублировано в схеме: {@code close_rate},
   * {@code previous_close} и {@code usd_rate} объявлены как {@code NUMERIC(19, 10)}, а {@code
   * ddl-auto: validate} падает на старте при расхождении.
   */
  public static final int RATE_SCALE = 10;

  public ExchangeRate {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(quote, "quote");
    Objects.requireNonNull(rateDate, "rateDate");
  }

  /** Курс, применимый к дате операции: close, а при его отсутствии — previous_close (ТЗ п.3). */
  public BigDecimal applicableRate() {
    if (close != null && close.signum() > 0) {
      return close;
    }
    if (previousClose != null && previousClose.signum() > 0) {
      return previousClose;
    }
    throw new IllegalStateException("No usable rate for " + base + "/" + quote + " on " + rateDate);
  }
}

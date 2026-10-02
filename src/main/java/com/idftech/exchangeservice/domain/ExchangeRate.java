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

  public static final int RATE_SCALE = 4;

  public ExchangeRate {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(quote, "quote");
    Objects.requireNonNull(rateDate, "rateDate");
    // close и previousClose взаимно не исключают друг друга: биржа может вернуть только одно из них,
    // а применимый курс выбирается в applicableRate().
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

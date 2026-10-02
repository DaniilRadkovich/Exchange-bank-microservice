package com.idftech.exchangeservice.domain;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Календарный месяц в границах часового пояса лимитов.
 *
 * <p>ТЗ требует определять границы месяца в одном часовом поясе (UTC, см. README). Поскольку
 * месячная лимитация считается по календарному месяцу, а не по 30-дневным окнам, значение
 * объекта — просто {@link YearMonth}.
 *
 * <p>Часовой пояс не компонент записи, а константа {@link #LIMIT_TIMEZONE}. Пока он был
 * компонентом, его можно было задать в конструкторе любому значению, и код расходился в две
 * стороны: {@code BudgetPeriod.of} всегда брал UTC, а расчёт даты установки лимита, даты курса и
 * границ периода в SQL — `exchange.limit.timezone`. При значении по умолчанию это не видно, но
 * смена пояса в конфиге тихо дала бы две разные границы месяца в одном расчёте.
 *
 * @param value календарный месяц
 */
public record BudgetPeriod(YearMonth value) {

  /** Часовой пояс, в котором определяются границы месяца. Единственный источник этого значения. */
  public static final ZoneId LIMIT_TIMEZONE = ZoneOffset.UTC;

  public BudgetPeriod {
    Objects.requireNonNull(value, "value");
  }

  public static BudgetPeriod of(YearMonth value) {
    return new BudgetPeriod(value);
  }

  /** Период операции по времени её совершения. */
  public static BudgetPeriod of(java.time.OffsetDateTime occurredAt) {
    return of(YearMonth.from(occurredAt.atZoneSameInstant(LIMIT_TIMEZONE)));
  }

  /** Часовой пояс границ периода. Метод, а не компонент: значение менять негде. */
  public ZoneId zoneId() {
    return LIMIT_TIMEZONE;
  }

  /** Момент установки лимита по умолчанию: начало текущего месяца в часовом поясе лимитов. */
  public java.time.Instant defaultLimitInstant() {
    return value.atDay(1).atStartOfDay(LIMIT_TIMEZONE).toInstant();
  }

  @Override
  public String toString() {
    return value.toString();
  }
}

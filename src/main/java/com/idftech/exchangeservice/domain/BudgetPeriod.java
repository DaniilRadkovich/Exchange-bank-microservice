package com.idftech.exchangeservice.domain;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Календарный месяц в границах системного часового пояса.
 *
 * <p>ТЗ требует определять границы месяца в одном часовом поясе (UTC, см. README). Поскольку
 * месячная лимитация считается по календарному месяцу, а не по 30-дневным окнам, значение
 * объекта — просто {@link YearMonth}; часовой пояс здесь зафиксирован как часть контракта,
 * чтобы его нельзя было случайно переопределить в одном месте кода.
 *
 * @param value календарный месяц
 * @param zoneId часовой пояс, в котором определяются границы месяца
 */
public record BudgetPeriod(YearMonth value, ZoneId zoneId) {

  public static final ZoneId LIMIT_TIMEZONE = ZoneOffset.UTC;

  public BudgetPeriod {
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(zoneId, "zoneId");
  }

  public static BudgetPeriod of(YearMonth value) {
    return new BudgetPeriod(value, LIMIT_TIMEZONE);
  }

  /** Период операции по времени её совершения. */
  public static BudgetPeriod of(java.time.OffsetDateTime occurredAt) {
    return of(YearMonth.from(occurredAt.atZoneSameInstant(LIMIT_TIMEZONE)));
  }

  /** Следующий месяц — используется при переходе календарного месяца. */
  public BudgetPeriod next() {
    return of(value.plusMonths(1));
  }

  /** Момент установки лимита по умолчанию: начало текущего месяца в часовом поясе лимитов. */
  public java.time.Instant defaultLimitInstant() {
    return value.atDay(1).atStartOfDay(zoneId).toInstant();
  }

  @Override
  public String toString() {
    return value.toString();
  }
}

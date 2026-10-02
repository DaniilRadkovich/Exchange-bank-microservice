package com.idftech.exchangeservice.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Проекция для ТЗ п.6: транзакция, превысившая лимит, вместе с параметрами того лимита, который
 * был превышен. Это read-модель, собираемая SQL-запросом, а не JPA-сущность.
 *
 * @param limit сумма лимита, который был превышен
 * @param limitDatetime дата установки этого лимита
 * @param limitCurrency валюта лимита, всегда USD
 * @param runningTotalUsd накопленная сумма расходов категории на момент этой транзакции —
 *     производная величина для клиента, она же показывает, насколько превышен лимит
 */
public record ExceededTransaction(
    UUID id,
    String accountFrom,
    String accountTo,
    Currency currency,
    BigDecimal amount,
    ExpenseCategory category,
    OffsetDateTime occurredAt,
    BigDecimal amountUsd,
    BigDecimal usdRate,
    BigDecimal limit,
    OffsetDateTime limitDatetime,
    Currency limitCurrency,
    BigDecimal runningTotalUsd) {

  public ExceededTransaction {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(accountFrom, "accountFrom");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(limit, "limit");
    Objects.requireNonNull(limitDatetime, "limitDatetime");
    Objects.requireNonNull(limitCurrency, "limitCurrency");
  }

  /** Насколько сумма превысила лимит, в USD. */
  public BigDecimal exceededBy() {
    return runningTotalUsd.subtract(limit);
  }
}

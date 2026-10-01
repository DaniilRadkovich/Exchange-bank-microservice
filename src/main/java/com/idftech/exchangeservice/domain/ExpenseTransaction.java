package com.idftech.exchangeservice.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Неизменяемая доменная модель расходной транзакции.
 *
 * <p>Намеренно не JPA-сущность: доменный слой не зависит от Hibernate, персистентное
 * представление описано в {@code infra.persistence}. Это позволяет юнит-тестам доменной логики
 * (требование 5 ТЗ) работать без контекста Spring и реальной БД.
 */
public record ExpenseTransaction(
    UUID id,
    String accountFrom,
    String accountTo,
    Currency currency,
    BigDecimal amount,
    ExpenseCategory category,
    OffsetDateTime occurredAt,
    BigDecimal usdRate,
    BigDecimal amountUsd,
    TransactionStatus status,
    Boolean limitExceeded) {

  /** Точность суммы в USD — два знака после точки, как в формате ответа ТЗ. */
  public static final int USD_SCALE = 2;
  /** Точность биржевого курса при хранении и округлении суммы — HALF_UP. */
  public static final int RATE_SCALE = 4;

  public ExpenseTransaction {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(accountFrom, "accountFrom");
    Objects.requireNonNull(accountTo, "accountTo");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(status, "status");
  }

  public boolean isUsd() {
    return USD_CURRENCY_CODE.equals(currency.getCurrencyCode());
  }

  /** Календарный месяц операции в границах часового пояса лимитов. */
  public BudgetPeriod period() {
    return BudgetPeriod.of(occurredAt);
  }

  /** Признак операции, готовой к расчёту лимита: курс применён, сумма в USD известна. */
  public boolean isResolved() {
    return status == TransactionStatus.RATE_RESOLVED && amountUsd != null;
  }

  /** Копия транзакции с применённым курсом и рассчитанным флагом превышения лимита. */
  public ExpenseTransaction resolved(BigDecimal rate, boolean exceeded) {
    return new ExpenseTransaction(
        id,
        accountFrom,
        accountTo,
        currency,
        amount,
        category,
        occurredAt,
        rate.setScale(RATE_SCALE, RoundingMode.HALF_UP),
        toUsd(amount, rate),
        TransactionStatus.RATE_RESOLVED,
        exceeded);
  }

  /** Копия транзакции с пересчитанным флагом — нужна при переоценке уже разрешённой транзакции. */
  public ExpenseTransaction withLimitExceeded(boolean exceeded) {
    return new ExpenseTransaction(
        id, accountFrom, accountTo, currency, amount, category, occurredAt,
        usdRate, amountUsd, TransactionStatus.RATE_RESOLVED, exceeded);
  }

  /** Копия в статусе FAILED: внешний API курсов недоступен, сумма в USD неизвестна. */
  public ExpenseTransaction failed() {
    return new ExpenseTransaction(
        id, accountFrom, accountTo, currency, amount, category, occurredAt,
        null, null, TransactionStatus.FAILED, null);
  }

  /** Сумма операции в USD по биржевому курсу: amount × rate с округлением до копеек. */
  public static BigDecimal toUsd(BigDecimal amount, BigDecimal rate) {
    return amount.multiply(rate).setScale(USD_SCALE, RoundingMode.HALF_UP);
  }

  private static final String USD_CURRENCY_CODE = "USD";
}

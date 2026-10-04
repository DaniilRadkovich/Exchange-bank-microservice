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

/**
   * Копия транзакции с применённым курсом и рассчитанным флагом превышения лимита.
   *
   * <p>Сумма в USD считается по <b>сохранённому</b> курсу, то есть по тому, что реально попадёт в
   * строку {@code usd_rate}. Иначе сумма и курс описывали бы разные цены: расхождение показывалось бы
   * только в пересчёте суммы, и найти его можно было бы исключительно сверкой с хранилищем.
   *
   * <p>Точность курса берётся из {@link ExchangeRate#RATE_SCALE}: собственной константы здесь нет,
   * иначе два числа разъехались бы, а расхождение не показал бы ни один тест — округлённый до меньшей
   * точности курс выглядит правдоподобно.
   */
  public ExpenseTransaction resolved(BigDecimal rate, boolean exceeded) {
    BigDecimal storedRate = rate.setScale(ExchangeRate.RATE_SCALE, RoundingMode.HALF_UP);
    return new ExpenseTransaction(
        id,
        accountFrom,
        accountTo,
        currency,
        amount,
        category,
        occurredAt,
        storedRate,
        toUsd(amount, storedRate),
        TransactionStatus.RATE_RESOLVED,
        exceeded);
  }

  /** Копия транзакции с пересчитанным флагом — нужна при переоценке уже разрешённой транзакции. */
  public ExpenseTransaction withLimitExceeded(boolean exceeded) {
    return new ExpenseTransaction(
        id, accountFrom, accountTo, currency, amount, category, occurredAt,
        usdRate, amountUsd, TransactionStatus.RATE_RESOLVED, exceeded);
  }

  /** Сумма операции в USD по биржевому курсу: amount × rate с округлением до копеек. */
  public static BigDecimal toUsd(BigDecimal amount, BigDecimal rate) {
    return amount.multiply(rate).setScale(USD_SCALE, RoundingMode.HALF_UP);
  }

  private static final String USD_CURRENCY_CODE = "USD";
}

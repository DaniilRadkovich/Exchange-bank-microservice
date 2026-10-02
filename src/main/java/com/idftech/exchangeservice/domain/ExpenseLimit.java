package com.idftech.exchangeservice.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

/**
 * Месячный лимит расходов в USD для пары «счёт + категория».
 *
 * <p>Лимиты неизменяемы: ТЗ п.5 запрещает обновлять существующий лимит, новый лимит создаётся
 * новой записью. Валюта лимита всегда USD — единственная валюта, в которой ведётся учёт.
 *
 * <p>Лимит по умолчанию (ТЗ п.2) физически не хранится: он вычисляется на лету и имеет
 * {@code id == null}. Сумма по умолчанию живёт в {@code LimitProperties.defaultSum} — держать её ещё
 * и в домене означало бы два источника одного правила, и они разошлись бы при смене значения в
 * конфигурации.
 *
 * @param id идентификатор записи либо {@code null} у вычисляемого лимита по умолчанию
 */
public record ExpenseLimit(
    UUID id,
    String accountFrom,
    ExpenseCategory category,
    BigDecimal limitSum,
    Currency currency,
    OffsetDateTime limitDatetime) {

  public static final String USD_CURRENCY_CODE = "USD";
  public static final int USD_SCALE = 2;

  public ExpenseLimit {
    // id может быть null: лимит по умолчанию не хранится в БД, см. javadoc типа.
    Objects.requireNonNull(accountFrom, "accountFrom");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(limitSum, "limitSum");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(limitDatetime, "limitDatetime");
  }

  public static ExpenseLimit create(
      UUID id, String accountFrom, ExpenseCategory category, BigDecimal limitSum, OffsetDateTime at) {
    return new ExpenseLimit(
        id,
        accountFrom,
        category,
        limitSum.setScale(USD_SCALE, java.math.RoundingMode.UNNECESSARY),
        Currency.getInstance(USD_CURRENCY_CODE),
        at);
  }
}

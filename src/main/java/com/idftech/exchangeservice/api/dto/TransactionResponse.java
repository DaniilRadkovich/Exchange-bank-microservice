package com.idftech.exchangeservice.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Ответ по состоянию расчёта транзакции; используется также в ответе {@code POST /transactions}. */
public record TransactionResponse(
    @JsonProperty("transaction_id") UUID transactionId,
    @JsonProperty("account_from") String accountFrom,
    @JsonProperty("account_to") String accountTo,
    @JsonProperty("currency_shortname") String currencyShortname,
    @JsonProperty("sum") BigDecimal sum,
    @JsonProperty("expense_category") String expenseCategory,
    @JsonProperty("datetime") OffsetDateTime datetime,
    @JsonProperty("status") String status,
    @JsonProperty("usd_rate") BigDecimal usdRate,
    @JsonProperty("amount_usd") BigDecimal amountUsd,
    @JsonProperty("limit_exceeded") Boolean limitExceeded) {

  public static TransactionResponse of(ExpenseTransaction transaction) {
    return new TransactionResponse(
        transaction.id(),
        transaction.accountFrom(),
        transaction.accountTo(),
        transaction.currency().getCurrencyCode(),
        transaction.amount(),
        transaction.category().code(),
        transaction.occurredAt(),
        transaction.status().name(),
        transaction.usdRate(),
        transaction.amountUsd(),
        transaction.limitExceeded());
  }
}

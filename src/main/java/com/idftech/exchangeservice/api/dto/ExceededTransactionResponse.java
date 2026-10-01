package com.idftech.exchangeservice.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ответ ТЗ п.6: транзакция, превысившая лимит.
 *
 * <p>Структура повторяет входную (поля 1–6) и добавляет три поля лимита ({@code limit_sum},
 * {@code limit_datetime}, {@code limit_currency_shortname}). Дополнительно возвращаются
 * {@code amount_usd}, {@code usd_rate}, {@code spent_usd} и {@code exceeded_by_usd}: без них клиент
 * не может понять, насколько именно превышен лимит.
 */
@Schema(description = "Расходная операция, превысившая месячный лимит")
public record ExceededTransactionResponse(
    @JsonProperty("transaction_id") UUID transactionId,
    @JsonProperty("account_from") String accountFrom,
    @JsonProperty("account_to") String accountTo,
    @JsonProperty("currency_shortname") String currencyShortname,
    @JsonProperty("sum") BigDecimal sum,
    @JsonProperty("expense_category") String expenseCategory,
    @JsonProperty("datetime") OffsetDateTime datetime,
    @JsonProperty("amount_usd") BigDecimal amountUsd,
    @JsonProperty("usd_rate") BigDecimal usdRate,
    @Schema(description = "Сумма превышенного лимита, USD", example = "1000.00")
    @JsonProperty("limit_sum")
    BigDecimal limitSum,
    @Schema(description = "Дата установки превышенного лимита", example = "2022-01-01T00:00:00+06:00")
    @JsonProperty("limit_datetime")
    OffsetDateTime limitDatetime,
    @Schema(description = "Валюта лимита, всегда USD", example = "USD")
    @JsonProperty("limit_currency_shortname")
    String limitCurrencyShortname,
    @JsonProperty("spent_usd") BigDecimal spentUsd,
    @JsonProperty("exceeded_by_usd") BigDecimal exceededByUsd) {

  public static ExceededTransactionResponse of(ExceededTransaction exceeded) {
    return new ExceededTransactionResponse(
        exceeded.id(),
        exceeded.accountFrom(),
        exceeded.accountTo(),
        exceeded.currency().getCurrencyCode(),
        exceeded.amount(),
        exceeded.category().code(),
        exceeded.occurredAt(),
        exceeded.amountUsd(),
        exceeded.usdRate(),
        exceeded.limit(),
        exceeded.limitDatetime(),
        exceeded.limitCurrency().getCurrencyCode(),
        exceeded.runningTotalUsd(),
        exceeded.exceededBy());
  }

  public static List<ExceededTransactionResponse> of(List<ExceededTransaction> exceeded) {
    return exceeded.stream().map(ExceededTransactionResponse::of).toList();
  }
}

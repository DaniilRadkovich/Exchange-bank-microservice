package com.idftech.exchangeservice.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ответ клиентского API по лимитам.
 *
 * <p>Поля {@code spentUsd} и {@code remainingUsd} заполняются в {@link #ofSpent}: это расход
 * текущего месяца и остаток от последнего установленного лимита. В ответе на {@code POST /limits}
 * они пусты — на момент установки лимита расход ещё не имеет смысла показывать.
 */
@Schema(description = "Месячный лимит расходов и, при наличии, факт расхода за период")
public record LimitResponse(
    @JsonProperty("limit_id") UUID limitId,
    @JsonProperty("account_from") String accountFrom,
    @JsonProperty("expense_category") String expenseCategory,
    @JsonProperty("limit_sum") BigDecimal limitSum,
    @JsonProperty("limit_currency_shortname") String limitCurrencyShortname,
    @JsonProperty("limit_datetime") OffsetDateTime limitDatetime,
    @JsonProperty("spent_usd") BigDecimal spentUsd,
    @JsonProperty("remaining_usd") BigDecimal remainingUsd) {

  /** Ответ без статистики расхода: обычный GET списка лимитов. */
  public static LimitResponse of(ExpenseLimit limit) {
    return new LimitResponse(
        limit.id(),
        limit.accountFrom(),
        limit.category().code(),
        limit.limitSum(),
        limit.currency().getCurrencyCode(),
        limit.limitDatetime(),
        null,
        null);
  }

  /** Разворачивает список лимитов в ответ API. */
  public static List<LimitResponse> of(List<ExpenseLimit> limits) {
    return limits.stream().map(LimitResponse::of).toList();
  }

  /** Ответ с расходом периода и остатком: используется в {@code GET /limits}. */
  public static LimitResponse ofSpent(TransactionStore.LimitWithSpent limit) {
    return new LimitResponse(
        limit.limitId(),
        limit.accountFrom(),
        limit.category().code(),
        limit.limitSum(),
        limit.limitCurrency().getCurrencyCode(),
        limit.limitDatetime(),
        limit.spentUsd(),
        limit.remainingUsd());
  }

  /** Разворачивает список лимитов с расходом в ответ API. */
  public static List<LimitResponse> ofSpent(List<TransactionStore.LimitWithSpent> limits) {
    return limits.stream().map(LimitResponse::ofSpent).toList();
  }
}

package com.idftech.exchangeservice.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Входная структура расходной операции (ТЗ, раздел «Структура данных транзакции на входе»).
 *
 * @param accountFrom банковский счёт клиента, ровно 10 цифр
 * @param accountTo банковский счёт контрагента, ровно 10 цифр
 * @param currencyShortname код валюты ISO 4217
 * @param sum сумма транзакции, два знака после точки
 * @param expenseCategory категория расхода: {@code product} или {@code service}
 * @param datetime момент операции с часовым поясом, ISO 8601
 * @param transactionId идентификатор операции на стороне банка; необязателен, генерируется сервисом
 */
public record TransactionRequest(
    @NotBlank
        @Pattern(regexp = "\\d{10}", message = "must contain exactly 10 digits")
        @JsonProperty("account_from")
        String accountFrom,
    @NotBlank
        @Pattern(regexp = "\\d{10}", message = "must contain exactly 10 digits")
        @JsonProperty("account_to")
        String accountTo,
    @NotBlank
        @Size(min = 3, max = 3)
        @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO 4217 code")
        @JsonProperty("currency_shortname")
        String currencyShortname,
    @NotNull
        @DecimalMin(value = "0.01", message = "must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "must have at most 2 decimal places")
        @JsonProperty("sum")
        BigDecimal sum,
    @NotBlank
        @Pattern(regexp = "(?i)product|service", message = "must be either 'product' or 'service'")
        @JsonProperty("expense_category")
        String expenseCategory,
    @NotNull @JsonProperty("datetime") OffsetDateTime datetime,
    @JsonProperty("transaction_id") String transactionId) {}

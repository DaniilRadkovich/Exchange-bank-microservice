package com.idftech.exchangeservice.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Запрос на установку нового месячного лимита (ТЗ п.5).
 *
 * <p>Дату установки клиент не передаёт: её проставляет сервис из бина {@code Clock}, поэтому лимит
 * нельзя «отмотать» в прошлое или в будущее. Обновление существующего лимита не поддерживается —
 * новый лимит создаётся новым запросом.
 *
 * @param accountFrom счёт клиента
 * @param expenseCategory категория расхода: {@code product} или {@code service}
 * @param limitSum сумма лимита в USD, два знака после точки
 */
public record CreateLimitRequest(
    @NotBlank
        @Pattern(regexp = "\\d{10}", message = "Must contain exactly 10 digits")
        @JsonProperty("account_from")
        String accountFrom,
    @NotBlank
        @Pattern(regexp = "(?i)product|service", message = "Must be either 'product' or 'service'")
        @JsonProperty("expense_category")
        String expenseCategory,
    @NotNull
        @DecimalMin(value = "0.01", message = "Must be greater than zero")
        @Digits(integer = 17, fraction = 2, message = "Must have at most 2 decimal places")
        @JsonProperty("limit_sum")
        BigDecimal limitSum) {}

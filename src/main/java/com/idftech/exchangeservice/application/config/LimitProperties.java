package com.idftech.exchangeservice.application.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки лимитов. Значения по умолчанию соответствуют ТЗ п.2 и п.3: месячный лимит 1000 USD.
 *
 * <p>Часового пояса здесь нет намеренно. Границы месяца определены константой
 * {@code BudgetPeriod.LIMIT_TIMEZONE}, и раньше они дополнительно читались из
 * {@code exchange.limit.timezone}: при смене значения в конфиге расчёт даты установки лимита и
 * границ периода в SQL уезжал в другой пояс, а период операции оставался в UTC. Одно место —
 * один пояс, иначе «границы месяца» перестают быть границами.
 *
 * @param defaultSum лимит по умолчанию, если клиент его не устанавливал
 * @param defaultCurrency валюта лимита и учёта расходов
 */
@Validated
@ConfigurationProperties(prefix = "exchange.limit")
public record LimitProperties(BigDecimal defaultSum, String defaultCurrency) {

  public LimitProperties {
    if (defaultSum == null) {
      defaultSum = new BigDecimal("1000.00");
    }
    if (defaultCurrency == null) {
      defaultCurrency = "USD";
    }
  }
}

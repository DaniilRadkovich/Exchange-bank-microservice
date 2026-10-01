package com.idftech.exchangeservice.infra.config;

import java.math.BigDecimal;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки лимитов. Значения по умолчанию соответствуют ТЗ п.2 и п.3: месячный лимит 1000 USD,
 * границы месяца — в UTC.
 *
 * @param zoneId часовой пояс, в котором определяются границы календарного месяца
 * @param defaultSum лимит по умолчанию, если клиент его не устанавливал
 * @param defaultCurrency валюта лимита и учёта расходов
 */
@Validated
@ConfigurationProperties(prefix = "exchange.limit")
public record LimitProperties(
    @Name("timezone") ZoneId zoneId, BigDecimal defaultSum, String defaultCurrency) {

  public LimitProperties {
    if (zoneId == null) {
      zoneId = ZoneId.of("UTC");
    }
    if (defaultSum == null) {
      defaultSum = new BigDecimal("1000.00");
    }
    if (defaultCurrency == null) {
      defaultCurrency = "USD";
    }
  }
}

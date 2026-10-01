package com.idftech.exchangeservice.infra.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки до расчёта транзакций, принятых асинхронно.
 *
 * @param maxAttempts сколько раз повторно пытаться досчитать транзакцию в статусе PENDING/FAILED
 * @param retryDelay пауза между попытками
 * @param processingEnabled признак включения фоновой обработки; в тестах отключается, чтобы
 *     результат был детерминированным
 */
@Validated
@ConfigurationProperties(prefix = "exchange.settlement")
public record SettlementProperties(int maxAttempts, Duration retryDelay, boolean processingEnabled) {

  public SettlementProperties {
    if (maxAttempts <= 0) {
      maxAttempts = 5;
    }
    if (retryDelay == null) {
      retryDelay = Duration.ofSeconds(5);
    }
  }
}

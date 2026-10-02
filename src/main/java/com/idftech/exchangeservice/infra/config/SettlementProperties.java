package com.idftech.exchangeservice.infra.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки до расчёта транзакций, принятых асинхронно.
 *
 * @param maxAttempts сколько попыток дорасчёта допускается до перевода транзакции из PENDING в FAILED
 * @param retryDelay пауза между попытками
 * @param processingEnabled признак включения фоновой обработки; в тестах отключается, чтобы
 *     результат был детерминированным
 * @param parallelism сколько транзакций дорасчитывается одновременно. Виртуальные потоки сами по себе
 *     не ограничивают число задач, а неограниченная пачка забрала бы все соединения пула PostgreSQL
 */
@Validated
@ConfigurationProperties(prefix = "exchange.settlement")
public record SettlementProperties(
    int maxAttempts, Duration retryDelay, boolean processingEnabled, int parallelism) {

  public SettlementProperties {
    if (maxAttempts <= 0) {
      maxAttempts = 5;
    }
    if (retryDelay == null) {
      retryDelay = Duration.ofSeconds(5);
    }
    if (parallelism <= 0) {
      parallelism = 16;
    }
  }
}

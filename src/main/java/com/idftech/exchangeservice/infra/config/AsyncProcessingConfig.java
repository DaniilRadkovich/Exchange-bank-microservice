package com.idftech.exchangeservice.infra.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Асинхронная обработка и планировщик.
 *
 * <p>Виртуальные потоки включаются флагом {@code spring.threads.virtual.enabled=true} (см.
 * {@code application.yaml}): по умолчанию в Spring Boot 4 они выключены, а для этого сервиса важны —
 * обработка транзакций состоит в основном из ожидания внешнего API.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "exchange.settlement",
    name = "processing-enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableAsync
@EnableScheduling
public class AsyncProcessingConfig {

  /** Стандартный пул задач: на виртуальных потоках его размер задаёт не размер пула, а резервирование. */
  @Bean(name = "settlementExecutor")
  public java.util.concurrent.Executor settlementExecutor() {
    return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
  }
}

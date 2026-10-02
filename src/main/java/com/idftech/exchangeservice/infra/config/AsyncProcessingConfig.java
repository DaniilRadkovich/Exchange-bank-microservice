package com.idftech.exchangeservice.infra.config;

import java.util.concurrent.ExecutorService;
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
 *
 * <p>Пул расчёта объявлен безусловно, а не под тем же условием, что планировщик. Иначе интеграционные
 * тесты с {@code processing-enabled=false} не нашли бы бин и упали бы на старте контекста: такие
 * тесты отключают фоновый планировщик ради детерминизма, но досчёт вызывают вручную, и пул им нужен.
 * Сам планировщик остаётся условным — условие стоит на компоненте
 * {@code PendingSettlementScheduler}.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncProcessingConfig {

  /**
   * Пул задач расчёта на виртуальных потоках.
   *
   * <p>Один виртуальный поток на задачу: получение курса — это ожидание сети, а не вычисления, и его
   * размер задаёт не число потоков ОС, а число задач в полёте. Верхняя граница одновременного
   * выполнения обеспечивается самим пулом, см. {@link BoundedVirtualThreadExecutor}.
   */
  @Bean(name = "settlementExecutor", destroyMethod = "close")
  public ExecutorService settlementExecutor(SettlementProperties properties) {
    return new BoundedVirtualThreadExecutor(properties.parallelism());
  }
}

package com.idftech.exchangeservice.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки дорасчёта транзакций, принятых асинхронно.
 *
 * <p>Составлен только из того, что читает код. {@code retry-delay} и {@code processing-enabled} в
 * записи не дублируются: первый потребляет планировщик как placeholder в {@code @Scheduled}, второе
 * — условие создания его бина. Держать те же значения ещё и в record значило бы хранить одну
 * настройку в трёх местах: yaml, аннотация и запись, — и разъехаться с ними при первом же
 * изменении. Читается ли значение — проверяется по вызывающему коду, поэтому свойство, которое
 * никто не читает, сюда не попадает.
 *
 * @param maxAttempts сколько попыток дорасчёта допускается до перевода транзакции из PENDING в FAILED
 * @param batchSize сколько транзакций планировщик берёт за один проход
 * @param parallelism сколько транзакций дорасчитывается одновременно. Виртуальные потоки сами по себе
 *     не ограничивают число задач, а неограниченная пачка забрала бы все соединения пула PostgreSQL
 */
@Validated
@ConfigurationProperties(prefix = "exchange.settlement")
public record SettlementProperties(int maxAttempts, int batchSize, int parallelism) {

  public SettlementProperties {
    if (maxAttempts <= 0) {
      maxAttempts = 5;
    }
    if (batchSize <= 0) {
      batchSize = 100;
    }
    if (parallelism <= 0) {
      parallelism = 16;
    }
  }
}

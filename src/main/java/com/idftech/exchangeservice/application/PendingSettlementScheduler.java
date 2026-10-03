package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.config.SettlementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Фоновая дорасчётка транзакций, оставшихся в статусе {@code PENDING} (ТЗ: внешний API может быть
 * недоступен, данные терять нельзя).
 *
 * <p>Запускается на виртуальных потоках: обработка внешнего API — это ожидание, а не вычисления,
 * поэтому блокирующие вызовы здесь уместны.
 *
 * <p>Отключается флагом {@code exchange.settlement.processing-enabled=false} — в тестах это делает
 * результат детерминированным, а в интеграционных тестах дорасчёт запускается явно.
 *
 * <p>Пауза между проходами и размер пачки берутся из конфигурации, а не из констант: пачка на 100
 * транзакций при параллелизме 16 держит соединения PostgreSQL дольше, чем пачка на 10, и размер
 * пачки меняют под нагрузку, не пересобирая образ. Пауза в yaml объявлена один раз: default в
 * аннотации был бы вторым значением той же настройки, и его можно было бы забыть обновить.
 */
@Component
@ConditionalOnProperty(
    prefix = "exchange.settlement",
    name = "processing-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PendingSettlementScheduler {

  private static final Logger log = LoggerFactory.getLogger(PendingSettlementScheduler.class);

  private final TransactionIntakeService intakeService;
  private final SettlementProperties settlementProperties;

  public PendingSettlementScheduler(
      TransactionIntakeService intakeService, SettlementProperties settlementProperties) {
    this.intakeService = intakeService;
    this.settlementProperties = settlementProperties;
  }

  @Scheduled(fixedDelayString = "${exchange.settlement.retry-delay}")
  public void processPending() {
    int processed = intakeService.settlePending(settlementProperties.batchSize());
    if (processed > 0) {
      log.info("Processed {} pending transactions", processed);
    }
  }
}

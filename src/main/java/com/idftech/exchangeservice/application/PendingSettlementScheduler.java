package com.idftech.exchangeservice.application;

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
 */
@Component
@ConditionalOnProperty(
    prefix = "exchange.settlement",
    name = "processing-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class PendingSettlementScheduler {

  private static final Logger log = LoggerFactory.getLogger(PendingSettlementScheduler.class);

  private static final int BATCH_SIZE = 100;

  private final TransactionIntakeService intakeService;

  public PendingSettlementScheduler(TransactionIntakeService intakeService) {
    this.intakeService = intakeService;
  }

  @Scheduled(fixedDelayString = "${exchange.settlement.retry-delay:5s}")
  public void processPending() {
    int processed = intakeService.settlePending(BATCH_SIZE);
    if (processed > 0) {
      log.info("Processed {} pending transactions", processed);
    }
  }
}

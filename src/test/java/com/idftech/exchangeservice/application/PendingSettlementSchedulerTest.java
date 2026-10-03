package com.idftech.exchangeservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.idftech.exchangeservice.application.config.SettlementProperties;
import java.lang.reflect.RecordComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Планировщик берёт размер пачки из конфигурации.
 *
 * <p>Константа в коде — это настройка, забытая при настройке: пачка на 100 транзакций при
 * параллелизме 16 держит соединения PostgreSQL вчетверо дольше, чем пачка на 25, и под нагрузкой
 * это выедает пул расчёта раньше, чем увидит операционная нагрузка на приём. Проверять тут нечем
 * дольше одного: значение обязано приходить из {@code exchange.settlement.batch-size}, а не из кода.
 */
class PendingSettlementSchedulerTest {

  @Test
  @DisplayName("Размер пачки берётся из настроек, а не из константы")
  void batchSizeComesFromConfiguration() {
    TransactionIntakeService intakeService = mock(TransactionIntakeService.class);
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, new SettlementProperties(5, 7, 16));

    scheduler.processPending();

    verify(intakeService).settlePending(7);
  }

  @Test
  @DisplayName("Пауза планировщика объявлена в конфигурации один раз")
  void retryDelayIsNotDuplicatedInCode() throws NoSuchMethodException {
    Scheduled scheduled =
        PendingSettlementScheduler.class.getMethod("processPending").getAnnotation(Scheduled.class);

    // Значение после двоеточия было бы вторым объявлением той же настройки: yaml перестал бы
    // влиять на паузу молча, и удивляться пришлось бы планировщику, а не конфигурации.
    assertThat(scheduled.fixedDelayString()).doesNotContain(":");
    // Настройка, которую никто не читает, — ошибка (правило 14): retryDelay и processingEnabled
    // читаются аннотацией и условием создания бина, поэтому в record им не место.
    assertThat(SettlementProperties.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly("maxAttempts", "batchSize", "parallelism");
  }
}

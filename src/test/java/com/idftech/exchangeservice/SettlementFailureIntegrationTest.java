package com.idftech.exchangeservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.idftech.exchangeservice.application.ExchangeRateService;
import com.idftech.exchangeservice.application.SettlementApplier;
import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.application.exception.RateCallCancelledException;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Сорвавшийся расчёт, а не недоступный курс: попытка обязана засчитываться в обоих случаях.
 *
 * <p>Раньше счётчик {@code settlement_attempts} рос только в ветке «курс не пришёл». Расчёт, который
 * упал, попытки не тратил, поэтому {@code findPending} возвращал транзакцию каждые
 * {@code retry-delay} секунд, счётчик навсегда оставался нулевым, а {@code FAILED} был недостижим:
 * транзакция крутилась в дорасчёте до перезапуска сервиса.
 *
 * <p>Расчёт срывается подменой {@link SettlementApplier}, а не испорченными данными: тест не должен
 * зависеть от того, что конкретная сумма не влезает в {@code NUMERIC(19,2)}. Ошибка записи в БД
 * проверит отдельным тестом, когда переполнение суммы перестанет быть достижимым.
 *
 * <p>Все операции в USD: внешний API курсов не участвует, и тест проверяет только состояние
 * транзакции, а не заглушки.
 */
class SettlementFailureIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000450";
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();
  private static final int BATCH = 50;

  @Autowired
  private TransactionIntakeService intakeService;

  @MockitoSpyBean
  private SettlementApplier settlementApplier;

  @MockitoSpyBean
  private ExchangeRateService exchangeRateService;

  /**
   * Брать число из конфигурации, а не писать константой: тест обязан ломаться, если
   * {@code exchange.settlement.max-attempts} перестанет влиять на переход в {@code FAILED}.
   */
  @Value("${exchange.settlement.max-attempts}")
  private int maxAttempts;

  @Test
  @DisplayName("Сорвавшийся расчёт тратит попытку и доходит до FAILED, а не крутится вечно")
  void failedSettlementCountsAttemptsUntilFailed() {
    ExpenseTransaction pending = accept("1100.00");
    breakSettlementOf(pending);

    // До предела попыток это штатное ожидание: транзакция в PENDING, но попытка засчитана.
    for (int attempt = 1; attempt < maxAttempts; attempt++) {
      assertThat(intakeService.settlePending(BATCH)).isEqualTo(1);
      assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.PENDING.name());
      assertThat(attemptsInDatabase(pending.id())).isEqualTo(attempt);
    }
    intakeService.settlePending(BATCH);

    assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.FAILED.name());
    assertThat(attemptsInDatabase(pending.id())).isEqualTo(maxAttempts);
    // Расчёт по-прежнему срывается, но планировщик больше её не берёт: иначе бесконечный ретрай
    // каждые retry-delay секунд никогда бы не остановился.
    assertThat(intakeService.findPending(BATCH)).isEmpty();
  }

  @Test
  @DisplayName("Ручной дорасчёт видит причину сбоя и тоже засчитывает попытку")
  void manualSettleCountsAttemptAndRethrowsCause() {
    ExpenseTransaction pending = accept("1100.00");
    breakSettlementOf(pending);

    assertThatThrownBy(() -> intakeService.settle(pending.id()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("settlement is broken");

    assertThat(attemptsInDatabase(pending.id())).isEqualTo(1);
    assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.PENDING.name());
  }

  @Test
  @DisplayName("Параллельные попытки не затирают счётчик друг друга")
  void concurrentAttemptsAreAllCounted() throws Exception {
    // Инкремент, сделанный в Java, конкурентным попыткам не виден: два дорасчёта одной транзакции
    // (два прохода планировщика или две реплики) читают одно значение и пишут одно и то же
    // увеличенное. Попытка терялась, счётчик зависал, а с ним и переход в FAILED: при
    // exchange.settlement.max-attempts: 1 транзакция не дошла бы до него никогда.
    ExpenseTransaction pending = accept("1100.00");
    // Столько попыток, сколько разрешено настройкой: иначе тест не заметил бы, что инкремент
    // считается по-старому, показывая счётчик меньше предела.
    int threads = maxAttempts;
    CyclicBarrier startTogether = new CyclicBarrier(threads);

    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> tasks = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        tasks.add(workers.submit(() -> {
          startTogether.await();
          settlementApplier.registerUnresolvedAttempt(pending.id());
          return null;
        }));
      }
      for (Future<?> task : tasks) {
        task.get(30, TimeUnit.SECONDS);
      }
    }

    // Каждая попытка видна в счётчике, и предел достигнут: заниженный счётчик означал бы, что
    // часть попыток пропала, и FAILED не наступил бы.
    assertThat(attemptsInDatabase(pending.id())).isEqualTo(threads);
    assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.FAILED.name());
  }

  @Test
  @DisplayName("Попытка у уже рассчитанной транзакции не переводит её обратно в FAILED")
  void attemptOnResolvedTransactionChangesNothing() {
    ExpenseTransaction pending = accept("50.00");
    ExpenseTransaction resolved = intakeService.settle(pending.id());
    assertThat(resolved.status()).isEqualTo(TransactionStatus.RATE_RESOLVED);

    assertThat(settlementApplier.registerUnresolvedAttempt(pending.id())).isFalse();

    // Курс, сумма и флаг остались на месте: перевод в FAILED при заполненных полях нарушил бы
    // CHECK ck_expense_tx_resolved_consistent, то есть неудачный дорасчёт упал бы с ошибкой
    // целостности вместо того, чтобы просто ничего не сделать.
    assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.RATE_RESOLVED.name());
    assertThat(attemptsInDatabase(pending.id())).isZero();
    assertThat(amountUsdInDatabase(pending.id())).isEqualByComparingTo("50.00");
  }

  @Test
  @DisplayName("Сбой одного расчёта в пачке не мешает соседним")
  void brokenSettlementDoesNotStopTheRestOfBatch() {
    ExpenseTransaction broken = accept("1100.00");
    ExpenseTransaction healthy = accept("50.00");
    breakSettlementOf(broken);

    assertThat(intakeService.settlePending(BATCH)).isEqualTo(2);

    assertThat(statusInDatabase(healthy.id())).isEqualTo(TransactionStatus.RATE_RESOLVED.name());
    assertThat(amountUsdInDatabase(healthy.id())).isEqualByComparingTo("50.00");
    assertThat(statusInDatabase(broken.id())).isEqualTo(TransactionStatus.PENDING.name());
    assertThat(attemptsInDatabase(broken.id())).isEqualTo(1);
  }

  @Test
  @DisplayName("Сбой получения курса на нашей стороне тоже тратит попытку ручного дорасчёта")
  void manualSettleCountsAttemptWhenRateResolutionFails() {
    ExpenseTransaction pending = accept("50.00");
    // Сбой на нашей стороне по правилу 3 выходит наружу, а не прячется за «курс недоступен».
    // Раньше исключение выходило из settle() раньше ветки orElseGet, и ручной дорасчёт попытку не
    // засчитывал: findPending возвращал транзакцию вечно, а FAILED был недостижим именно там,
    // где дорасчёт запускает человек. Пакетный путь с этим был справлен, поэтому баг и жил.
    doThrow(new DataIntegrityViolationException("cache write failed"))
        .when(exchangeRateService)
        .resolveUsdRate(eq("USD"), any());

    assertThatThrownBy(() -> intakeService.settle(pending.id()))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(attemptsInDatabase(pending.id())).isEqualTo(1);
    assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.PENDING.name());
  }

  @Test
  @DisplayName("Ручной дорасчёт возвращает состояние после попытки, а не снимок до неё")
  void manualSettleReturnsStateAfterAttempt() {
    ExpenseTransaction pending = accept("50.00");
    // Провайдер не дал курса: попытка засчитана, а транзакция ещё ждёт следующего прохода.
    when(exchangeRateService.resolveUsdRate(eq("USD"), any())).thenReturn(Optional.empty());

    for (int attempt = 1; attempt < maxAttempts; attempt++) {
      assertThat(intakeService.settle(pending.id()).status()).isEqualTo(TransactionStatus.PENDING);
      assertThat(attemptsInDatabase(pending.id())).isEqualTo(attempt);
    }

    // Последняя попытка достигает предела и переводит транзакцию в FAILED. Снимок, взятый до неё,
    // утверждал бы PENDING, и вызывающий продолжил бы считать транзакцию расчётной.
    assertThat(intakeService.settle(pending.id()).status()).isEqualTo(TransactionStatus.FAILED);
    assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.FAILED.name());
    assertThat(attemptsInDatabase(pending.id())).isEqualTo(maxAttempts);
  }

  @Test
  @DisplayName("Отмена дорасчёта остановкой сервиса не тратит попытку")
  void cancelledSettlementKeepsAttemptUncounted() {
    ExpenseTransaction pending = accept("50.00");
    // Поток расчёта прерван остановкой сервиса: ни провайдер, ни наш код не сработали плохо.
    doThrow(new RateCallCancelledException("TwelveData cancelled while waiting 5000 ms before a retry", null))
        .when(exchangeRateService)
        .resolveUsdRate(eq("USD"), any());

    assertThatThrownBy(() -> intakeService.settle(pending.id()))
        .isInstanceOf(RateCallCancelledException.class);

    // Попытка не состоялась, поэтому её не за что считать: иначе max-attempts: 1 превращал бы
    // каждый перезапуск сервиса в массовый перевод транзакций в FAILED.
    assertThat(attemptsInDatabase(pending.id())).isZero();
    assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.PENDING.name());
  }

  private ExpenseTransaction accept(String sum) {
    return intakeService.accept(
        nextId(),
        ACCOUNT,
        "0000009999",
        "USD",
        new BigDecimal(sum),
        ExpenseCategory.PRODUCT,
        utc("2022-01-10"));
  }

  /** Расчёт этой транзакции падает с исключением — эмуляция сбоя БД или нашего кода. */
  private void breakSettlementOf(ExpenseTransaction transaction) {
    doThrow(new IllegalStateException("settlement is broken"))
        .when(settlementApplier)
        .apply(eq(transaction.id()), any());
  }

  private String statusInDatabase(UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM expense_transaction WHERE id = ?", String.class, id);
  }

  private int attemptsInDatabase(UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT settlement_attempts FROM expense_transaction WHERE id = ?", Integer.class, id);
  }

  private BigDecimal amountUsdInDatabase(UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT amount_usd FROM expense_transaction WHERE id = ?", BigDecimal.class, id);
  }

  private static UUID nextId() {
    return new UUID(0, ID_SEQUENCE.incrementAndGet());
  }
}
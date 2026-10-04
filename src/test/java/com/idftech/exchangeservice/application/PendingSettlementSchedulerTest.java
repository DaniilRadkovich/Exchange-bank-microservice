package com.idftech.exchangeservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.idftech.exchangeservice.application.config.SettlementProperties;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Фоновая дорасчётка: размер пачки из конфигурации, проход по событию приёма и границы, не дающие
 * двум проходам идти одновременно.
 *
 * <p>Проверяется то, что ломается дороже всего: один проход за раз и одна задача в очереди. Оба
 * ограничения защищают внешний API и пул соединений. Дедупликация курсов по паре «валюта + дата»
 * работает только внутри одного прохода, поэтому два параллельных прохода отправили бы платные
 * запросы по одному ключу дважды, а задача на каждый приём разогнала бы пул расчёта.
 *
 * <p>Проверки без гонок: вместо «подождём, пока фоновый поток отработает» используется executor,
 * который только кладёт задачи в список, а выполняется та задача, которую сам тест и запускает.
 * Исключение одно — проверка потока выполнения, там нужен latch с ожиданием по таймауту.
 */
class PendingSettlementSchedulerTest {

  private final TransactionIntakeService intakeService = mock(TransactionIntakeService.class);
  private final SettlementProperties properties = new SettlementProperties(5, 7, 16);

  /** Пул, выполняющий задачу на месте: так видно всё без фоновых потоков. */
  private final Executor sameThread = Runnable::run;

  /** Пул, который только копирует задачи: их запускает сам тест. */
  private final List<Runnable> queued = new ArrayList<>();

  private final Executor queueOnly = queued::add;

  @Test
  @DisplayName("Размер пачки берётся из настроек, а не из константы")
  void batchSizeComesFromConfiguration() {
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, properties, sameThread);

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

  @Test
  @DisplayName("Принятая операция запускает проход, не дожидаясь планового")
  void acceptedTransactionSchedulesPassImmediately() {
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, properties, sameThread);

    scheduler.onTransactionAccepted(new TransactionAccepted(UUID.randomUUID()));

    verify(intakeService).settlePending(7);
  }

  @Test
  @DisplayName("Слушатель приёма срабатывает после фиксации транзакции, а не во время её работы")
  void acceptedListenerRunsAfterCommit() throws NoSuchMethodException {
    // Приём публикует событие внутри своей транзакции, и до коммита строки в базе нет: проход,
    // поставленный раньше, её бы не нашёл, и операция ждала бы планового прохода.
    TransactionalEventListener listener =
        PendingSettlementScheduler.class
            .getMethod("onTransactionAccepted", TransactionAccepted.class)
            .getAnnotation(TransactionalEventListener.class);

    assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
  }

  @Test
  @DisplayName("Приём только ставит задачу: расчёт не выполняется на потоке HTTP-запроса")
  void acceptedTransactionDoesNotSettleOnTheRequestThread() {
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, properties, queueOnly);

    scheduler.onTransactionAccepted(new TransactionAccepted(UUID.randomUUID()));

    // Пока задача не выполнена, расчёта не было: иначе приём операции ждал бы внешнего API и
    // держал соединение PostgreSQL, а волна приёмов съедала бы пул (правило 10).
    verifyNoInteractions(intakeService);
    assertThat(queued).hasSize(1);
  }

  @Test
  @DisplayName("Пачка приёмов ставит в очередь одну задачу, а не по задаче на операцию")
  void burstOfAcceptedTransactionsQueuesSingleTask() {
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, properties, queueOnly);

    for (int i = 0; i < 10; i++) {
      scheduler.onTransactionAccepted(new TransactionAccepted(UUID.randomUUID()));
    }

    assertThat(queued)
        .as("десять приёмов подряд — одна задача: остальные девять уже поставлены в очередь")
        .hasSize(1);
  }

  @Test
  @DisplayName("Задача выполняется в пуле расчёта, а не в потоке приёма")
  void passRunsOnThePoolThread() throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    String[] taskThread = new String[1];
    Executor poolThread =
        task ->
            new Thread(
                    () -> {
                      taskThread[0] = Thread.currentThread().getName();
                      task.run();
                      done.countDown();
                    })
                .start();
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, properties, poolThread);
    String requestThread = Thread.currentThread().getName();

    scheduler.onTransactionAccepted(new TransactionAccepted(UUID.randomUUID()));

    assertThat(done.await(5, TimeUnit.SECONDS)).as("проход должен завершиться").isTrue();
    assertThat(taskThread[0]).isNotNull().isNotEqualTo(requestThread);
    verify(intakeService).settlePending(7);
  }

  @Test
  @DisplayName("Запрос во время прохода не запускает второй, а заставляет сделать ещё круг")
  void requestDuringRunningPassIsSatisfiedByAnExtraRound() {
    PendingSettlementScheduler[] holder = new PendingSettlementScheduler[1];
    int[] reentrant = {-1};
    int[] rounds = {0};
    when(intakeService.settlePending(7))
        .thenAnswer(
            invocation -> {
              // Запрос изнутри самого прохода и ровно один раз: так проверяются и защита «один
              // проход за раз», и запрет ждать планового прохода. Повторяющийся запрос был бы
              // честным моделированием непрерывного потока операций, но здесь он зациклил бы тест.
              if (rounds[0]++ == 0) {
                reentrant[0] = holder[0].pass();
              }
              return 7;
            });
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, properties, sameThread);
    holder[0] = scheduler;

    int processed = scheduler.pass();

    assertThat(processed).as("два круга по семь транзакций").isEqualTo(14);
    assertThat(reentrant[0])
        .as("вложенный вызов не заходит в расчёт сам: он только просит ещё круг")
        .isZero();
    verify(intakeService, times(2)).settlePending(7);
  }

  @Test
  @DisplayName("После прохода следующий приём снова ставит задачу")
  void nextRequestAfterFinishedPassIsQueued() {
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, properties, queueOnly);

    scheduler.onTransactionAccepted(new TransactionAccepted(UUID.randomUUID()));
    queued.get(0).run();
    scheduler.onTransactionAccepted(new TransactionAccepted(UUID.randomUUID()));

    // Флаг «задача поставлена» снимается до прохода, иначе приём, пришедший во время расчёта,
    // остался бы без прохода до планового.
    assertThat(queued).hasSize(2);
    verify(intakeService).settlePending(7);
  }

  @Test
  @DisplayName("Отказ пула при остановке сервиса не превращается в ошибку приёма")
  void rejectionDuringShutdownDoesNotPropagate() {
    Executor rejectingExecutor =
        task -> {
          throw new RejectedExecutionException("pool is shutting down");
        };
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, properties, rejectingExecutor);

    scheduler.onTransactionAccepted(new TransactionAccepted(UUID.randomUUID()));

    // Транзакция уже сохранена, попытка не потрачена: её заберёт плановый проход или следующий
    // запуск. Ошибка здесь означала бы 500 на приёме из-за остановки сервиса.
    verifyNoInteractions(intakeService);
  }

  @Test
  @DisplayName("После отказа пула проход снова запускается")
  void passWorksAgainAfterRejection() {
    Executor rejectingThenWorking =
        task -> {
          if (queued.isEmpty()) {
            queued.add(task);
            throw new RejectedExecutionException("pool is shutting down");
          }
          task.run();
        };
    PendingSettlementScheduler scheduler =
        new PendingSettlementScheduler(intakeService, properties, rejectingThenWorking);

    scheduler.requestPass();
    queued.get(0).run();
    scheduler.requestPass();

    // Два прохода, два запроса: отказ не должен навсегда отключить запуск по событию. Первый проход
    // был выполнен вручную, второй — уже обычной задачей пула.
    verify(intakeService, times(2)).settlePending(7);
  }
}

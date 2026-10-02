package com.idftech.exchangeservice.infra.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Граница одновременного выполнения действует на всех точках входа пула.
 *
 * <p>Проверяется без Spring и без {@code Thread.sleep}: задачи блокируются на защёлки, которую
 * открывает сам тест. Сон был бы ненадёжен — при медленной машине лишние задачи успевали бы
 * «поместиться» в окно и тест прошёл бы сломанную реализацию.
 */
class BoundedVirtualThreadExecutorTest {

  private static final int PARALLELISM = 2;
  private static final int TASKS = 6;

  /** Пик одновременно выполняющихся задач и защёлки, которыми тест управляет запуском. */
  private final AtomicInteger running = new AtomicInteger();
  private final AtomicInteger peak = new AtomicInteger();
  private final CountDownLatch started = new CountDownLatch(PARALLELISM);
  private final CountDownLatch release = new CountDownLatch(1);

  @Test
  @DisplayName("execute не даёт выполнить больше задач, чем задано parallelism")
  void executeIsBounded() throws Exception {
    try (BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(PARALLELISM)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < TASKS; i++) {
        futures.add(executor.submit(this::blockingTask));
      }

      assertOnlyParallelismSlotsAreTaken();
      release.countDown();
      awaitAll(futures);
    }
    assertThat(peak.get()).isEqualTo(PARALLELISM);
  }

  @Test
  @DisplayName("execute с Runnable тоже удерживает слот до конца задачи")
  void executeWithRunnableIsBounded() throws Exception {
    try (BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(PARALLELISM)) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < TASKS; i++) {
        futures.add(executor.submit(this::blockingTask, null));
      }

      assertOnlyParallelismSlotsAreTaken();
      release.countDown();
      awaitAll(futures);
    }
    assertThat(peak.get()).isEqualTo(PARALLELISM);
  }

  @Test
  @DisplayName("invokeAll не обходит границу")
  void invokeAllIsBounded() throws Exception {
    List<Callable<Void>> tasks = new ArrayList<>();
    for (int i = 0; i < TASKS; i++) {
      tasks.add(this::blockingTask);
    }

    CountDownLatch finished = new CountDownLatch(1);
    try (BoundedVirtualThreadExecutor executor = new BoundedVirtualThreadExecutor(PARALLELISM)) {
      Thread caller = Thread.ofPlatform().start(
          () -> {
            try {
              executor.invokeAll(tasks);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              finished.countDown();
            }
          });

      assertOnlyParallelismSlotsAreTaken();
      release.countDown();
      assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
      caller.join(10_000);
    }
    assertThat(peak.get()).isEqualTo(PARALLELISM);
  }

  /**
   * Ждём, пока слоты забьются, и убеждаемся, что шестая задача внутрь не пролезла.
   *
   * <p>Проверка именно «застряла снаружи», а не «выполнилась позже»: иначе тест прошёл бы и при
   * полностью снятом ограничении, просто с большим пиком.
   */
  private void assertOnlyParallelismSlotsAreTaken() throws InterruptedException {
    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(running).hasValue(PARALLELISM);
  }

  private Void blockingTask() {
    running.incrementAndGet();
    peak.accumulateAndGet(running.get(), Math::max);
    started.countDown();
    try {
      release.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      running.decrementAndGet();
    }
    return null;
  }

  private void awaitAll(List<Future<?>> futures) throws Exception {
    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }
  }
}

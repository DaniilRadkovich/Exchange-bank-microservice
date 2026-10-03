package com.idftech.exchangeservice.infra.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idftech.exchangeservice.application.exception.RateCallCancelledException;
import com.idftech.exchangeservice.application.config.RateProviderProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * Прерывание повторов не выдаётся за отказ внешнего API.
 *
 * <p>Остановка сервиса прерывает потоки расчёта. Раньше {@code InterruptedException} при паузе перед
 * повтором проглатывался, цикл доводил попытки до конца и возвращал {@code null}: остановка выглядела
 * как недоступность биржи, а попытка дорасчёта засчитывалась как неудача. При
 * {@code exchange.settlement.max-attempts: 1} хватало одного перезапуска, чтобы перевести
 * нормальные транзакции в {@code FAILED} и выдать банку список на ручной дорасчёт.
 *
 * <p>Отмена обязана быть видна наружу ({@link RateCallCancelledException}) и не начинать новых
 * попыток: повтор после остановки — работа, которой никто не ждёт.
 */
class RetryingCallerTest {

  private static final int MAX_RETRIES = 3;

  @Test
  @DisplayName("Прерванный до вызова поток не начинает ни одной попытки")
  void interruptedThreadDoesNotStartAnyAttempt() {
    RetryingCaller caller = caller();
    AtomicInteger attempts = new AtomicInteger();

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(
              () ->
                  caller.call(
                      () -> {
                        attempts.incrementAndGet();
                        return "rate";
                      },
                      MAX_RETRIES,
                      "TwelveData"))
          .isInstanceOf(RateCallCancelledException.class)
          .hasMessageContaining("cancelled before attempt 1");
    } finally {
      Thread.interrupted();
    }

    // Попытка не состоялась: виноват не провайдер, и жаловаться ему не на что.
    assertThat(attempts).hasValue(0);
  }

  @Test
  @DisplayName("Прерывание во время паузы отменяет вызов, а не возвращает «курс недоступен»")
  void interruptionDuringBackoffIsCancellationNotProviderFailure() throws InterruptedException {
    RetryingCaller caller = caller();
    AtomicInteger attempts = new AtomicInteger();
    AtomicReference<RateCallCancelledException> cancellation = new AtomicReference<>();
    AtomicReference<Object> returned = new AtomicReference<>();
    AtomicBoolean interruptFlagKept = new AtomicBoolean();

    // Прерывание внутри самой операции делает тест детерминированным: поток засыпает уже с
    // установленным флагом, поэтому Thread.sleep бросает InterruptedException немедленно, без
    // гонки и без Thread.sleep в тесте.
    Thread worker =
        Thread.ofVirtual()
            .unstarted(
                () -> {
                  try {
                    returned.set(
                        caller.call(
                            () -> {
                              attempts.incrementAndGet();
                              Thread.currentThread().interrupt();
                              throw new ResourceAccessException("read timed out");
                            },
                            MAX_RETRIES,
                            "TwelveData"));
                  } catch (RateCallCancelledException e) {
                    cancellation.set(e);
                    interruptFlagKept.set(Thread.currentThread().isInterrupted());
                  }
                });
    worker.start();
    worker.join(Duration.ofSeconds(10).toMillis());

    // null означал бы «провайдер не дал курса»: попытка была бы засчитана как неудача.
    assertThat(returned.get()).isNull();
    assertThat(cancellation.get()).isNotNull();
    // Флаг прерывания восстановлен, иначе следующий код вышел бы InterruptedException оттуда,
    // где никто не ждёт прерывания.
    assertThat(interruptFlagKept).isTrue();
    // Ровно одна попытка: прерванная пауза не съедает оставшиеся повторы.
    assertThat(attempts).hasValue(1);
  }

  private static RetryingCaller caller() {
    Duration backoff = Duration.ofMillis(5);
    return new RetryingCaller(
        new RateProviderProperties(
            "twelve-data",
            "http://localhost/",
            "key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            MAX_RETRIES,
            backoff,
            backoff,
            Duration.ofDays(7)));
  }
}

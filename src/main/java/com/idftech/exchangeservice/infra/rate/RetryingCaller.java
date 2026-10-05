package com.idftech.exchangeservice.infra.rate;

import com.idftech.exchangeservice.application.exception.RateCallCancelledException;
import com.idftech.exchangeservice.application.config.RateProviderProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Повторные попытки вызова внешнего API (ТЗ: «заложите таймауты и обработку сбоев»).
 *
 * <p>Повторяются только временные сбои: таймауты и сетевые ошибки (то, что может пройти само), а
 * также 429 и 5xx от источника. Ошибки 4xx не повторяются — некорректный ключ или символ не
 * станут корректными от повтора.
 *
 * <p>Задержка растёт экспоненциально и добавляет случайный разброс (jitter): без него все потоки,
 * освободившиеся после таймаута источника, ударили бы в него одновременно.
 *
 * <p>Границы задержки берутся из {@code exchange.rates.retry-initial-backoff} и
 * {@code retry-max-backoff}, а не зашиты в код: иначе настройка в yaml была бы декоративной.
 *
 * <p>После исчерпания попыток вызывающий код получает {@code null} и продолжает работу: приём
 * транзакций не должен зависеть от доступности внешнего источника.
 *
 * <h2>Прерывание</h2>
 *
 * <p>Прерванный поток — это не отказ провайдера. Раньше {@code InterruptedException} при паузе перед
 * повтором проглатывался, цикл доводил попытки до конца и возвращал {@code null}: остановка сервиса
 * выглядела как недоступность биржи, а попытка дорасчёта засчитывалась как неудача. Теперь пауза
 * бросает {@link RateCallCancelledException}, и транзакция остаётся нетронутой до следующего
 * прохода. Прерванный поток дополнительно не начинает новых попыток вовсе.
 */
@Component
public class RetryingCaller {

  private static final Logger log = LoggerFactory.getLogger(RetryingCaller.class);

  private final RateProviderProperties properties;

  public RetryingCaller(RateProviderProperties properties) {
    this.properties = properties;
  }

  /** Вызывает операцию с повторными попытками; {@code null} в результате означает отказ. */
  public <T> T call(RetriableOperation<T> operation, int maxRetries, String operationName) {
    int attempts = Math.max(1, maxRetries + 1);
    RuntimeException lastFailure = null;

    for (int attempt = 1; attempt <= attempts; attempt++) {
      if (Thread.currentThread().isInterrupted()) {
        throw new RateCallCancelledException(
            operationName + " cancelled before attempt " + attempt + ": thread is interrupted", null);
      }
      try {
        return operation.execute();
      } catch (RuntimeException e) {
        if (!isRetryable(e)) {
          log.warn("{} failed with a non-retryable error: {}", operationName, e.toString());
          return null;
        }
        lastFailure = e;
        if (attempt == attempts) {
          break;
        }
        Duration backoff = backoffFor(attempt);
        log.warn(
            "{} attempt {}/{} failed ({}), retrying in {} ms",
            operationName,
            attempt,
            attempts,
            e.toString(),
            backoff.toMillis());
        sleep(operationName, backoff);
      }
    }

    log.warn("{} exhausted {} attempts; last failure: {}", operationName, attempts, lastFailure);
    return null;
  }

  /** Транзиентные сбои: сетевые ошибки, таймауты, 429 и 5xx. */
  private boolean isRetryable(RuntimeException e) {
    if (e instanceof ResourceAccessException) {
      return true;
    }
    if (e instanceof RestClientResponseException response) {
      int status = response.getStatusCode().value();
      return status == 429 || status >= 500;
    }
    Throwable cause = e.getCause();
    return cause instanceof IOException;
  }

  /**
   * Задержка перед попыткой {@code attempt}: экспоненциальный рост, обрезанный по
   * {@code retry-max-backoff}, с разбросом в четверть интервала, чтобы параллельные потоки не
   * синхронизировались.
   */
  private Duration backoffFor(int attempt) {
    long initial = Math.max(1, properties.retryInitialBackoff().toMillis());
    long max = Math.max(initial, properties.retryMaxBackoff().toMillis());
    long exponential = initial * (1L << Math.min(attempt - 1, 20));
    long capped = Math.min(exponential, max);
    long jitter = Math.max(1, capped / 4);
    return Duration.ofMillis(capped - jitter + ThreadLocalRandom.current().nextLong(0, 2 * jitter + 1));
  }

  /**
   * Пауза перед повтором.
   *
   * <p>Прерывание не возвращается молча: пауза означает, что сервис останавливают, а повтор после
   * остановки — работа, которой никто не ждёт. Повод возвращается вызывающему как
   * {@link RateCallCancelledException}, чтобы попытка дорасчёта не была засчитана неудачей.
   */
  private void sleep(String operationName, Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RateCallCancelledException(
          operationName + " cancelled while waiting " + duration.toMillis() + " ms before a retry", e);
    }
  }

  /** Операция, которую разрешено повторять. */
  @FunctionalInterface
  public interface RetriableOperation<T> {
    T execute();
  }
}
package com.idftech.exchangeservice;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Счётчик одновременных обращений к внешнему API курсов.
 *
 * <p>Нужен тестам параллельности. Измерение «сколько запросов было в полёте одновременно» надёжнее
 * сравнения времени выполнения: оно не зависит от загрузки машины и не требует подбирать задержку
 * заглушки так, чтобы разница была заметнее шума.
 *
 * <p>Заявки на вход и выход приходят из потоков WireMock, поэтому состояние изменяется только через
 * {@link AtomicInteger} и сравнение с обновлением.
 */
final class ConcurrentRequestCounter {

  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger peak = new AtomicInteger();

  void enter() {
    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
  }

  void exit() {
    inFlight.decrementAndGet();
  }

  int peak() {
    return peak.get();
  }

  void reset() {
    inFlight.set(0);
    peak.set(0);
  }
}

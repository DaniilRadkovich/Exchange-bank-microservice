package com.idftech.exchangeservice.application.exception;

/**
 * Вызов внешнего API прерван, а не завершился отказом.
 *
 * <p>Повод — остановка сервиса или отмена задачи: поток, на котором шёл расчёт, получил
 * {@code InterruptedException}. Это не сбой провайдера и не сбой нашего сервиса, поэтому
 * {@code null} вместо курса здесь означал бы «курс недоступен», а попытка дорасчёта была бы
 * засчитана. При {@code exchange.settlement.max-attempts: 1} достаточно было бы одного
 * перезапуска сервиса, чтобы перевести нормальные транзакции в {@code FAILED} и сказать банку
 * досчитывать их вручную.
 *
 * <p>Отсюда два правила, которые обязан соблюдать каждый обработчик:
 * <ul>
 *   <li>исключение пробрасывается наружу, а не глотается;
 *   <li>попытка дорасчёта не засчитывается — транзакция остаётся ровно в том состоянии, в котором
 *       была, и следующий проход планировщика подхватит её без потерь.
 * </ul>
 */
public class RateCallCancelledException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public RateCallCancelledException(String message, Throwable cause) {
    super(message, cause);
  }
}

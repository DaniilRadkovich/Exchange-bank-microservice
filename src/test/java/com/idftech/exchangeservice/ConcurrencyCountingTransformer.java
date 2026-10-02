package com.idftech.exchangeservice;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import java.time.Duration;

/**
 * Заглушка-преобразователь: считает одновременные обращения к внешнему API, удерживая их заданное время.
 *
 * <p>Нужен тестам параллельности. Он выполняется до отправки ответа клиенту, поэтому запрос в этот
 * момент ещё «в полёте»: счётчик растёт на входе и падает на выходе, а зафиксированный максимум равен
 * числу запросов, действительно шедших одновременно.
 *
 * <p>Пауза задана здесь, а не через {@code withFixedDelay}, намеренно: задержка ответа применяется уже
 * после преобразователя, и счётчик к этому моменту уже обнулился. Пик тогда отражал бы не сетевое
 * ожидание, а скорость сборки ответа, то есть всегда почти единицу.
 *
 * <p>Сравнивать время выполнения вместо этого нельзя: результат зависел бы от загрузки машины и
 * требовал бы подбирать задержку так, чтобы разница была заметнее шума. Пик одновременных запросов от
 * шума не зависит.
 *
 * <p>Экземпляр регистрируется в самом WireMock как расширение, а заглушка ссылается на него по имени.
 * Передача экземпляра прямо в заглушке не работает: преобразователь без зарегистрированного класса не
 * вызывается, и пик молча оставался бы нулём — тест прошёл бы на пустом измерении.
 */
final class ConcurrencyCountingTransformer extends ResponseDefinitionTransformer {

  public static final String NAME = "concurrency-counter";

  private final ConcurrentRequestCounter counter;

  /** Сколько провайдер «думает» перед ответом; меняется тестом между прогонами. */
  private volatile Duration holdTime = Duration.ZERO;

  ConcurrencyCountingTransformer(ConcurrentRequestCounter counter) {
    this.counter = counter;
  }

  void holdEachRequestFor(Duration holdTime) {
    this.holdTime = holdTime;
  }

  @Override
  public ResponseDefinition transform(
      Request request,
      ResponseDefinition responseDefinition,
      FileSource files,
      Parameters parameters) {
    counter.enter();
    try {
      if (!holdTime.isZero()) {
        Thread.sleep(holdTime.toMillis());
      }
      return responseDefinition;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return responseDefinition;
    } finally {
      counter.exit();
    }
  }

  @Override
  public String getName() {
    return NAME;
  }
}

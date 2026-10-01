package com.idftech.exchangeservice.infra.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки клиента внешнего API биржевых курсов (ТЗ п.3).
 *
 * <p>Стратегия отказоустойчивости: приём транзакций не должен терять данные из-за недоступности
 * внешнего API, поэтому таймауты короткие, а неудачные попытки ограничены по числу и растут по
 * времени. Итоговая стратегия описывается в README.
 *
 * @param provider идентификатор провайдера, используется для выбора адаптера
 * @param baseUrl базовый URL внешнего API
 * @param apiKey ключ доступа; передаётся переменной окружения, в репозиторий не коммитится
 * @param connectTimeout таймаут установки соединения
 * @param readTimeout таймаут чтения ответа
 * @param maxRetries число повторных попыток при 5xx и таймаутах
 * @param retryInitialBackoff начальная задержка между попытками
 * @param retryMaxBackoff максимальная задержка между попытками
 */
@Validated
@ConfigurationProperties(prefix = "exchange.rates")
public record RateProviderProperties(
    String provider,
    String baseUrl,
    String apiKey,
    Duration connectTimeout,
    Duration readTimeout,
    int maxRetries,
    Duration retryInitialBackoff,
    Duration retryMaxBackoff) {

  public RateProviderProperties {
    if (connectTimeout == null) {
      connectTimeout = Duration.ofSeconds(2);
    }
    if (readTimeout == null) {
      readTimeout = Duration.ofSeconds(3);
    }
    if (maxRetries <= 0) {
      maxRetries = 3;
    }
    if (retryInitialBackoff == null) {
      retryInitialBackoff = Duration.ofMillis(200);
    }
    if (retryMaxBackoff == null) {
      retryMaxBackoff = Duration.ofSeconds(2);
    }
  }
}

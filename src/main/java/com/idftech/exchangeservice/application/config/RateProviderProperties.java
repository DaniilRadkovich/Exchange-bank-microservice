package com.idftech.exchangeservice.application.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
 * <p>Помимо HTTP-клиента здесь настройка политики кэша: {@code maxFallbackAge} ограничивает, насколько
 * старым может быть курс, взятый из базы как резерв.
 *
 * <p>Ключ и адрес проверяются на старте, а не на первом запросе: пустой ключ иначе позволял бы
 * подняться «здоровому» сервису, который каждую FX-операцию отправлял бы в {@code FAILED}, а в логе
 * виноватым выглядел бы провайдер. Без схемы в адресе {@code RestClient} падал бы на первом же
 * запросе с невнятным исключением вместо понятной ошибки конфигурации.
 *
 * @param provider идентификатор провайдера, используется для выбора адаптера
 * @param baseUrl базовый URL внешнего API
 * @param apiKey ключ доступа. Берётся только из переменной окружения {@code RATES_API_KEY}:
 *     значения по умолчанию нет, поэтому секрет не может случайно попасть в репозиторий, а сервис
 *     без ключа не поднимается — {@code @NotBlank} превращает пустое значение в понятную ошибку
 *     конфигурации на старте
 * @param connectTimeout таймаут установки соединения
 * @param readTimeout таймаут чтения ответа
 * @param maxRetries число повторных попыток при 5xx и таймаутах
 * @param retryInitialBackoff начальная задержка между попытками
 * @param retryMaxBackoff максимальная задержка между попытками
 * @param maxFallbackAge насколько старым может быть резервный курс из кэша. Без предела операция,
 *     датированная годом назад, получила бы курс последней доступной даты и молча посчиталась по
 *     нему: цифра выглядит правдоподобно, но денежно неверна
 */
@Validated
@ConfigurationProperties(prefix = "exchange.rates")
public record RateProviderProperties(
    String provider,
    @Pattern(regexp = "^https?://.+", message = "must start with http:// or https://")
    String baseUrl,
    @NotBlank(message = "must not be empty: set RATES_API_KEY")
    String apiKey,
    Duration connectTimeout,
    Duration readTimeout,
    int maxRetries,
    Duration retryInitialBackoff,
    Duration retryMaxBackoff,
    Duration maxFallbackAge) {

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
    if (maxFallbackAge == null || maxFallbackAge.isNegative() || maxFallbackAge.isZero()) {
      maxFallbackAge = Duration.ofDays(7);
    }
  }
}

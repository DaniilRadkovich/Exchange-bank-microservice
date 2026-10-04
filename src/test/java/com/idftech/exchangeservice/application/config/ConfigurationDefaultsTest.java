package com.idftech.exchangeservice.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Значения по умолчанию в records совпадают с {@code application.yaml}.
 *
 * <p>Одно и то же значение объявлено дважды: в yaml, откуда его читает приложение, и в record, где оно
 * подставляется при отсутствии свойства. Само по себе это обычная страховка, но как только значения
 * разъезжаются, появляется настройка, которая в yaml выглядит действующей, а на деле не читается, —
 * ровно то, за что в проекте ругают мёртвые свойства.
 *
 * <p>Yaml читается с classpath, а не копируется в тест: вторая декларация того же набора чисел
 * разъехалась бы вместе с ним и проверила бы саму себя.
 */
class ConfigurationDefaultsTest {

  private static final PropertySource<?> YAML = loadYaml();

  @Test
  @DisplayName("Лимит по умолчанию в yaml совпадает с дефолтом LimitProperties")
  void limitDefaultsMatchYaml() {
    LimitProperties defaults = new LimitProperties(null, null);

    // Сравнение по значению, а не по тексту: YAML разбирает 1000.00 как 1000.0, и разница в
    // масштабе была бы ложным расхождением. Масштаб приводит к двум знакам сам LimitCalculator.
    assertThat(new BigDecimal(yaml("exchange.limit.default-sum")))
        .isEqualByComparingTo(defaults.defaultSum());
    assertThat(yaml("exchange.limit.default-currency")).isEqualTo(defaults.defaultCurrency());
  }

  @Test
  @DisplayName("Настройки провайдера курсов в yaml совпадают с дефолтами RateProviderProperties")
  void rateProviderDefaultsMatchYaml() {
    // maxRetries = 0: дефолт срабатывает на неположительном значении, поэтому сверяется он сам.
    RateProviderProperties defaults =
        new RateProviderProperties(
            "twelvedata", "https://api.twelvedata.com", "not-empty",
            null, null, 0, null, null, null);

    assertThat(duration(yaml("exchange.rates.connect-timeout"))).isEqualTo(defaults.connectTimeout());
    assertThat(duration(yaml("exchange.rates.read-timeout"))).isEqualTo(defaults.readTimeout());
    assertThat(Integer.parseInt(yaml("exchange.rates.max-retries"))).isEqualTo(defaults.maxRetries());
    assertThat(duration(yaml("exchange.rates.retry-initial-backoff")))
        .isEqualTo(defaults.retryInitialBackoff());
    assertThat(duration(yaml("exchange.rates.retry-max-backoff"))).isEqualTo(defaults.retryMaxBackoff());
    assertThat(duration(yaml("exchange.rates.max-fallback-age"))).isEqualTo(defaults.maxFallbackAge());
  }

  @Test
  @DisplayName("Настройки расчёта в yaml совпадают с дефолтами SettlementProperties")
  void settlementDefaultsMatchYaml() {
    // Дефолты в record срабатывают только на неположительных значениях, поэтому сверяются границы:
    // yaml не должен уходить ниже единицы, иначе молча включился бы запасной дефолт.
    SettlementProperties guard = new SettlementProperties(0, 0, 0);

    assertThat(Integer.parseInt(yaml("exchange.settlement.max-attempts")))
        .isEqualTo(guard.maxAttempts());
    assertThat(Integer.parseInt(yaml("exchange.settlement.batch-size"))).isEqualTo(guard.batchSize());
    assertThat(Integer.parseInt(yaml("exchange.settlement.parallelism"))).isEqualTo(guard.parallelism());
  }

  private static String yaml(String key) {
    return String.valueOf(YAML.getProperty(key));
  }

  /** Формат yaml («2s», «200ms») не ISO-8601, поэтому парсится тем же стилем, что и в приложении. */
  private static Duration duration(String value) {
    return DurationStyle.detectAndParse(value);
  }

  private static PropertySource<?> loadYaml() {
    try {
      Resource resource = new ClassPathResource("application.yaml");
      for (PropertySource<?> source : new YamlPropertySourceLoader().load("application", resource)) {
        // YamlPropertySourceLoader разворачивает вложенность в плоские ключи exchange.*.*,
        // поэтому секция ищется по префиксу, а не по ключу верхнего уровня.
        if (source instanceof MapPropertySource map
            && map.getSource().keySet().stream().anyMatch(key -> key.startsWith("exchange."))) {
          return source;
        }
      }
      throw new IllegalStateException("В application.yaml нет секции exchange");
    } catch (IOException e) {
      throw new IllegalStateException("Не удалось прочитать application.yaml", e);
    }
  }
}
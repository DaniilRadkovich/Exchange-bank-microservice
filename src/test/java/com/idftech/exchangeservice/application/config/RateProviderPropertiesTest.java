package com.idftech.exchangeservice.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Ключ и адрес провайдера проверяются на старте.
 *
 * <p>Пустой ключ иначе позволял бы подняться «здоровому» сервису, который каждую FX-операцию
 * отправлял бы в {@code FAILED}, а в логе виноватым выглядел бы провайдер: оператор чинил бы
 * несуществующую проблему с биржей. Без схемы в адресе {@code RestClient} падал бы на первом запросе
 * с невнятным исключением вместо понятной ошибки конфигурации.
 *
 * <p>Проверка идёт через стандартный {@code Validator}, как это делает Spring при биндинге
 * {@code @ConfigurationProperties} с {@code @Validated}.
 */
class RateProviderPropertiesTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  @DisplayName("Пустой ключ доступа отвергается на старте")
  void emptyApiKeyIsRejected() {
    RateProviderProperties properties = properties("");

    Set<ConstraintViolation<RateProviderProperties>> violations = VALIDATOR.validate(properties);

    assertThat(violations)
        .extracting(ConstraintViolation::getMessage)
        .anyMatch(message -> message.contains("RATES_API_KEY"));
  }

  @Test
  @DisplayName("Ключ, состоящий из пробелов, тоже отвергается")
  void blankApiKeyIsRejected() {
    assertThat(VALIDATOR.validate(properties("   "))).isNotEmpty();
  }

  @Test
  @DisplayName("Адрес без схемы отвергается")
  void baseUrlWithoutSchemeIsRejected() {
    RateProviderProperties properties =
        new RateProviderProperties(
            "twelve-data",
            "api.twelvedata.com",
            "key",
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            3,
            Duration.ofMillis(200),
            Duration.ofSeconds(2),
            Duration.ofDays(7));

    assertThat(VALIDATOR.validate(properties))
        .extracting(ConstraintViolation::getMessage)
        .anyMatch(message -> message.contains("http://"));
  }

  @Test
  @DisplayName("Корректная конфигурация проходит проверку")
  void validConfigurationPasses() {
    assertThat(VALIDATOR.validate(properties("test-key"))).isEmpty();
  }

  @Test
  @DisplayName("Контекст не поднимается без ключа доступа")
  void contextFailsToStartWithoutApiKey() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(
            "exchange.rates.provider=twelve-data",
            "exchange.rates.base-url=https://api.twelvedata.com",
            "exchange.rates.api-key=")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("Контекст поднимается с ключом")
  void contextStartsWithApiKey() {
    new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues(
            "exchange.rates.provider=twelve-data",
            "exchange.rates.base-url=https://api.twelvedata.com",
            "exchange.rates.api-key=test-key")
        .run(context -> assertThat(context).hasNotFailed());
  }

  @Configuration
  @EnableConfigurationProperties(RateProviderProperties.class)
  static class PropertiesConfiguration {}

  private static RateProviderProperties properties(String apiKey) {
    return new RateProviderProperties(
        "twelve-data",
        "https://api.twelvedata.com",
        apiKey,
        Duration.ofSeconds(1),
        Duration.ofSeconds(1),
        3,
        Duration.ofMillis(200),
        Duration.ofSeconds(2),
        Duration.ofDays(7));
  }
}
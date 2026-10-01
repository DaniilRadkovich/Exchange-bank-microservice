package com.idftech.exchangeservice.infra.config;

import com.idftech.exchangeservice.application.LimitCalculator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Регистрация компонентов прикладного слоя, не являющихся сервисами Spring. */
@Configuration
public class ApplicationConfig {

  /**
   * Доменный сервис расчёта лимитов.
   *
   * <p>Создаётся вручную, а не через аннотацию: {@code LimitCalculator} — чистый объект без
   * зависимостей от фреймворка, и его проще создать напрямую в юнит-тестах.
   */
  @Bean
  public LimitCalculator limitCalculator(LimitProperties limitProperties, Clock clock) {
    return new LimitCalculator(limitProperties, clock);
  }
}

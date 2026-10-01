package com.idftech.exchangeservice.infra.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Единственный источник текущего времени во всём приложении.
 *
 * <p>Логика лимитов зависит от «сейчас»: лимит проставляется текущей датой (ТЗ п.5), границы
 * месяца определены часовым поясом, лимит по умолчанию датируется началом месяца. Прямые вызовы
 * {@code Instant.now()} и {@code LocalDate.now()} в коде отсутствуют — вместо них внедряется этот
 * бин, что позволяет в тестах «переводить часы» и воспроизводить сценарии из таблицы ТЗ.
 */
@Configuration
@EnableConfigurationProperties({LimitProperties.class, RateProviderProperties.class, SettlementProperties.class})
public class ClockConfig {

  /**
   * Системные часы в часовом поясе лимитов. В тестах бин заменяется на управляемый
   * {@code AdjustableClock}, чтобы переводить время между шагами сценария.
   */
  @Bean
  public Clock clock(LimitProperties limitProperties) {
    return Clock.system(limitProperties.zoneId());
  }

  /** Часовой пояс приложения: он же используется для определения границ месяца. */
  @Bean
  public ZoneId applicationZoneId(LimitProperties limitProperties) {
    return limitProperties.zoneId();
  }
}

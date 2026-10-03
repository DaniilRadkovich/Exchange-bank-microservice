package com.idftech.exchangeservice.infra.config;

import com.idftech.exchangeservice.application.config.LimitProperties;
import com.idftech.exchangeservice.application.config.RateProviderProperties;
import com.idftech.exchangeservice.application.config.SettlementProperties;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import java.time.Clock;
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
@EnableConfigurationProperties({
    LimitProperties.class,
    RateProviderProperties.class,
    SettlementProperties.class
})
public class ClockConfig {

  /**
   * Системные часы в часовом поясе лимитов. В тестах бин заменяется на управляемый
   * {@code AdjustableClock}, чтобы переводить время между шагами сценария.
   *
   * <p>Пояс берётся из {@code BudgetPeriod.LIMIT_TIMEZONE}, а не из конфигурации: «сейчас» и
   * границы месяца обязаны считаться в одном поясе, иначе 1-е число местного времени попадёт
   * в чужой месяц.
   */
  @Bean
  public Clock clock() {
    return Clock.system(BudgetPeriod.LIMIT_TIMEZONE);
  }
}

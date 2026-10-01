package com.idftech.exchangeservice;

import org.junit.jupiter.api.Test;

/**
 * Проверка того, что контекст приложения поднимается целиком: схема создаётся Liquibase, Hibernate в
 * режиме {@code validate} подтверждает её соответствие сущностям, все бины собираются.
 *
 * <p>Наследует интеграционную среду, потому что без настоящего PostgreSQL контекст не поднимется:
 * подменять БД заглушкой здесь нельзя — тест проверяет именно связность схемы и JPA.
 */
class ExchangeserviceApplicationTests extends AbstractIntegrationTest {

  @Test
  void contextLoads() {
    // Контекст собран: сам факт успешного запуска — проверка.
  }

  @Test
  void schemaMatchesJpaEntities() {
    // ddl-auto=validate падает при старте, если схема разошлась с сущностями.
    // Дополнительно убеждаемся, что Liquibase действительно применил изменения.
    Integer tables =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public'", Integer.class);
    org.assertj.core.api.Assertions.assertThat(tables).isGreaterThanOrEqualTo(4);
  }
}

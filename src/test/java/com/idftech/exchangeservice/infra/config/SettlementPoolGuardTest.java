package com.idftech.exchangeservice.infra.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Граница параллелизма расчёта против размера пула соединений.
 *
 * <p>Ошибка в этой настройке не проявляется на функциональных тестах: расчёт остаётся верным, пока
 * хватает соединений. Проявляется она как остановка приёма и чтения при полностью загруженном
 * расчёте, поэтому проверять её надлежит отдельно и без контейнера.
 */
class SettlementPoolGuardTest {

  @Test
  @DisplayName("Конфигурация по умолчанию проходит проверку")
  void defaultsFitThePool() {
    assertThatCode(() -> SettlementPoolGuard.check(20, 16)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Ровно необходимый запас под приём и чтение проходит проверку")
  void exactReserveFits() {
    assertThatCode(
            () -> SettlementPoolGuard.check(SettlementPoolGuard.RESERVED_CONNECTIONS + 8, 8))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Параллелизм, съедающий пул, останавливает старт с понятным сообщением")
  void parallelismThatWouldExhaustPoolIsRejected() {
    assertThatThrownBy(() -> SettlementPoolGuard.check(20, 17))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("exchange.settlement.parallelism=17")
        .hasMessageContaining("maximum-pool-size");
  }

  @Test
  @DisplayName("Параллелизм больше размера пула отвергается")
  void parallelismAbovePoolSizeIsRejected() {
    assertThatThrownBy(() -> SettlementPoolGuard.check(10, 32))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("DB_POOL_SIZE");
  }

  @Test
  @DisplayName("Пул неизвестной реализации не оценивается: проверка пропускается")
  void unknownPoolImplementationIsSkipped() {
    assertThatCode(
            () ->
                SettlementPoolGuard.checkAgainst(
                    new org.springframework.jdbc.datasource.SimpleDriverDataSource(), 16))
        .doesNotThrowAnyException();
  }
}
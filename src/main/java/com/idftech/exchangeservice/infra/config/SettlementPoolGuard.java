package com.idftech.exchangeservice.infra.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/**
 * Проверка на старте: пул расчёта не должен выедать пул соединений PostgreSQL.
 *
 * <p>Задача дорасчёта удерживает соединение всё время своей работы, а под блокировкой периода
 * ({@code SELECT ... FOR UPDATE}) она в основном и ждёт. Ждать приходится недолго по времени, но
 * соединение это всё равно занято. При {@code exchange.settlement.parallelism} равном размеру пула
 * или большем него весь пул уходит в ожидание блокировки, и всё остальное — приём операций, чтение
 * лимитов, планировщик — начинает ждать свободного соединения, пока не истечёт
 * {@code connection-timeout}. Признак такой неисправности снаружи выглядит неверно: страдает не
 * расчёт, а обычные запросы клиента, поэтому деградацию замечают по {@code 500} на {@code POST}
 * без связи с нагрузкой на расчёт.
 *
 * <p>Отсюда требование: пул обязан иметь запас сверх границы параллелизма. Запас резервируется под
 * приём и чтение ({@link #RESERVED_CONNECTIONS}), а сама граница задаётся
 * {@code exchange.settlement.parallelism}. Проверка выполняется при создании бина пула, то есть до
 * того, как сервис примет первую операцию: конфигурация, при которой расчёт способен заблокировать
 * приём, должна останавливать старт, а не работу.
 */
final class SettlementPoolGuard {

  /**
   * Сколько соединений пула остаётся под запросы клиента, пока расчёт занимает остальные.
   *
   * <p>Число небольшое и осознанное: приём операции, чтение лимитов и превышений и сам планировщик
   * должны работать всегда, даже когда расчёт полностью загружен. Растить его без нужды незачем —
   * соединения простаивают, а расчёт всё равно упирается в блокировку периода.
   */
  static final int RESERVED_CONNECTIONS = 4;

  private SettlementPoolGuard() {}

  /**
   * Проверяет настройку по фактическому пулу соединений.
   *
   * <p>Берётся не значение из YAML, а размер пула у живого {@code DataSource}: он учитывает и
   * переменную окружения, и всё, что настроено поверх конфигурации. Если пул не Hikari, проверка
   * пропускается — неизвестный пул нельзя оценивать, а выдуманное ограничение было бы ложной
   * тревогой.
   */
  static void checkAgainst(DataSource dataSource, int parallelism) {
    if (dataSource instanceof HikariDataSource hikari) {
      check(hikari.getMaximumPoolSize(), parallelism);
    }
  }

  static void check(int maximumPoolSize, int parallelism) {
    if (parallelism + RESERVED_CONNECTIONS <= maximumPoolSize) {
      return;
    }
    throw new IllegalStateException(
        "exchange.settlement.parallelism=%d несовместимо с максимальным размером пула %d: задачи "
                .formatted(parallelism, maximumPoolSize)
            + "расчёта держат соединение, пока ждут блокировку периода, поэтому пул обязан держать "
            + "ещё %d соединения под приём операций и чтение. Уменьшите "
            + "exchange.settlement.parallelism или увеличьте "
            + "spring.datasource.hikari.maximum-pool-size (переменная DB_POOL_SIZE)."
                .formatted(RESERVED_CONNECTIONS));
  }
}
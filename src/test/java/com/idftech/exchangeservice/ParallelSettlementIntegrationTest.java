package com.idftech.exchangeservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.idftech.exchangeservice.application.LimitCalculator;
import com.idftech.exchangeservice.application.LimitCommandService;
import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Параллельный досчёт пачки транзакций (ТЗ п.1*: параллельное выполнение алгоритма для транзакций
 * клиента в различных валютах и параллельное получение курсов на виртуальных потоках).
 *
 * <p>Проверяются три разных свойства, и важно, что они разные:
 *
 * <ul>
 *   <li><b>Параллельность</b> — курсы разных валют запрашиваются одновременно, а не по очереди.
 *       Меряется пиком одновременных обращений к провайдеру, а не временем выполнения: сравнение
 *       времени зависело бы от загрузки машины.
 *   <li><b>Дедупликация</b> — повторы одной пары «валюта + дата» дают один запрос. Внешний API
 *       платный, и пачка почти всегда состоит из повторов.
 *   <li><b>Корректность флагов</b> — параллельность не должна ломать кумулятивный расчёт: операции
 *       одного счёта конкурируют за период и обязаны выстроиться в очередь на блокировке.
 * </ul>
 */
class ParallelSettlementIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000420";
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();
  private static final int HOLD_TIME_MS = 400;
  private static final BigDecimal ONE = new BigDecimal("1.00");
  private static final OffsetDateTime OFFSET_DATE = OffsetDateTime.parse("2022-01-05T12:00:00Z");

  @Autowired
  private TransactionIntakeService intakeService;

  @Autowired
  private LimitCommandService limitCommandService;

  @Autowired
  private TransactionStore transactionStore;

  @Test
  @DisplayName("Курсы разных валют запрашиваются одновременно, а не по очереди")
  void ratesOfDifferentCurrenciesAreFetchedConcurrently() {
    stubSlowRate(Duration.ofMillis(HOLD_TIME_MS));
    String[] currencies = {"EUR", "GBP", "JPY", "CHF", "SEK", "NOK"};
    for (String currency : currencies) {
      acceptPending(currency, new BigDecimal("100.00"));
    }

    assertThat(intakeService.settlePending(100)).isEqualTo(currencies.length);

    // Если бы курсы запрашивались последовательно, пик одновременных обращений остался бы равен 1.
    assertThat(peakConcurrentRateRequests()).as("пик одновременных запросов к провайдеру")
        .isGreaterThan(1);
    // Каждая валюта запрашивается один раз: 6 операций, 6 запросов.
    assertThat(rateApiCallCount()).isEqualTo(currencies.length);
  }

  @Test
  @DisplayName("Повторы одной пары «валюта + дата» схлопываются в один запрос к провайдеру")
  void repeatedCurrencyAndDateAreFetchedOnce() {
    stubRate("EUR", new BigDecimal("1.10"));
    // Одна и та же валюта и одна и та же дата: различаются только суммы. Именно этот случай и
    // схлопывается — пачка реального клиента состоит из таких повторов.
    for (int index = 0; index < 8; index++) {
      acceptPendingOn("EUR", new BigDecimal("10.00"), OFFSET_DATE);
    }

    assertThat(intakeService.settlePending(100)).isEqualTo(8);

    assertThat(rateApiCallCount()).as("запросов к платному провайдеру").isEqualTo(1);
    assertThat(resolvedCount()).isEqualTo(8);
  }

  @Test
  @DisplayName("Разные даты — это разные запросы: дедупликация не схлопывает несмежные операции")
  void differentDatesAreFetchedSeparately() {
    stubRate("EUR", new BigDecimal("1.10"));
    acceptPendingOn("EUR", new BigDecimal("10.00"), OffsetDateTime.parse("2022-01-05T12:00:00Z"));
    acceptPendingOn("EUR", new BigDecimal("10.00"), OffsetDateTime.parse("2022-01-06T12:00:00Z"));

    assertThat(intakeService.settlePending(100)).isEqualTo(2);

    // Курс за каждую дату нужен отдельно: дедупликация по валюте была бы ошибкой и вернула бы
    // вчерашний курс на сегодняшнюю операцию.
    assertThat(rateApiCallCount()).isEqualTo(2);
    assertThat(resolvedCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("Параллельный расчёт не теряет расходы и считает флаги кумулятивно")
  void parallelSettlementKeepsCumulativeFlagsConsistent() {
    testClock.set(OffsetDateTime.parse("2022-01-01T10:00:00Z"));
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("1000.00"));

    // Курс 1.00, чтобы сумма в USD совпадала с суммой операции и ожидаемая арифметика была
    // читаемой: 6 операций по 250 USD = 1500 USD при лимите 1000 USD.
    stubRate("EUR", ONE);
    stubRate("GBP", ONE);
    // EUR и GBP в одном месяце одного счёта: их расчёты конкурируют за один период и должны
    // выстроиться в очередь на блокировке, иначе накопленная сумма разъехалась бы.
    for (int index = 0; index < 6; index++) {
      acceptPending(index % 2 == 0 ? "EUR" : "GBP", new BigDecimal("250.00"));
    }

    assertThat(intakeService.settlePending(100)).isEqualTo(6);

    assertThat(resolvedCount()).isEqualTo(6);
    // Порог превышен начиная с пятой операции в хронологическом порядке: 250, 500, 750, 1000 — ещё
    // нет (остаток ровно 0 превышением не считается), 1250 и 1500 — уже да.
    assertThat(flagsMatchRunningTotal())
        .as("флаги совпадают с накопленной суммой по порядку")
        .isTrue();
    assertThat(exceededCount()).isEqualTo(2);
    assertThat(totalUsd()).isEqualByComparingTo("1500.00");
  }

  @Test
  @DisplayName("Отказ провайдера по одной валюте не мешает расчёту остальных")
  void failedRateForOneCurrencyDoesNotBlockOthers() {
    stubRate("EUR", new BigDecimal("2.00"));
    stubRateProviderFailureForSymbol("GBP");
    acceptPending("EUR", new BigDecimal("100.00"));
    acceptPending("GBP", new BigDecimal("100.00"));
    acceptPending("EUR", new BigDecimal("100.00"));

    assertThat(intakeService.settlePending(100)).isEqualTo(3);

    // Валюты независимы: недоступность GBP не должна уводить в PENDING операции в EUR.
    assertThat(pendingCountFor("EUR")).isZero();
    assertThat(pendingCountFor("GBP")).isEqualTo(1);
    assertThat(resolvedCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("Пустая пачка не обращается к провайдеру и не падает")
  void emptyBatchIsNoOp() {
    assertThat(intakeService.settlePending(100)).isZero();
    assertThat(rateApiCallCount()).isZero();
  }

  /** Принимает транзакцию в PENDING; расчёт отложен до {@code settlePending}. */
  private void acceptPending(String currency, BigDecimal amount) {
    // Дата операции двигается по дням месяца, чтобы транзакции различались моментом совершения:
    // порядок флагов по ТЗ зависит именно от него.
    UUID id = nextId();
    acceptPendingOn(
        currency,
        amount,
        OffsetDateTime.parse(
            "2022-01-%02dT12:00:00Z".formatted(2 + (int) (id.getLeastSignificantBits() % 20))),
        id);
  }

  private void acceptPendingOn(String currency, BigDecimal amount, OffsetDateTime occurredAt) {
    acceptPendingOn(currency, amount, occurredAt, nextId());
  }

  private void acceptPendingOn(
      String currency, BigDecimal amount, OffsetDateTime occurredAt, UUID id) {
    intakeService.accept(
        id, ACCOUNT, "0000009999", currency, amount, ExpenseCategory.PRODUCT, occurredAt);
  }

  private BigDecimal totalUsd() {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(amount_usd), 0) FROM expense_transaction", BigDecimal.class);
  }

  private int resolvedCount() {
    return countByStatus(TransactionStatus.RATE_RESOLVED);
  }

  private int pendingCountFor(String currency) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM expense_transaction WHERE currency_code = ? AND status = ?",
        Integer.class,
        currency,
        TransactionStatus.PENDING.name());
  }

  private int countByStatus(TransactionStatus status) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM expense_transaction WHERE status = ?", Integer.class, status.name());
  }

  private int exceededCount() {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM expense_transaction WHERE limit_exceeded IS TRUE", Integer.class);
  }

  /**
   * Пересчитывает флаги «с нуля» по упорядоченным операциям и сравнивает с сохранёнными в БД.
   *
   * @return {@code true}, если каждый сохранённый флаг совпадает с кумулятивным расчётом
   */
  private boolean flagsMatchRunningTotal() {
    List<ExpenseTransaction> transactions =
        transactionStore.findResolvedInPeriod(
            ACCOUNT, ExpenseCategory.PRODUCT, BudgetPeriod.of(YearMonth.of(2022, 1)));

    BigDecimal running = BigDecimal.ZERO;
    for (ExpenseTransaction transaction : LimitCalculator.ordered(transactions)) {
      running = running.add(transaction.amountUsd());
      boolean expected = running.compareTo(new BigDecimal("1000.00")) > 0;
      if (expected != Boolean.TRUE.equals(transaction.limitExceeded())) {
        return false;
      }
    }
    return true;
  }

  /** Отказ провайдера по конкретной валюте: EUR продолжает обслуживаться. */
  private void stubRateProviderFailureForSymbol(String currency) {
    rateApi()
        .stubFor(
            com.github.tomakehurst.wiremock.client.WireMock.get(
                    com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/time_series"))
                .withQueryParam(
                    "symbol",
                    com.github.tomakehurst.wiremock.client.WireMock.equalTo(currency + "/USD"))
                .willReturn(
                    com.github.tomakehurst.wiremock.client.WireMock.serverError()));
  }

  /** Последовательные идентификаторы: при равном времени порядок определяется сравнением id. */
  private static UUID nextId() {
    return new UUID(0, ID_SEQUENCE.incrementAndGet());
  }
}

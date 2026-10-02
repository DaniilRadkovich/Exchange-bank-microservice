package com.idftech.exchangeservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.idftech.exchangeservice.application.ExchangeRateService;
import com.idftech.exchangeservice.application.LimitCommandService;
import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.application.port.RateCache;
import com.idftech.exchangeservice.domain.ExchangeRate;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * Работа с биржевыми курсами: собственный кэш, перевод в USD и отказоустойчивость (ТЗ п.3).
 *
 * <p>Внешний API подменён WireMock. Проверяется не сам HTTP-клиент, а поведение сервиса: кэширование,
 * применение {@code previous_close}, когда торгов на дату операции не было, и сохранение транзакции в
 * статусе {@code PENDING}, когда курс получить не удалось.
 */
class ExchangeRateIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000123";
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();

  @Autowired
  private TransactionIntakeService intakeService;

  @Autowired
  private LimitCommandService limitCommandService;

  @Autowired
  private ExchangeRateService exchangeRateService;

  @Autowired
  private RateCache rateCache;

  /**
   * Брать число из конфигурации, а не писать константой: тест обязан ломаться, если
   * {@code exchange.settlement.max-attempts} перестанет влиять на переход в {@code FAILED}.
   */
  @Value("${exchange.settlement.max-attempts}")
  private int maxAttempts;

  @Test
  @DisplayName("Транзакция в USD считается с курсом 1 без обращения к внешнему API")
  void usdTransactionNeedsNoExternalCall() {
    stubRate("USD", DEFAULT_CLOSE_RATE);

    ExpenseTransaction settled =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "USD", new BigDecimal("250.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10")));

    assertThat(settled.usdRate()).isEqualByComparingTo("1.0000");
    assertThat(settled.amountUsd()).isEqualByComparingTo("250.00");
    assertThat(rateApiCallCount()).isZero();
  }

  @Test
  @DisplayName("Курс валюты переводит сумму в USD")
  void foreignCurrencyIsConverted() {
    // 1 KZT = 0.0025 USD
    stubRate("KZT", new BigDecimal("0.0025"));

    ExpenseTransaction settled =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10")));

    assertThat(settled.usdRate()).isEqualByComparingTo("0.0025");
    assertThat(settled.amountUsd()).isEqualByComparingTo("25.00");
  }

  @Test
  @DisplayName("При отсутствии торгов на дату операции применяется previous_close (выходной)")
  void previousCloseIsUsedWhenThereWasNoTrading() {
    // Провайдер отдаёт только предыдущее закрытие: на целевую дату торгов не было.
    stubRateWithoutTradingOnTargetDate("KZT", new BigDecimal("0.0020"));

    ExpenseTransaction settled =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-08")));

    assertThat(settled.usdRate()).isEqualByComparingTo("0.0020");
    assertThat(settled.amountUsd()).isEqualByComparingTo("20.00");
  }

  @Test
  @DisplayName("Курс сохраняется в собственной БД и повторно не запрашивается у внешнего API")
  void rateIsCachedInOwnDatabase() {
    stubRate("KZT", new BigDecimal("0.0025"));

    LocalDate date = LocalDate.of(2022, 1, 10);
    assertThat(exchangeRateService.resolveUsdRate("KZT", date)).isPresent();
    int callsAfterFirst = rateApiCallCount();
    assertThat(callsAfterFirst).isEqualTo(1);

    // Второй и третий запросы той же пары и даты обслуживаются из БД.
    assertThat(exchangeRateService.resolveUsdRate("KZT", date)).isPresent();
    assertThat(exchangeRateService.resolveUsdRate("KZT", date)).isPresent();

    assertThat(rateApiCallCount()).isEqualTo(callsAfterFirst);
    Integer cached =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM exchange_rate WHERE base_currency = 'KZT'", Integer.class);
    assertThat(cached).isEqualTo(1);
  }

  @Test
  @DisplayName("Курс хранится с точностью выше четырёх знаков: сумма в USD не искажается")
  void ratePrecisionBeyondFourDecimalsSurvives() {
    // 1 KZT = 0.00251234 USD. При округлении до четырёх знаков курс становится 0.0025, и сумма
    // 10000.00 KZT превращается в 25.00 USD вместо 25.12 — недобор четверти процента.
    stubRate("KZT", new BigDecimal("0.00251234"));

    ExpenseTransaction settled =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10")));

    assertThat(settled.usdRate()).isEqualByComparingTo("0.00251234");
    assertThat(settled.usdRate().scale()).isEqualTo(ExchangeRate.RATE_SCALE);
    assertThat(settled.amountUsd()).isEqualByComparingTo("25.12");
    // Ровно то же значение лежит в БД: иначе расхождение вылезло бы только в отчёте о превышениях.
    assertThat(usdRateInDatabase(settled.id())).isEqualByComparingTo("0.00251234");
  }

  @Test
  @DisplayName("Курс мельче 0.0001 не обнуляется: валюта остаётся расчётной")
  void subFourDecimalRateIsSettledInsteadOfFailing() {
    // 1 VND ≈ 0.0000391 USD. При четырёх знаках такой курс округлялся до нуля, запись кэша
    // отвергалась ограничением close_rate > 0, и транзакция навсегда уходила в FAILED.
    stubRate("VND", new BigDecimal("0.0000391"));

    ExpenseTransaction settled =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "VND", new BigDecimal("1000000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10")));

    assertThat(settled.status()).isEqualTo(TransactionStatus.RATE_RESOLVED);
    assertThat(settled.usdRate()).isEqualByComparingTo("0.0000391");
    assertThat(settled.amountUsd()).isEqualByComparingTo("39.10");
    assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM exchange_rate WHERE base_currency = 'VND'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Непригодная цена провайдера не пишется в кэш и не роняет расчёт")
  void unusableProviderPriceIsNotWrittenToCache() {
    // Биржа отдала нулевые цены: такого курса не существует. Записывать его в кэш нельзя —
    // ограничение close_rate > 0 отвергло бы вставку, и сбой собственной схемы выглядел бы
    // в логе как отказ внешнего API. Правильное поведение — ждать дорасчёта.
    stubRate("VND", BigDecimal.ZERO, BigDecimal.ZERO);

    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "VND", new BigDecimal("1000000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10"));

    assertThat(intakeService.settle(pending.id()).status()).isEqualTo(TransactionStatus.PENDING);
    assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM exchange_rate WHERE base_currency = 'VND'", Integer.class))
        .isZero();
  }

  private BigDecimal usdRateInDatabase(java.util.UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT usd_rate FROM expense_transaction WHERE id = ?", BigDecimal.class, id);
  }

  @Test
  @DisplayName("Недоступность внешнего API не теряет транзакцию: она остаётся PENDING")
  void providerFailureLeavesTransactionPending() {
    stubRateProviderFailure();

    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10"));
    ExpenseTransaction settled = intakeService.settle(pending.id());

    assertThat(settled.status()).isEqualTo(TransactionStatus.PENDING);
    assertThat(settled.amountUsd()).isNull();
    assertThat(settled.limitExceeded()).isNull();

    // Транзакция физически сохранена: её можно дорассчитать позже.
    assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM expense_transaction WHERE id = ?", Integer.class, pending.id()))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("После восстановления API отложенная транзакция дорассчитывается")
  void pendingTransactionIsSettledOnRetry() {
    stubRateProviderFailure();
    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10"));
    assertThat(intakeService.settle(pending.id()).status()).isEqualTo(TransactionStatus.PENDING);

    // Провайдер ожил.
    rateApi().resetAll();
    stubRate("KZT", new BigDecimal("0.0025"));

    ExpenseTransaction settled = intakeService.settle(pending.id());

    assertThat(settled.status()).isEqualTo(TransactionStatus.RATE_RESOLVED);
    assertThat(settled.amountUsd()).isEqualByComparingTo("25.00");
  }

  @Test
  @DisplayName("Ответ без данных не считается превышением и оставляет транзакцию PENDING")
  void emptyProviderResponseLeavesTransactionPending() {
    stubRateEmpty();

    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10"));
    ExpenseTransaction settled = intakeService.settle(pending.id());

    assertThat(settled.status()).isEqualTo(TransactionStatus.PENDING);
  }

  @Test
  @DisplayName("Флаг рассчитывается по сумме в USD, а не по сумме в валюте операции")
  void limitFlagUsesUsdAmountNotOriginalAmount() {
    // 1000 KZT = 2.50 USD: по исходной сумме лимит был бы превышен, по USD — нет.
    stubRate("KZT", new BigDecimal("0.0025"));
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("100.00"));

    ExpenseTransaction settled =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("1000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10")));

    assertThat(settled.amountUsd()).isEqualByComparingTo("2.50");
    assertThat(settled.limitExceeded()).isFalse();
  }

  @Test
  @DisplayName("Неизвестный провайдер не роняет расчёт: транзакция ждёт дорассчёта")
  void unknownCurrencyLeavesTransactionPending() {
    stubRateEmpty();

    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "XXX", new BigDecimal("100.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10"));

    assertThat(intakeService.settle(pending.id()).status()).isEqualTo(TransactionStatus.PENDING);
  }

  @Test
  @DisplayName("Исчерпание max-attempts переводит транзакцию в FAILED и убирает её из дорасчёта")
  void transactionIsMarkedFailedAfterMaxAttempts() {
    stubRateProviderFailure();
    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10"));

    // Все попытки, кроме последней, оставляют транзакцию в PENDING: до лимита это штатное ожидание.
    for (int attempt = 1; attempt < maxAttempts; attempt++) {
      intakeService.settle(pending.id());
      assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.PENDING.name());
    }
    intakeService.settle(pending.id());

    assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.FAILED.name());
    assertThat(attemptsInDatabase(pending.id())).isEqualTo(maxAttempts);
    // Провайдер по-прежнему недоступен, но планировщик больше её не берёт: иначе бесконечный
    // ретрай каждые retry-delay секунд никогда бы не остановился.
    assertThat(intakeService.findPending(100)).isEmpty();
  }

  @Test
  @DisplayName("FAILED не означает потерю данных: транзакция досчитывается вручную")
  void failedTransactionIsSettledManually() {
    stubRateProviderFailure();
    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10"));
    for (int attempt = 0; attempt < maxAttempts; attempt++) {
      intakeService.settle(pending.id());
    }
    assertThat(statusInDatabase(pending.id())).isEqualTo(TransactionStatus.FAILED.name());

    rateApi().resetAll();
    stubRate("KZT", new BigDecimal("0.0025"));

    ExpenseTransaction settled = intakeService.settle(pending.id());

    assertThat(settled.status()).isEqualTo(TransactionStatus.RATE_RESOLVED);
    assertThat(settled.amountUsd()).isEqualByComparingTo("25.00");
  }

  private String statusInDatabase(UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM expense_transaction WHERE id = ?", String.class, id);
  }

  private int attemptsInDatabase(UUID id) {
    return jdbcTemplate.queryForObject(
        "SELECT settlement_attempts FROM expense_transaction WHERE id = ?", Integer.class, id);
  }

  @Test
  @DisplayName("Резервный курс не старше max-fallback-age подставляется и попадает в расчёт")
  void recentFallbackRateIsUsed() {
    // За выходные точной записи за дату операции нет, но свежий курс есть — это нормальная
    // ситуация, а не ошибка.
    rateCache.save(rate("2022-01-05", null, new BigDecimal("0.0025")));
    stubRateEmpty();

    ExpenseTransaction settled =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10")));

    assertThat(settled.status()).isEqualTo(TransactionStatus.RATE_RESOLVED);
    assertThat(settled.amountUsd()).isEqualByComparingTo("25.00");
  }

  @Test
  @DisplayName("Резервный курс старше max-fallback-age не подставляется: транзакция ждёт дорасчёта")
  void staleFallbackRateIsNotUsed() {
    // Полгода старше недельного предела. Подставить такой курс молча нельзя: сумма в USD получилась
    // бы правдоподобной и денежно неверной, и заметить это можно только вручную.
    rateCache.save(rate("2021-07-01", null, new BigDecimal("0.0025")));
    stubRateEmpty();

    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "KZT", new BigDecimal("10000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10"));

    assertThat(settle(pending).status()).isEqualTo(TransactionStatus.PENDING);
  }

  @Test
  @DisplayName("Запись кэша, сохранённая без close, позже дополняется настоящим close")
  void incompleteCacheEntryIsCompletedByLaterUpsert() {
    rateCache.save(rate("2022-01-10", null, new BigDecimal("0.0025")));
    rateCache.save(rate("2022-01-10", new BigDecimal("0.0030"), new BigDecimal("0.0025")));

    // Прежняя реализация просто возвращалась, если запись уже есть, и close оставался пустым
    // навсегда: кэш залипал на previous_close даже после появления настоящей цены закрытия.
    // Сравнение числовое, а не через equals: значение приходит из NUMERIC(19, 10) и несёт
    // десять знаков после точки, тогда как записано было четыре.
    assertThat(rateCache.findRate("KZT", LocalDate.parse("2022-01-10")))
        .hasValueSatisfying(rate -> assertThat(rate).isEqualByComparingTo("0.0030"));
  }

  @Test
  @DisplayName("Параллельная запись одного и того же курса не приводит к нарушению уникального ключа")
  void concurrentCacheWritesOfSamePairSucceed() throws Exception {
    int threads = 8;
    var pool = Executors.newFixedThreadPool(threads);
    try {
      var start = new CountDownLatch(1);
      List<Future<?>> writes = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        writes.add(
            pool.submit(
                () -> {
                  start.await();
                  // Каждый поток приносит свой id: при exists+save гонка выдала бы нарушение
                  // uc_exchange_rate_pair_date сразу двум потокам.
                  rateCache.save(rate("2022-01-10", new BigDecimal("0.0025"), new BigDecimal("0.0024")));
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> write : writes) {
        write.get(30, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM exchange_rate WHERE base_currency = 'KZT'", Integer.class)).isEqualTo(1);
  }

  private ExchangeRate rate(String isoDate, BigDecimal close, BigDecimal previousClose) {
    return new ExchangeRate(
        UUID.randomUUID(),
        Currency.getInstance("KZT"),
        Currency.getInstance("USD"),
        LocalDate.parse(isoDate),
        close,
        previousClose);
  }

  private ExpenseTransaction settle(ExpenseTransaction pending) {
    return intakeService.settle(pending.id());
  }

  private static UUID nextId() {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ID_SEQUENCE.incrementAndGet()));
  }
}

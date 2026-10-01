package com.idftech.exchangeservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.idftech.exchangeservice.application.ExchangeRateService;
import com.idftech.exchangeservice.application.LimitCommandService;
import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

  private ExpenseTransaction settle(ExpenseTransaction pending) {
    return intakeService.settle(pending.id());
  }

  private static UUID nextId() {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ID_SEQUENCE.incrementAndGet()));
  }
}
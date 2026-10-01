package com.idftech.exchangeservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.idftech.exchangeservice.application.LimitCommandService;
import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Сценарии из таблицы ТЗ п.4, выполненные на настоящей БД.
 *
 * <p>Каждый тест повторяет одну строку таблицы целиком: устанавливает лимит, отправляет транзакции в
 * указанные даты и сверяет полученный {@code limit_exceeded} с ожидаемым. Транзакции в USD, поэтому
 * внешний API курсов не участвует и результат не зависит от заглушек — проверяется именно расчёт
 * лимитов, а не интеграция с провайдером.
 */
class LimitScenarioIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000123";
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();

  @Autowired
  private TransactionIntakeService intakeService;

  @Autowired
  private LimitCommandService limitCommandService;

  @Test
  @DisplayName("Сценарий 1, строки 1–2: 500 USD не превышают лимит, 600 USD превышают")
  void januaryTransactionsAgainstThousandLimit() {
    setLimitAt("2022-01-01", "1000.00");

    assertThat(flagOf(send("2022-01-02", "500.00"))).isFalse();
    assertThat(flagOf(send("2022-01-03", "600.00"))).isTrue();
  }

  @Test
  @DisplayName("Сценарий 1, строки 3–5: после лимита 2000 USD от 10.01 превышения нет")
  void januaryTransactionsAfterRaisedLimit() {
    setLimitAt("2022-01-01", "1000.00");
    send("2022-01-02", "500.00");
    send("2022-01-03", "600.00");

    setLimitAt("2022-01-10", "2000.00");

    assertThat(flagOf(send("2022-01-11", "100.00"))).isFalse();
    assertThat(flagOf(send("2022-01-12", "700.00"))).isFalse();
    assertThat(flagOf(send("2022-01-13", "100.00"))).isFalse();
  }

  @Test
  @DisplayName("Сценарий 1, строка 6: накопленная сумма 2100 превышает лимит 2000")
  void januaryFinalTransactionExceedsRaisedLimit() {
    setLimitAt("2022-01-01", "1000.00");
    settle(send("2022-01-02", "500.00"));
    settle(send("2022-01-03", "600.00"));
    setLimitAt("2022-01-10", "2000.00");
    settle(send("2022-01-11", "100.00"));
    settle(send("2022-01-12", "700.00"));
    settle(send("2022-01-13", "100.00"));

    assertThat(flagOf(send("2022-01-13", "100.00"))).isTrue();
  }

  @Test
  @DisplayName("Сценарий 1 целиком: 02.01 false, 03.01 true, 11.01 false, 12.01 false, 13.01 false, 13.01 true")
  void januaryScenarioInFull() {
    setLimitAt("2022-01-01", "1000.00");
    setLimitAt("2022-01-10", "2000.00");

    List<Boolean> flags =
        List.of(
            flagOf(send("2022-01-02", "500.00")),
            flagOf(send("2022-01-03", "600.00")),
            flagOf(send("2022-01-11", "100.00")),
            flagOf(send("2022-01-12", "700.00")),
            flagOf(send("2022-01-13", "100.00")),
            flagOf(send("2022-01-13", "100.00")));

    assertThat(flags).containsExactly(false, true, false, false, false, true);
  }

  @Test
  @DisplayName("Сценарий 2: после снижения лимита до 400 обе транзакции превышены")
  void februaryTransactionsAgainstLoweredLimit() {
    setLimitAt("2022-02-01", "1000.00");

    assertThat(flagOf(send("2022-02-02", "500.00"))).isFalse();
    assertThat(flagOf(send("2022-02-03", "100.00"))).isFalse();

    setLimitAt("2022-02-10", "400.00");

    assertThat(flagOf(send("2022-02-11", "100.00"))).isTrue();
    assertThat(flagOf(send("2022-02-12", "100.00"))).isTrue();
  }

  @Test
  @DisplayName("Остаток после сценария 1 равен -100 USD: 2000 − 2100")
  void remainingIsNegativeWhenLimitIsExceeded() {
    setLimitAt("2022-01-01", "1000.00");
    setLimitAt("2022-01-10", "2000.00");
    settle(send("2022-01-02", "500.00"));
    settle(send("2022-01-03", "600.00"));
    settle(send("2022-01-11", "100.00"));
    settle(send("2022-01-12", "700.00"));
    settle(send("2022-01-13", "100.00"));
    settle(send("2022-01-13", "100.00"));

    BigDecimal spent =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount_usd), 0) FROM expense_transaction WHERE account_from = ?",
            BigDecimal.class,
            ACCOUNT);

    assertThat(spent).isEqualByComparingTo("2100.00");
  }

  @Test
  @DisplayName("Без установленного лимита применяется лимит 1000 USD от начала месяца")
  void defaultLimitAppliesWhenClientNeverSetOne() {
    ExpenseTransaction transaction = settle(send("2022-01-15", "1200.00"));

    assertThat(transaction.limitExceeded()).isTrue();
    assertThat(transaction.amountUsd()).isEqualByComparingTo("1200.00");
  }

  @Test
  @DisplayName("Расходы соседнего месяца не влияют на флаг текущего")
  void monthBoundaryResetsAccumulatedSpend() {
    setLimitAt("2022-01-01", "1000.00");

    assertThat(flagOf(send("2022-01-20", "900.00"))).isFalse();
    assertThat(flagOf(send("2022-02-05", "900.00"))).isFalse();
    // В феврале лимита нет: действует лимит по умолчанию 1000, сумма начинается с нуля.
    assertThat(flagOf(send("2022-02-06", "200.00"))).isTrue();
  }

  @Test
  @DisplayName("Категории считаются независимо: product не расходует лимит service")
  void categoriesHaveSeparateBudgets() {
    setLimitAt("2022-01-01", "1000.00");

    ExpenseTransaction product =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "USD", new BigDecimal("1000.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-10")));
    assertThat(product.limitExceeded()).isFalse();

    ExpenseTransaction service =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "USD", new BigDecimal("1000.00"),
            ExpenseCategory.SERVICE, utc("2022-01-10")));
    assertThat(service.limitExceeded()).isFalse();

    // Превышение в одной категории не переносится на другую.
    ExpenseTransaction secondProduct =
        settle(intakeService.accept(
            nextId(), ACCOUNT, "0000009999", "USD", new BigDecimal("1.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-11")));
    assertThat(secondProduct.limitExceeded()).isTrue();
  }

  @Test
  @DisplayName("Лимит и его история сохраняются в БД как есть, без округления и потери значности")
  void limitsArePersistedExactly() {
    setLimitAt("2022-01-01", "1234.56");

    BigDecimal stored =
        jdbcTemplate.queryForObject(
            "SELECT limit_sum FROM expense_limit WHERE account_from = ?", BigDecimal.class, ACCOUNT);

    assertThat(stored).isEqualByComparingTo("1234.56");
  }

  @Test
  @DisplayName("Категория хранится в БД кодом из API: product, а не PRODUCT")
  void categoryIsStoredInApiForm() {
    setLimitAt("2022-01-01", "1000.00");
    settle(send("2022-01-02", "100.00"));

    String category =
        jdbcTemplate.queryForObject(
            "SELECT expense_category FROM expense_limit WHERE account_from = ?", String.class, ACCOUNT);
    String transactionCategory =
        jdbcTemplate.queryForObject(
            "SELECT expense_category FROM expense_transaction WHERE account_from = ?", String.class, ACCOUNT);

    assertThat(category).isEqualTo("product");
    assertThat(transactionCategory).isEqualTo("product");
  }

  /** Устанавливает лимит, предварительно переведя часы сервиса на нужную дату. */
  private void setLimitAt(String isoDate, String sum) {
    testClock.set(utc(isoDate).plusHours(10));
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal(sum));
  }

  /**
   * Принимает транзакцию в статусе PENDING, как это делает POST /api/v1/transactions.
   *
   * <p>Пока транзакция не рассчитана, у неё нет суммы в USD, а значит она не участвует в накопленном
   * расходе. Поэтому в сценариях подготовку выполняет {@link #settle}, а не голый {@code send}.
   */
  private ExpenseTransaction send(String isoDate, String sum) {
    return intakeService.accept(
        nextId(), ACCOUNT, "0000009999", "USD", new BigDecimal(sum),
        ExpenseCategory.PRODUCT, utc(isoDate));
  }

  /** Принятая транзакция после расчёта. */
  private ExpenseTransaction settle(ExpenseTransaction pending) {
    ExpenseTransaction settled = intakeService.settle(pending.id());
    assertThat(settled).isNotNull();
    assertThat(settled.status()).isEqualTo(TransactionStatus.RATE_RESOLVED);
    return settled;
  }

  private boolean flagOf(ExpenseTransaction pending) {
    return Boolean.TRUE.equals(settle(pending).limitExceeded());
  }

  /**
   * Последовательные идентификаторы.
   *
   * <p>Две транзакции одного дня в таблице ТЗ (13.01) должны давать предсказуемый порядок. При
   * равном времени расчёт опирается на сравнение идентификаторов, поэтому случайные UUID сделали бы
   * тест недетерминированным.
   */
  @Test
  @DisplayName("Поздняя операция с ранней датой пересчитывает флаги уже рассчитанных операций")
  void lateArrivingEarlyTransactionRefreshesLaterFlags() {
    setLimitAt("2022-01-01", "1000.00");

    // Сначала рассчитываем операции с поздними датами: на тот момент их накопленный итог мал,
    // и флаги у них остаются неверными.
    ExpenseTransaction januaryTwelfth = settle(send("2022-01-12", "600.00"));
    ExpenseTransaction januaryThirteenth = settle(send("2022-01-13", "600.00"));
    assertThat(januaryTwelfth.limitExceeded()).isFalse();
    assertThat(januaryThirteenth.limitExceeded()).isTrue();

    // Теперь приходит операция с ранней датой, которая доводит итог каждого периода до превышения.
    settle(send("2022-01-05", "900.00"));

    assertThat(storedFlagOf(januaryTwelfth.id())).isTrue();
    assertThat(storedFlagOf(januaryThirteenth.id())).isTrue();
  }

  @Test
  @DisplayName("Поздняя операция с ранней датой не занижает флаг операции, до неё не дошедшей")
  void lateArrivingEarlyTransactionLeavesEarlierFlagsUnchanged() {
    setLimitAt("2022-01-01", "1000.00");

    ExpenseTransaction januaryThird = settle(send("2022-01-03", "400.00"));
    settle(send("2022-01-06", "900.00"));

    assertThat(storedFlagOf(januaryThird.id())).isFalse();
  }

  private static UUID nextId() {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ID_SEQUENCE.incrementAndGet()));
  }

  /** Сохранённый в БД флаг: нужен, чтобы проверить пересчёт, а не возвращаемое значение расчёта. */
  private boolean storedFlagOf(UUID transactionId) {
    return jdbcTemplate.queryForObject(
        "SELECT limit_exceeded FROM expense_transaction WHERE id = ?",
        Boolean.class,
        transactionId);
  }
}
package com.idftech.exchangeservice.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import com.idftech.exchangeservice.infra.config.LimitProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Юнит-тесты доменной логики выставления флага {@code limit_exceeded}.
 *
 * <p>Проверяются сценарии из таблицы ТЗ (п.4) плюс граничные случаи, требуемые ТЗ п.5: смена лимита
 * внутри месяца, переход на новый месяц и остаток ровно 0.
 *
 * <p>Тест не поднимает Spring и БД: {@link LimitCalculator} — чистая функция над доменными типами,
 * ради чего доменный слой и вынесен отдельно от персистентного.
 */
class LimitCalculatorTest {

  private static final String ACCOUNT = "0000000123";
  private static final ExpenseCategory CATEGORY = ExpenseCategory.PRODUCT;
  private static final Currency USD = Currency.getInstance("USD");

  /**
   * Счётчик идентификаторов тестовых транзакций.
   *
   * <p>Идентификаторы выдаются последовательно, потому что при равном времени совершения порядок
   * двух транзакций в расчёте определяется сравнением идентификаторов. Со случайными UUID тест на
   * две операции в один момент был бы недетерминированным.
   */
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();

  private final Clock clock = Clock.systemUTC();

  private final LimitCalculator calculator =
      new LimitCalculator(new LimitProperties(null, null), clock);

  @Nested
  @DisplayName("Сценарий 1 из таблицы ТЗ")
  class FirstScenarioFromSpec {

    @Test
    @DisplayName("первые транзации не превышают лимит, третья превышает")
    void flagsFollowCumulativeSpend() {
      ExpenseLimit limit = limit("2022-01-01", "1000.00");
      List<ExpenseTransaction> transactions = new ArrayList<>();

      transactions.add(usd("2022-01-02", "500.00"));
      transactions.add(usd("2022-01-03", "600.00"));

      assertThat(flagOf(transactions.get(0), List.of(limit), transactions)).isFalse();
      assertThat(flagOf(transactions.get(1), List.of(limit), transactions)).isTrue();
    }

    @Test
    @DisplayName("новый лимит внутри месяца не влияет на флаг транзакции, случившейся раньше")
    void newLimitDoesNotRetagEarlierTransactions() {
      ExpenseLimit first = limit("2022-01-01", "1000.00");
      ExpenseLimit second = limit("2022-01-10", "2000.00");
      List<ExpenseLimit> limits = List.of(first, second);

      ExpenseTransaction beforeAll = usd("2022-01-02", "500.00");
      ExpenseTransaction exceededBeforeNewLimit = usd("2022-01-03", "600.00");
      ExpenseTransaction afterNewLimit = usd("2022-01-11", "100.00");
      List<ExpenseTransaction> period =
          List.of(beforeAll, exceededBeforeNewLimit, afterNewLimit);

      // Операция от 03.01 превысила лимит 1000 (накоплено 1100) и сохраняет этот флаг,
      // хотя позднее был установлен лимит 2000.
      assertThat(flagOf(exceededBeforeNewLimit, limits, period)).isTrue();
      // Операция от 11.01 считается уже по лимиту 2000: накоплено 1200, превышения нет.
      assertThat(flagOf(afterNewLimit, limits, period)).isFalse();
    }

    @Test
    @DisplayName("полный сценарий таблицы: 02.01 false, 03.01 true, 11.01 false, 12.01 false, 13.01 false, 13.01 true")
    void fullTableScenario() {
      List<ExpenseLimit> limits = List.of(limit("2022-01-01", "1000.00"), limit("2022-01-10", "2000.00"));

      List<ExpenseTransaction> transactions =
        List.of(
            usd("2022-01-02", "500.00"),
            usd("2022-01-03", "600.00"),
            usd("2022-01-11", "100.00"),
            usd("2022-01-12", "700.00"),
            usd("2022-01-13", "100.00"),
            usd("2022-01-13", "100.00"));

      assertThat(flagsOf(transactions, limits))
          .containsExactly(false, true, false, false, false, true);
    }

    @Test
    @DisplayName("остаток ровно 0 не считается превышением")
    void zeroRemainingIsNotExceeded() {
      List<ExpenseLimit> limits = List.of(limit("2022-01-10", "2000.00"));
      ExpenseTransaction transaction = usd("2022-01-13", "2000.00");

      assertThat(flagOf(transaction, limits, List.of(transaction))).isFalse();
    }

    @Test
    @DisplayName("сумма в валюте операции переводится в USD по курсу закрытия")
    void foreignCurrencyIsConvertedBeforeFlagComputation() {
      ExpenseTransaction tenThousandTenge =
        transactionIn("KZT", "10000.45", "2022-01-02", BigDecimal.valueOf(0.0025));

      ExpenseLimit limit = limit("2022-01-01", "50.00");

      assertThat(ExpenseTransaction.toUsd(tenThousandTenge.amount(), BigDecimal.valueOf(0.0025)))
          .isEqualByComparingTo("25.00");
      assertThat(flagOf(tenThousandTenge, List.of(limit), List.of(tenThousandTenge))).isFalse();
    }

    @Test
    @DisplayName("даты лимита и транзакций не зависят от переданного часового пояса")
    void transactionTimezoneDoesNotShiftMonthBoundary() {
      // 2022-01-31T23:30+06:00 — это 2022-01-31T17:30Z, то есть тот же январский период в UTC.
      ExpenseTransaction lateJanuary =
        transactionWithOffset("USD", "500.00", OffsetDateTime.parse("2022-01-31T23:30:00+06:00"));

      assertThat(lateJanuary.period()).isEqualTo(BudgetPeriod.of(YearMonth.of(2022, 1)));

      // 2022-02-01T00:30+06:00 — это 2022-01-31T18:30Z, то есть ещё январь в UTC.
      ExpenseTransaction firstFebruaryInUtc =
        transactionWithOffset("USD", "500.00", OffsetDateTime.parse("2022-02-01T00:30:00+06:00"));

      assertThat(firstFebruaryInUtc.period()).isEqualTo(BudgetPeriod.of(YearMonth.of(2022, 1)));
    }
  }

  @Nested
  @DisplayName("Сценарий 2 из таблицы ТЗ")
  class SecondScenarioFromSpec {

    @Test
    @DisplayName("после снижения лимита превышены обе последующие транзакции")
    void bothTransactionsExceedLoweredLimit() {
      List<ExpenseLimit> limits = List.of(limit("2022-02-01", "1000.00"), limit("2022-02-10", "400.00"));

      List<ExpenseTransaction> transactions =
        List.of(
            usd("2022-02-02", "500.00"),
            usd("2022-02-03", "100.00"),
            usd("2022-02-11", "100.00"),
            usd("2022-02-12", "100.00"));

      assertThat(flagsOf(transactions, limits)).containsExactly(false, false, true, true);
    }
  }

  @Nested
  @DisplayName("Граничные случаи")
  class EdgeCases {

    @Test
    @DisplayName("без установленного лимита применяется лимит 1000 USD с датой в начале месяца")
    void defaultLimitAppliesWhenNoneInstalled() {
      ExpenseTransaction transaction = usd("2022-01-15", "1200.00");
      ExpenseLimit effective = calculator.effectiveLimit(transaction, List.of());

      assertThat(effective.limitSum()).isEqualByComparingTo("1000.00");
      assertThat(effective.limitDatetime()).isEqualTo(OffsetDateTime.parse("2022-01-01T00:00:00Z"));
      assertThat(flagOf(transaction, List.of(), List.of(transaction))).isTrue();
    }

    @Test
    @DisplayName("переход на новый месяц сбрасывает накопленную сумму")
    void newMonthStartsWithCleanBudget() {
      ExpenseLimit januaryLimit = limit("2022-01-01", "1000.00");
      List<ExpenseLimit> januaryLimits = List.of(januaryLimit);

      ExpenseTransaction january = usd("2022-01-20", "900.00");
      ExpenseTransaction february = usd("2022-02-05", "900.00");

      assertThat(flagOf(january, januaryLimits, List.of(january, february))).isFalse();

      // В феврале своего лимита нет — действует лимит по умолчанию, и сумма начинается с нуля.
      assertThat(flagOf(february, List.of(), List.of(january, february))).isFalse();
    }

    @Test
    @DisplayName("порядок при равном времени совпадает с PostgreSQL, а не с UUID.compareTo")
    void tieBreakOrderMatchesPostgresByteOrder() {
      // Два UUID, которые UUID.compareTo упорядочивает наоборот, чем PostgreSQL: старший бит
      // первого полу-слова установлен, поэтому Java считает его отрицательным. Флаг считает
      // приложение, а накопленную сумму отдаёт SQL, и при расхождении порядка клиент увидел бы
      // «превышение» с чужой суммой.
      ExpenseLimit januaryLimit = limit("2022-01-01", "1000.00");

      ExpenseTransaction withHighBit =
          transactionIn("USD", "600.00", "2022-01-10", BigDecimal.ONE,
              UUID.fromString("ffffffff-ffff-4fff-bfff-fffffffffff0"));
      ExpenseTransaction withLowBit =
          transactionIn("USD", "600.00", "2022-01-10", BigDecimal.ONE,
              UUID.fromString("00000000-0000-4000-8000-000000000001"));

      // PostgreSQL считает withHighBit большим, поэтому накопленная сумма второго равна 1200.
      assertThat(withHighBit.toString().compareTo(withLowBit.toString())).isPositive();
      assertThat(withHighBit.id().compareTo(withLowBit.id())).isNegative();

      List<ExpenseTransaction> period = List.of(withLowBit, withHighBit);
      List<Boolean> flags = flagsOf(period, List.of(januaryLimit));

      assertThat(flags).containsExactly(false, true);
      assertThat(calculator.spentUpTo(LimitCalculator.ordered(period), withHighBit))
          .isEqualByComparingTo("1200.00");
    }

    @Test
    @DisplayName("транзакции разных категорий не суммируются")
    void categoriesAreIndependent() {
      ExpenseLimit productLimit = limit("2022-01-01", "1000.00");
      ExpenseTransaction product = usd("2022-01-10", "1000.00");

      ExpenseTransaction service =
        new ExpenseTransaction(
            nextId(),
            ACCOUNT,
            "9999999999",
            USD,
            new BigDecimal("1000.00"),
            ExpenseCategory.SERVICE,
            OffsetDateTime.parse("2022-01-10T00:00:00Z"),
            BigDecimal.ONE,
            new BigDecimal("1000.00"),
            TransactionStatus.RATE_RESOLVED,
            null);

      assertThat(flagOf(product, List.of(productLimit), List.of(product, service))).isFalse();
    }

    @Test
    @DisplayName("транзакции в валюте USD применяются с курсом 1")
    void usdAmountIsUnchanged() {
      ExpenseTransaction transaction = usd("2022-01-10", "250.00");

      assertThat(transaction.usdRate()).isEqualByComparingTo(BigDecimal.ONE);
      assertThat(transaction.amountUsd()).isEqualByComparingTo("250.00");
    }

    @Test
    @DisplayName("лимит, установленный в момент операции, уже действует")
    void limitInstalledExactlyAtTransactionTimeApplies() {
      ExpenseLimit limit = limit("2022-01-10", "500.00");
      ExpenseTransaction sameInstant = usd("2022-01-10", "600.00");

      assertThat(flagOf(sameInstant, List.of(limit), List.of(sameInstant))).isTrue();
    }
  }

  /**
   * Флаг одной транзакции. Кумулятивная сумма считается по всему периоду {@code periodInPeriod},
   * поэтому её нужно передавать целиком — иначе проверка была бы неверной по построению.
   */
  private boolean flagOf(
      ExpenseTransaction transaction,
      List<ExpenseLimit> limits,
      List<ExpenseTransaction> periodTransactions) {
    return calculator.isExceeded(
        transaction, calculator.effectiveLimit(transaction, limits), periodTransactions);
  }

  private List<Boolean> flagsOf(List<ExpenseTransaction> transactions, List<ExpenseLimit> limits) {
    return transactions.stream()
        .map(transaction -> calculator.isExceeded(
            transaction, calculator.effectiveLimit(transaction, limits), transactions))
        .toList();
  }

  private static ExpenseLimit limit(String isoDate, String sum) {
    return ExpenseLimit.create(nextId(), ACCOUNT, CATEGORY, new BigDecimal(sum), january(isoDate));
  }

  /** Следующий идентификатор: {@code 00000000-0000-0000-0000-0000000000NN}. */
  private static UUID nextId() {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ID_SEQUENCE.incrementAndGet()));
  }

  private static ExpenseTransaction transactionIn(
      String currency, String sum, String isoDate, BigDecimal rate, UUID id) {
    return transactionIn(currency, sum, january(isoDate), rate, id);
  }

  private static ExpenseTransaction usd(String isoDate, String sum) {
    return transactionIn("USD", sum, isoDate, BigDecimal.ONE);
  }

  private static ExpenseTransaction transactionIn(String currency, String sum, String isoDate, BigDecimal rate) {
    return transactionIn(currency, sum, january(isoDate), rate);
  }

  private static ExpenseTransaction transactionIn(
      String currency, String sum, OffsetDateTime at, BigDecimal rate) {
    return transactionIn(currency, sum, at, rate, nextId());
  }

  /** Транзакция с заданным идентификатором: нужен для проверки порядка при равном времени. */
  private static ExpenseTransaction transactionIn(
      String currency, String sum, OffsetDateTime at, BigDecimal rate, UUID id) {
    ExpenseTransaction pending = new ExpenseTransaction(
        id,
        ACCOUNT,
        "9999999999",
        Currency.getInstance(currency),
        new BigDecimal(sum),
        CATEGORY,
        at,
        null,
        null,
        TransactionStatus.PENDING,
        null);
    return pending.resolved(rate, false);
  }

  private static ExpenseTransaction transactionWithOffset(String currency, String sum, OffsetDateTime at) {
    return new ExpenseTransaction(
        nextId(),
        ACCOUNT,
        "9999999999",
        Currency.getInstance(currency),
        new BigDecimal(sum),
        CATEGORY,
        at,
        BigDecimal.ONE,
        new BigDecimal(sum),
        TransactionStatus.RATE_RESOLVED,
        false);
  }

  private static OffsetDateTime january(String isoDate) {
    return OffsetDateTime.parse(isoDate + "T00:00:00Z").withOffsetSameInstant(ZoneOffset.UTC);
  }
}

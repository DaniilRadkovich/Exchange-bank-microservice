package com.idftech.exchangeservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.idftech.exchangeservice.application.LimitCommandService;
import com.idftech.exchangeservice.application.LimitQueryService;
import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Клиентский read-модельный API (ТЗ п.6) на настоящем PostgreSQL.
 *
 * <p>Тесты проверяют не только значения, но и сам факт корректности SQL: {@code JOIN} с
 * коррелированным подзапросом на лимит, оконную функцию {@code SUM} и {@code GROUP BY} работают в
 * PostgreSQL иначе, чем в H2, поэтому запрос исполняется против настоящей СУБД.
 */
class ExceededTransactionsQueryIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000123";
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();

  @Autowired
  private TransactionIntakeService intakeService;

  @Autowired
  private LimitCommandService limitCommandService;

  @Autowired
  private LimitQueryService limitQueryService;

  @Autowired
  private com.idftech.exchangeservice.application.port.TransactionStore transactionStore;

  @Test
  @DisplayName("П.6: превышения возвращаются вместе с параметрами лимита и накопленной суммой")
  void exceededTransactionsCarryLimitDetails() {
    setLimitAt("2022-01-01", "1000.00");
    settle(send("2022-01-02", "500.00", ExpenseCategory.PRODUCT));
    settle(send("2022-01-03", "600.00", ExpenseCategory.PRODUCT));

    List<ExceededTransaction> exceeded = limitQueryService.findExceeded(ACCOUNT);

    assertThat(exceeded).hasSize(1);
    ExceededTransaction only = exceeded.getFirst();
    assertThat(only.amountUsd()).isEqualByComparingTo("600.00");
    assertThat(only.limit()).isEqualByComparingTo("1000.00");
    assertThat(only.limitCurrency().getCurrencyCode()).isEqualTo("USD");
    assertThat(only.limitDatetime()).isEqualTo(utc("2022-01-01").plusHours(10));
    // Накопленная сумма включает и непревышенную операцию: 500 + 600, а не только 600.
    assertThat(only.runningTotalUsd()).isEqualByComparingTo("1100.00");
    assertThat(only.exceededBy()).isEqualByComparingTo("100.00");
  }

  @Test
  @DisplayName("П.6: непревышенные транзакции в выборку не попадают")
  void onlyExceededTransactionsAreReturned() {
    setLimitAt("2022-01-01", "5000.00");
    settle(send("2022-01-02", "100.00", ExpenseCategory.PRODUCT));
    settle(send("2022-01-03", "200.00", ExpenseCategory.SERVICE));

    assertThat(limitQueryService.findExceeded(ACCOUNT)).isEmpty();
  }

  @Test
  @DisplayName("П.6: для каждой строки подставляется тот лимит, который действовал на момент операции")
  void eachRowReportsTheLimitEffectiveAtThatMoment() {
    setLimitAt("2022-01-01", "1000.00");
    // Превышает лимит 1000: накоплено 1200.
    settle(send("2022-01-03", "1200.00", ExpenseCategory.PRODUCT));
    setLimitAt("2022-01-10", "3000.00");
    // Не превышает: накоплено 1300 при лимите 3000.
    settle(send("2022-01-11", "100.00", ExpenseCategory.PRODUCT));
    // Превышает лимит 3000: накоплено 4300.
    settle(send("2022-01-12", "3000.00", ExpenseCategory.PRODUCT));

    List<ExceededTransaction> exceeded = limitQueryService.findExceeded(ACCOUNT);

    assertThat(exceeded).hasSize(2);
    assertThat(exceeded.get(0).limit()).isEqualByComparingTo("1000.00");
    assertThat(exceeded.get(0).limitDatetime()).isEqualTo(utc("2022-01-01").plusHours(10));
    assertThat(exceeded.get(0).runningTotalUsd()).isEqualByComparingTo("1200.00");
    assertThat(exceeded.get(1).limit()).isEqualByComparingTo("3000.00");
    assertThat(exceeded.get(1).limitDatetime()).isEqualTo(utc("2022-01-10").plusHours(10));
    assertThat(exceeded.get(1).runningTotalUsd()).isEqualByComparingTo("4300.00");
  }

  @Test
  @DisplayName("П.6: без установленного лимита подставляется лимит 1000 USD от начала месяца")
  void defaultLimitIsSubstitutedByCoalesce() {
    settle(send("2022-01-10", "1500.00", ExpenseCategory.PRODUCT));

    List<ExceededTransaction> exceeded = limitQueryService.findExceeded(ACCOUNT);

    assertThat(exceeded).hasSize(1);
    assertThat(exceeded.getFirst().limit()).isEqualByComparingTo("1000.00");
    assertThat(exceeded.getFirst().limitDatetime()).isEqualTo(utc("2022-01-01"));
  }

  @Test
  @DisplayName("П.6: запрос разделяет категории — превышение product не выдаётся как service")
  void categoriesArePartitioned() {
    setLimitAt("2022-01-01", "1000.00");
    settle(send("2022-01-10", "1200.00", ExpenseCategory.PRODUCT));

    List<ExceededTransaction> exceeded = limitQueryService.findExceeded(ACCOUNT);

    assertThat(exceeded).hasSize(1);
    assertThat(exceeded.getFirst().category()).isEqualTo(ExpenseCategory.PRODUCT);
  }

  @Test
  @DisplayName("П.6: чужие счета не попадают в выборку")
  void otherAccountsAreNotIncluded() {
    setLimitAt("2022-01-01", "1000.00");
    settle(send("2022-01-10", "1200.00", ExpenseCategory.PRODUCT));

    assertThat(limitQueryService.findExceeded("0000009999")).isEmpty();
  }

  @Test
  @DisplayName("П.6: накопленная сумма непрерывна по всем операциям периода, включая непревышенные")
  void runningTotalCountsAllPeriodOperations() {
    setLimitAt("2022-01-01", "2000.00");
    settle(send("2022-01-02", "500.00", ExpenseCategory.PRODUCT));
    settle(send("2022-01-03", "500.00", ExpenseCategory.PRODUCT));
    settle(send("2022-01-04", "1200.00", ExpenseCategory.PRODUCT));
    settle(send("2022-01-05", "100.00", ExpenseCategory.PRODUCT));

    List<ExceededTransaction> exceeded = limitQueryService.findExceeded(ACCOUNT);

    // Первая превышенная — 1200 при накопленных 2200; следующая идёт по непрерывной сумме 2300.
    assertThat(exceeded).hasSize(2);
    assertThat(exceeded.get(0).runningTotalUsd()).isEqualByComparingTo("2200.00");
    assertThat(exceeded.get(1).runningTotalUsd()).isEqualByComparingTo("2300.00");
  }

  @Test
  @DisplayName("П.6: накопленная сумма начинается заново в каждом месяце")
  void runningTotalResetsEachMonth() {
    // Один лимит на весь период, но сумма копится независимо в январе и феврале.
    setLimitAt("2022-01-01", "1000.00");
    settle(send("2022-01-20", "1500.00", ExpenseCategory.PRODUCT));
    settle(send("2022-02-02", "1500.00", ExpenseCategory.PRODUCT));

    List<ExceededTransaction> exceeded = limitQueryService.findExceeded(ACCOUNT);

    // Обе операции превышают лимит 1000, но февральская учитывает только свой месяц:
    // 1500, а не 3000 — иначе итог противоречил бы флагу limit_exceeded, посчитанному приложением.
    assertThat(exceeded).hasSize(2);
    assertThat(exceeded.get(0).runningTotalUsd()).isEqualByComparingTo("1500.00");
    assertThat(exceeded.get(1).runningTotalUsd()).isEqualByComparingTo("1500.00");
  }

  @Test
  @DisplayName("П.6: JOIN + подзапрос + GROUP BY + SUM в запросе лимитов с остатком")
  void limitsWithSpentAmountUseAggregationQuery() {
    setLimitAt("2022-01-01", "1000.00");
    settle(send("2022-01-02", "500.00", ExpenseCategory.PRODUCT));
    settle(send("2022-01-03", "200.00", ExpenseCategory.PRODUCT));

    List<TransactionStore.LimitWithSpent> rows =
        transactionStore.findLimitsWithSpent(ACCOUNT, BudgetPeriod.of(YearMonth.of(2022, 1)));

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().spentUsd()).isEqualByComparingTo("700.00");
    assertThat(rows.getFirst().remainingUsd()).isEqualByComparingTo("300.00");
  }

  @Test
  @DisplayName("П.6: список лимитов отдаёт расход периода и остаток, а не пустые поля")
  void limitsWithSpentCoverEveryLimitOfTheAccount() {
    setLimitAt("2022-01-01", "1000.00");
    setLimitAt("2022-01-10", "2000.00");
    settle(send("2022-01-11", "500.00", ExpenseCategory.PRODUCT));

    // Оба лимита возвращаются, новые первыми, и у каждого заполнены spent/remaining:
    // расход периода один и тот же — 500, остаток считается от суммы своей записи.
    List<TransactionStore.LimitWithSpent> rows =
        transactionStore.findLimitsWithSpent(ACCOUNT, BudgetPeriod.of(YearMonth.of(2022, 1)));

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).limitSum()).isEqualByComparingTo("2000.00");
    assertThat(rows.get(0).spentUsd()).isEqualByComparingTo("500.00");
    assertThat(rows.get(0).remainingUsd()).isEqualByComparingTo("1500.00");
    assertThat(rows.get(1).limitSum()).isEqualByComparingTo("1000.00");
    assertThat(rows.get(1).spentUsd()).isEqualByComparingTo("500.00");
    assertThat(rows.get(1).remainingUsd()).isEqualByComparingTo("500.00");
  }

  @Test
  @DisplayName("П.6: лимит прошлого месяца не подставляется к операции текущего месяца")
  void januaryLimitIsNotAppliedToFebruaryTransaction() {
    // Лимит января намеренно больше лимита по умолчанию. Если бы поиск лимита в SQL не был
    // ограничен календарным месяцем операции, февральская операция получила бы в ответе январский
    // лимит 5000, хотя её флаг посчитан против лимита по умолчанию 1000.
    setLimitAt("2022-01-01", "5000.00");
    settle(send("2022-01-31", "600.00", ExpenseCategory.PRODUCT));
    settle(send("2022-02-01", "1100.00", ExpenseCategory.PRODUCT));

    List<ExceededTransaction> exceeded = limitQueryService.findExceeded(ACCOUNT);

    assertThat(exceeded).hasSize(1);
    ExceededTransaction february = exceeded.getFirst();
    assertThat(february.occurredAt()).isEqualTo(utc("2022-02-01"));
    assertThat(february.limit()).isEqualByComparingTo("1000.00");
    assertThat(february.limitDatetime()).isEqualTo(utc("2022-02-01"));
    assertThat(february.runningTotalUsd()).isEqualByComparingTo("1100.00");
  }

  @Test
  @DisplayName("П.6: лимиты возвращаются новыми первыми")
  void limitsAreReturnedNewestFirst() {
    setLimitAt("2022-01-01", "1000.00");
    setLimitAt("2022-01-10", "2000.00");

    assertThat(limitQueryService.findAllLimits(ACCOUNT))
        .extracting(limit -> limit.limitSum())
        .containsExactly(new BigDecimal("2000.00"), new BigDecimal("1000.00"));
  }

  private void setLimitAt(String isoDate, String sum) {
    testClock.set(utc(isoDate).plusHours(10));
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal(sum));
  }

  private ExpenseTransaction send(String isoDate, String sum, ExpenseCategory category) {
    return intakeService.accept(
        nextId(), ACCOUNT, "0000009999", "USD", new BigDecimal(sum), category, utc(isoDate));
  }

  private ExpenseTransaction settle(ExpenseTransaction pending) {
    return intakeService.settle(pending.id());
  }

  private static UUID nextId() {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ID_SEQUENCE.incrementAndGet()));
  }
}
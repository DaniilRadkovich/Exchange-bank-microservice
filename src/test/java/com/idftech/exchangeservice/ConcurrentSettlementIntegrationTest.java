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
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Поведение под конкурентной нагрузкой (ТЗ п.2: расчёт месячных лимитов).
 *
 * <p>Смысл проверки: наивная реализация читает накопленную сумму, сравнивает с лимитом и пишет
 * флаг. Два параллельных потока делают одно и то же чтение до записи, поэтому второй поток считает
 * сумму по устаревшим данным и итоговые расходы расходятся с фактическими. Защита — блокировка
 * периода {@code SELECT ... FOR UPDATE} на строке-замке, которая сериализует расчёт внутри одного
 * месяца.
 *
 * <p>Тест выполняет расчёт в отдельных потоках и в отдельных транзакциях Spring: если блокировка
 * не работает, накопленная сумма и число превышений окажутся неверными, и проверка это поймает.
 */
class ConcurrentSettlementIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000123";
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();
  private static final int THREADS = 6;
  private static final int OPERATIONS_PER_THREAD = 5;
  private static final BigDecimal OPERATION_USD = new BigDecimal("100.00");

  @Autowired
  private TransactionIntakeService intakeService;

  @Autowired
  private LimitCommandService limitCommandService;

  @Autowired
  private TransactionStore transactionStore;

  @Test
  @DisplayName("Параллельный расчёт не теряет расходы: итог равен сумме всех операций")
  void concurrentSettlementLosesNoExpenses() throws Exception {
    setLimitAt("2022-01-01", "1000.00");

    List<UUID> pendingIds = new ArrayList<>();
    for (int index = 0; index < THREADS * OPERATIONS_PER_THREAD; index++) {
      pendingIds.add(
          intakeService
              .accept(
                  nextId(),
                  ACCOUNT,
                  "0000009999",
                  "USD",
                  OPERATION_USD,
                  ExpenseCategory.PRODUCT,
                  OffsetDateTime.parse("2022-01-%02dT12:00:00Z".formatted(2 + index % 25)))
              .id());
    }

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    try {
      List<Callable<ExpenseTransaction>> tasks =
          pendingIds.stream()
              .map(id -> (Callable<ExpenseTransaction>) () -> intakeService.settle(id))
              .toList();
      for (Future<ExpenseTransaction> result : pool.invokeAll(tasks)) {
        assertThat(result.get()).isNotNull();
      }
    } finally {
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }

    // 30 операций по 100 USD = 3000 USD при лимите 1000.
    Integer resolvedCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM expense_transaction WHERE account_from = ? AND status = ?",
            Integer.class,
            ACCOUNT,
            TransactionStatus.RATE_RESOLVED.name());
    assertThat(resolvedCount).isEqualTo(THREADS * OPERATIONS_PER_THREAD);

    BigDecimal total =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(amount_usd), 0) FROM expense_transaction"
                + " WHERE account_from = ? AND status = 'RATE_RESOLVED'",
            BigDecimal.class,
            ACCOUNT);
    assertThat(total).isEqualByComparingTo("3000.00");

    // Кумулятивный флаг обязан совпадать с накопленной суммой по каждой операции, иначе
    // последовательность флагов не будет монотонной.
    assertThat(flagsAreConsistentWithRunningTotal()).isTrue();
  }

  /**
   * Пересчитывает флаги «с нуля» по упорядоченным операциям и сравнивает с сохранёнными в БД.
   *
   * @return {@code true}, если каждый сохранённый флаг совпадает с кумулятивным расчётом
   */
  private boolean flagsAreConsistentWithRunningTotal() {
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

  private void setLimitAt(String isoDate, String sum) {
    testClock.set(OffsetDateTime.parse(isoDate + "T00:00:00Z").plusHours(10));
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal(sum));
  }

  private static UUID nextId() {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ID_SEQUENCE.incrementAndGet()));
  }
}
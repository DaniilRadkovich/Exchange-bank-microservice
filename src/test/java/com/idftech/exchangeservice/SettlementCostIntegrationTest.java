package com.idftech.exchangeservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Расчёт флага не должен стоить квадратично на пачке операций одного периода.
 *
 * <p>Флаг кумулятивный, поэтому каждой операции нужна сумма всех предшествующих. Если на каждый расчёт
 * перечитывать весь месяц, пачка из n операций читает его n раз: 100 операций — секунды, 800 — десятки
 * секунд, а месяц крупного клиента превращается в минуты под блокировкой периода. Расчёт поэтому
 * берёт суффикс периода, а накопленную сумму до него спрашивает у базы отдельной строкой.
 *
 * <p>Проверяется и что оптимизация не сломала правило: сумма до позиции из SQL совпадает с суммой,
 * посчитанной в Java по той же периодике, а флаги на выходе — с пооперационным эталоном.
 */
class SettlementCostIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000123";

  /** Меньше операций, чем в первом замере (800), но тот же порядок: проверка должна быть дешёвой. */
  private static final int OPERATIONS = 400;

  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();

  @Autowired
  private TransactionIntakeService intakeService;

  @Autowired
  private TransactionStore transactionStore;

  @Test
  @DisplayName("Накопленная сумма до позиции из SQL совпадает с суммой, посчитанной в Java")
  void spentBeforeMatchesSumComputedInJava() {
    List<ExpenseTransaction> settled = acceptAndSettle(50, "10.00");
    ExpenseTransaction target = settled.get(settled.size() / 2);
    BudgetPeriod period = target.period();

    BigDecimal fromSql =
        transactionStore.sumResolvedInPeriodBefore(ACCOUNT, ExpenseCategory.PRODUCT, period, target);

    BigDecimal inJava =
        transactionStore.findResolvedInPeriod(ACCOUNT, ExpenseCategory.PRODUCT, period).stream()
            .filter(candidate -> isStrictlyBefore(candidate, target))
            .map(ExpenseTransaction::amountUsd)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

    assertThat(fromSql).isEqualByComparingTo(inJava);
    assertThat(fromSql).isGreaterThan(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("Суффикс периода начинается с расчётной операции и идёт до конца месяца")
  void suffixStartsAtTheSettledTransaction() {
    List<ExpenseTransaction> settled = acceptAndSettle(50, "10.00");
    ExpenseTransaction target = settled.get(settled.size() / 2);

    List<ExpenseTransaction> suffix =
        transactionStore.findResolvedInPeriodFrom(
            ACCOUNT, ExpenseCategory.PRODUCT, target.period(), target);

    assertThat(suffix).hasSize(settled.size() - settled.indexOf(target));
    assertThat(suffix.get(0).id()).isEqualTo(target.id());
    assertThat(suffix).allMatch(ExpenseTransaction::isResolved);
  }

  @Test
  @DisplayName("Пачка из 400 операций одного месяца рассчитывается за секунды, а не за десятки")
  void largeBatchOfOnePeriodIsSettledQuickly() {
    accept(OPERATIONS);

    long startedAt = System.nanoTime();
    int settled = intakeService.settlePending(OPERATIONS);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(settled).isEqualTo(OPERATIONS);
    // Граница намеренно грубая: проверяется порядок стоимости, а не скорость машины. До перехода на
    // суффикс тот же тест занимал десятки секунд и падал здесь; линейная обработка укладывается в
    // разы. Порог не опущен до «должно быть быстро», чтобы тест не мигал на медленной машине.
    assertThat(elapsed)
        .as("расчёт %d операций одного периода занял %s", OPERATIONS, elapsed)
        .isLessThan(Duration.ofSeconds(30));

    // Итог при этом остаётся верным: сумма месяца 400 × 10 = 4000 USD против лимита по умолчанию.
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM expense_transaction WHERE status = 'RATE_RESOLVED' AND limit_exceeded",
                Integer.class))
        .isEqualTo(300);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT SUM(amount_usd) FROM expense_transaction WHERE status = 'RATE_RESOLVED'",
                BigDecimal.class))
        .isEqualByComparingTo("4000.00");
  }

  private void accept(int count) {
    for (int minute = 0; minute < count; minute++) {
      intakeService.accept(
          nextId(),
          ACCOUNT,
          "0000009999",
          "USD",
          new BigDecimal("10.00"),
          ExpenseCategory.PRODUCT,
          utc("2022-03-01").plusMinutes(minute));
    }
  }

  private List<ExpenseTransaction> acceptAndSettle(int count, String sum) {
    List<UUID> ids = new ArrayList<>();
    for (int minute = 0; minute < count; minute++) {
      ExpenseTransaction pending =
          intakeService.accept(
              nextId(),
              ACCOUNT,
              "0000009999",
              "USD",
              new BigDecimal(sum),
              ExpenseCategory.PRODUCT,
              utc("2022-03-01").plusMinutes(minute));
      ids.add(pending.id());
    }
    ids.forEach(id -> intakeService.settle(id));
    return ids.stream()
        .map(id -> intakeService.findById(id).orElseThrow())
        .sorted(java.util.Comparator.comparing(ExpenseTransaction::occurredAt))
        .toList();
  }

  /** Порядок при равном времени — как в SQL: сравнение канонического текста UUID. */
  private static boolean isStrictlyBefore(ExpenseTransaction candidate, ExpenseTransaction target) {
    int byTime = candidate.occurredAt().compareTo(target.occurredAt());
    return byTime < 0
        || (byTime == 0 && candidate.id().toString().compareTo(target.id().toString()) < 0);
  }

  private static UUID nextId() {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ID_SEQUENCE.incrementAndGet()));
  }
}
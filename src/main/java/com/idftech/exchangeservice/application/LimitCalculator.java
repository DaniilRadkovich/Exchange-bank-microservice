package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.infra.config.LimitProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/**
 * Доменный сервис расчёта месячных лимитов.
 *
 * <p>Содержит главное правило задания и не зависит ни от Spring, ни от БД, ни от HTTP: чистые
 * функции над доменными типами. Именно поэтому сценарии из таблицы ТЗ проверяются юнит-тестами без
 * контейнера.
 *
 * <h2>Семантика, зафиксированная по таблице ТЗ (п.4)</h2>
 *
 * <ol>
 *   <li>Лимит, действующий для транзакции, — последний установленный на момент её даты; если
 *       лимитов не было, действует лимит по умолчанию 1000 USD с датой установки в начале
 *       месяца.
 *   <li><b>Флаг</b> транзакции кумулятивный по периоду: сравнивается накопленная сумма всех
 *       транзакций месяца до и включая текущую с лимитом, действовавшим на момент этой
 *       транзакции. Поэтому после перехода остатка в минус флаг остаётся {@code true} и у
 *       следующих транзакций, а при остатке ровно 0 флаг равен {@code false}.
 *   <li><b>Остаток</b>, в отличие от флага, считается от расходов всего месяца с 1-го числа, а не
 *       от даты установки лимита. Именно поэтому в ТЗ при лимите 2000 USD от 10.01 остаток равен
 *       900 = 2000 − (500 + 600). Это подтверждено заказчиком и описано в README.
 *   <li>Смена лимита внутри месяца не влияет на флаги транзакций, совершённых раньше: их флаг
 *       рассчитан по лимиту, действовавшему тогда.
 * </ol>
 */
public class LimitCalculator {

  /** Точность сумм при сравнении с лимитом: копейки. */
  private static final int USD_SCALE = 2;

  private final LimitProperties limitProperties;
  private final Clock clock;

  public LimitCalculator(LimitProperties limitProperties, Clock clock) {
    this.limitProperties = limitProperties;
    this.clock = clock;
  }

  /** Лимит по умолчанию для периода: 1000 USD с датой установки в начале месяца. */
  public ExpenseLimit defaultLimit(String accountFrom, ExpenseCategory category, BudgetPeriod period) {
    return new ExpenseLimit(
        null,
        accountFrom,
        category,
        limitProperties.defaultSum().setScale(ExpenseLimit.USD_SCALE, RoundingMode.UNNECESSARY),
        Currency.getInstance(ExpenseLimit.USD_CURRENCY_CODE),
        OffsetDateTime.ofInstant(period.defaultLimitInstant(), limitProperties.zoneId()));
  }

  /**
   * Эффективный лимит транзакции: последний установленный не позже её даты, иначе — лимит по
   * умолчанию для периода транзакции.
   */
  public ExpenseLimit effectiveLimit(ExpenseTransaction transaction, List<ExpenseLimit> limitsInPeriod) {
    return effectiveLimitAsOf(
        transaction.accountFrom(), transaction.category(), transaction.period(), transaction.occurredAt(),
        limitsInPeriod);
  }

  /**
   * Эффективный лимит на заданный момент времени.
   *
   * <p>Тот же алгоритм нужен в двух местах: для флага транзакции важен момент её совершения, а для
   * остатка — текущий момент (лимит, установленный после последней операции месяца, уже действует).
   */
  public ExpenseLimit effectiveLimitAsOf(
      String accountFrom,
      ExpenseCategory category,
      BudgetPeriod period,
      OffsetDateTime asOf,
      List<ExpenseLimit> limitsInPeriod) {
    return limitsInPeriod.stream()
        .filter(limit -> !limit.limitDatetime().isAfter(asOf))
        .max((left, right) -> left.limitDatetime().compareTo(right.limitDatetime()))
        .orElseGet(() -> defaultLimit(accountFrom, category, period));
  }

  /**
   * Сумма расходов нарастающим итогом до и включая транзакцию {@code target}.
   *
   * <p>В сумму входят только транзакции того же месяца и той же категории: лимит в ТЗ принадлежит
   * паре «счёт + категория» и действует в пределах календарного месяца, поэтому расходы соседнего
   * месяца или соседней категории не должны влиять на флаг. Фильтрация по периоду и категории
   * делает метод устойчивым к тому, что в переданный список попало больше, чем нужно.
   *
   * <p>Транзакции с одинаковым временем учитываются по идентификатору, чтобы результат не зависел
   * от порядка строк в выборке.
   */
  public BigDecimal spentUpTo(List<ExpenseTransaction> candidateTransactions, ExpenseTransaction target) {
    BigDecimal total = BigDecimal.ZERO;
    for (ExpenseTransaction transaction : candidateTransactions) {
      if (transaction.amountUsd() == null
          || transaction.category() != target.category()
          || !transaction.period().equals(target.period())
          || !isNotAfter(transaction, target)) {
        continue;
      }
      total = total.add(transaction.amountUsd());
    }
    return total.setScale(USD_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Факт превышения для транзакции: накопленная сумма периода строго больше лимита, действовавшего
   * на момент её совершения. Строгое сравнение даёт {@code false} при остатке ровно 0.
   */
  public boolean isExceeded(ExpenseTransaction transaction, ExpenseLimit effectiveLimit, List<ExpenseTransaction> orderedTransactions) {
    BigDecimal spent = spentUpTo(orderedTransactions, transaction);
    return spent.compareTo(effectiveLimit.limitSum()) > 0;
  }

  /**
   * Остаток месячного лимита: сумма последнего установленного лимита минус расходы периода с
   * 1-го числа. Именно такая семантика даёт в ТЗ остаток 900 при лимите 2000 USD от 10.01 и
   * расходах 500 + 600 в том же месяце.
   */
  public BigDecimal remaining(
      List<ExpenseLimit> limitsInPeriod, List<ExpenseTransaction> allResolvedInPeriod) {
    if (limitsInPeriod.isEmpty()) {
      ExpenseTransaction reference = allResolvedInPeriod.isEmpty()
          ? null
          : allResolvedInPeriod.get(allResolvedInPeriod.size() - 1);
      BudgetPeriod period = BudgetPeriod.of(
          reference != null
              ? reference.period().value()
              : YearMonth.now(clock.withZone(limitProperties.zoneId())));
      ExpenseLimit effectiveDefault = defaultLimit(
          reference != null ? reference.accountFrom() : "0000000000",
          reference != null ? reference.category() : ExpenseCategory.PRODUCT,
          period);
      return effectiveDefault.limitSum()
          .subtract(spentOfPeriod(allResolvedInPeriod))
          .setScale(USD_SCALE, RoundingMode.HALF_UP);
    }
    return lastLimitOf(limitsInPeriod).orElseThrow().limitSum()
        .subtract(spentOfPeriod(allResolvedInPeriod))
        .setScale(USD_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Расходы периода целиком, независимо от дат установки лимитов.
   *
   * <p>Суммируются только транзакции с известной суммой в USD, то есть уже разрешённые: у
   * транзакции в статусе {@code PENDING} сумма ещё не рассчитана и не должна занижать остаток.
   */
  public BigDecimal spentOfPeriod(List<ExpenseTransaction> allResolvedInPeriod) {
    BigDecimal total = BigDecimal.ZERO;
    for (ExpenseTransaction transaction : allResolvedInPeriod) {
      if (transaction.amountUsd() != null) {
        total = total.add(transaction.amountUsd());
      }
    }
    return total.setScale(USD_SCALE, RoundingMode.HALF_UP);
  }

  /** Последний установленный лимит пары «счёт + категория» за период. */
  public Optional<ExpenseLimit> lastLimitOf(List<ExpenseLimit> limitsInPeriod) {
    if (limitsInPeriod.isEmpty()) {
      return Optional.empty();
    }
    return limitsInPeriod.stream()
        .max((left, right) -> left.limitDatetime().compareTo(right.limitDatetime()));
  }

  /** Порядок транзакций в периоде: по времени, при равенстве — по идентификатору для стабильности. */
  public static List<ExpenseTransaction> ordered(List<ExpenseTransaction> transactions) {
    return transactions.stream()
        .sorted(
            java.util.Comparator.comparing(ExpenseTransaction::occurredAt)
                .thenComparing(ExpenseTransaction::id, LIMIT_TIE_BREAK))
        .toList();
  }

  /** Текущее время сервиса в часовом поясе лимитов. */
  public OffsetDateTime now() {
    return OffsetDateTime.now(clock.withZone(limitProperties.zoneId()));
  }

  private static boolean isNotAfter(ExpenseTransaction candidate, ExpenseTransaction target) {
    int byTime = candidate.occurredAt().compareTo(target.occurredAt());
    return byTime < 0 || (byTime == 0 && LIMIT_TIE_BREAK.compare(candidate.id(), target.id()) <= 0);
  }

  /**
   * Порядок идентификаторов при равном времени операции — обязано совпадать с PostgreSQL.
   *
   * <p>Флаг {@code limit_exceeded} считает приложение, а накопленную сумму для клиента отдаёт SQL
   * (ТЗ п.6). При равных {@code occurred_at} обе стороны упорядочивают операции по {@code id}, и
   * если порядок разойдётся, накопленная сумма в ответе будет посчитана для другой операции, чем
   * флаг: клиент увидит «превышение» с чужой суммой.
   *
   * <p>Расхождение реальное, а не теоретическое: {@link java.util.UUID#compareTo} сравнивает
   * половинки как знаковые {@code long}, то есть для UUID с установленным старшим битом первого
   * полу-слова знак отрицательный. PostgreSQL сравнивает 16 байт беззнаково. Идентификаторы вида
   * {@code 00000000-0000-0000-0000-00000000000N} в обоих порядках совпадают — поэтому расхождение
   * не видно на тестовых идентификаторах, но проявляется на случайных UUID из {@code UUID.randomUUID()},
   * то есть на реальных данных клиента.
   *
   * <p>Сравнение ниже повторяет порядок PostgreSQL: побайтно, беззнаково, от старшего байта к
   * младшему. Это ровно лексикографический порядок канонического текста UUID в нижнем регистре.
   */
  private static final java.util.Comparator<java.util.UUID> LIMIT_TIE_BREAK =
      (left, right) -> left.toString().compareTo(right.toString());
}

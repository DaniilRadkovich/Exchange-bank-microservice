package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.port.LimitStore;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import com.idftech.exchangeservice.application.config.SettlementProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Транзакционная часть расчёта: применяет уже известный курс и считает флаг {@code limit_exceeded}.
 *
 * <h2>Зачем отдельный компонент</h2>
 *
 * <p>Курс добывается медленно — это HTTP-вызов к внешнему API с таймаутами и повторами. Если держать
 * его внутри транзакции БД под блокировкой периода, то соединение с PostgreSQL занято всё время
 * ожидания сети, а все параллельные расчёты того же счёта выстраиваются в очередь на замке. Расчёт
 * разделён на две фазы: {@link ParallelRateResolver} получает курсы без блокировок, а этот класс
 * применяет готовый курс под блокировкой периода.
 *
 * <p>Отдельный бин нужен ещё и по технической причине: {@code @Transactional} работает через прокси,
 * поэтому внутренний вызов метода того же класса транзакцию бы не открыл. Каждый расчёт здесь —
 * своя транзакция БД, и падение одной транзакции не откатывает остальные.
 */
@Component
public class SettlementApplier {

  private static final Logger log = LoggerFactory.getLogger(SettlementApplier.class);

  private final TransactionStore transactionStore;
  private final LimitStore limitStore;
  private final LimitCalculator limitCalculator;
  private final int maxAttempts;

  public SettlementApplier(
      TransactionStore transactionStore,
      LimitStore limitStore,
      LimitCalculator limitCalculator,
      SettlementProperties settlementProperties) {
    this.transactionStore = transactionStore;
    this.limitStore = limitStore;
    this.limitCalculator = limitCalculator;
    this.maxAttempts = settlementProperties.maxAttempts();
  }

  /**
   * Применяет курс и рассчитывает флаг превышения в собственной транзакции БД.
   *
   * <p>Транзакция читается повторно, а не берётся из аргумента: между получением курса и применением
   * транзакцию могли уже рассчитать — например, два прохода планировщика или ручной дорасчёт.
   * Повторная проверка {@code isResolved} делает применение идемпотентным.
   *
   * @param transactionId транзакция к расчёту
   * @param usdRate курс перевода 1 единицы валюты операции в USD
   * @return рассчитанная транзакция либо {@code null}, если её нет
   */
  @Transactional
  public ExpenseTransaction apply(UUID transactionId, BigDecimal usdRate) {
    ExpenseTransaction transaction = transactionStore.findById(transactionId).orElse(null);
    if (transaction == null) {
      log.warn("Transaction {} not found; nothing to settle", transactionId);
      return null;
    }
    if (transaction.isResolved()) {
      return transaction;
    }

    // Блокируем период до любого чтения накопленной суммы: иначе параллельный поток успел бы
    // прочитать ту же сумму, что и этот.
    BudgetPeriod period = transaction.period();
    transactionStore.lockPeriod(transaction.accountFrom(), transaction.category(), period);

    return settleWithRate(transaction, period, usdRate);
  }

  /**
   * Фиксирует неудачную попытку: транзакция остаётся {@code PENDING} и будет обработана позже.
   *
   * <p>Причина сбоя может быть любой: курс не пришёл либо расчёт упал. Это два разных уровня
   * тревоги, но одна транзакция состояния: счётчик попыток обязан расти в обоих случаях, иначе
   * {@code findPending} будет возвращать транзакцию по кругу, а {@code FAILED} недостижим.
   *
   * <p>Начиная с номера попытки, заданного в {@code exchange.settlement.max-attempts}, транзакция
   * переводится в {@code FAILED}: устранить причину автоматически не удалось, и бесконечный ретрай
   * только прятал бы это. Статус означает «требуется ручной дорасчёт», а не потерю данных.
   *
   * <p>Инкремент счётчика атомарен, поэтому пачка и повторный проход планировщика не могут затереть
   * попытку друг друга. См. {@link TransactionStore#registerUnresolvedAttempt(UUID, int)}.
   *
   * @return {@code true}, если попытка засчитана; {@code false}, если транзакция уже рассчитана
   */
  @Transactional
  public boolean registerUnresolvedAttempt(UUID transactionId) {
    return transactionStore.registerUnresolvedAttempt(transactionId, maxAttempts);
  }

  /**
   * Приводит флаги всех уже разрешённых операций периода в соответствие с текущими лимитами.
   *
   * <p>Вызывается при установке нового лимита. Новый лимит меняет порог для части операций месяца, а
   * их {@code limit_exceeded} был посчитан при прежнем пороге. Дата операции при этом ни при чём: банк
   * может прислать расход с будущей датой, он рассчитается раньше, чем клиент сменит лимит. Без
   * пересчёта {@code GET /limits/exceeded} отдавал бы транзацию, помеченную превышением, с
   * отрицательным {@code exceeded_by_usd}.
   *
   * <p>Период блокируется так же, как при расчёте операции, поэтому параллельный дорасчёт не может
   * прочитать старые флаги между чтением и записью.
   *
   * @return сколько флагов изменилось
   */
  @Transactional
  public int recalculatePeriod(String accountFrom, ExpenseCategory category, BudgetPeriod period) {
    transactionStore.lockPeriod(accountFrom, category, period);
    List<ExpenseTransaction> ordered =
        LimitCalculator.ordered(transactionStore.findResolvedInPeriod(accountFrom, category, period));
    List<ExpenseLimit> limits = limitStore.findLimitsInPeriod(accountFrom, category, period);
    int updated = storeChangedFlags(ordered, limitCalculator.computeFlags(ordered, limits), null);
    log.info(
        "Period {} of account {} / {} recalculated against {} limit(s): {} flag(s) changed",
        period.value(),
        accountFrom,
        category.code(),
        limits.size(),
        updated);
    return updated;
  }

  private ExpenseTransaction settleWithRate(
      ExpenseTransaction transaction, BudgetPeriod period, BigDecimal rate) {
    // Курс в USD применён, сумма известна — но ещё не записанная в БД. Для расчёта остатка
    // используем её в памяти, чтобы не требовать лишней записи и повторного чтения.
    ExpenseTransaction resolvedCandidate =
        new ExpenseTransaction(
            transaction.id(),
            transaction.accountFrom(),
            transaction.accountTo(),
            transaction.currency(),
            transaction.amount(),
            transaction.category(),
            transaction.occurredAt(),
            rate,
            ExpenseTransaction.toUsd(transaction.amount(), rate),
            TransactionStatus.RATE_RESOLVED,
            null);

    List<ExpenseTransaction> periodTransactions =
        withCandidate(
            transactionStore.findResolvedInPeriod(
                transaction.accountFrom(), transaction.category(), period),
            resolvedCandidate);
    List<ExpenseTransaction> ordered = LimitCalculator.ordered(periodTransactions);

    List<ExpenseLimit> limits =
        limitStore.findLimitsInPeriod(transaction.accountFrom(), transaction.category(), period);
    ExpenseLimit effectiveLimit = limitCalculator.effectiveLimit(resolvedCandidate, limits);
    boolean exceeded = limitCalculator.isExceeded(resolvedCandidate, effectiveLimit, ordered);

    ExpenseTransaction settled = resolvedCandidate.withLimitExceeded(exceeded);
    transactionStore.updateSettlement(settled);

    int refreshed = refreshStaleFlagsAfter(ordered, limits, resolvedCandidate);

    log.info(
        "Transaction {} settled: {} {} -> {} USD at rate {}, limit {} USD exceeded={} ({} stale flag(s) refreshed)",
        transaction.id(),
        transaction.amount(),
        transaction.currency().getCurrencyCode(),
        settled.amountUsd(),
        rate,
        effectiveLimit.limitSum(),
        exceeded,
        refreshed);
    return settled;
  }

  /**
   * Пересчитывает флаги операций, чей кумулятивный итог изменился из-за вновь рассчитанной операции.
   *
   * <p>Флаг по ТЗ кумулятивный: он сравнивает накопленную сумму месяца «до и включая» операцию. Значит
   * операция с ранней датой, дошедшая до расчёта позже операций с поздними датами, увеличивает
   * накопленный итог и для них. Если такие флаги не обновлять, они навсегда остаются заниженными:
   * пересчёт придёт следующим расчётом, но между ними клиент увидит неверный отчёт.
   *
   * <p>Поэтому после каждого расчёта флаги приводятся в соответствие с текущим набором операций
   * периода. Период уже заблокирован, поэтому гонки с параллельным расчётом нет.
   *
   * @param ordered все разрешённые операции периода, включая только что рассчитанную
   * @param limits лимиты периода
   * @param settled только что рассчитанная операция; её флаг уже записан
   * @return сколько флагов изменилось
   */
  private int refreshStaleFlagsAfter(
      List<ExpenseTransaction> ordered, List<ExpenseLimit> limits, ExpenseTransaction settled) {
    return storeChangedFlags(ordered, limitCalculator.computeFlags(ordered, limits), settled);
  }

  /**
   * Пересчитывает флаги и записывает только изменившиеся, чтобы не делать лишних UPDATE.
   *
   * <p>Флаги приходят одним проходом {@link LimitCalculator#computeFlags(List, List)}: сумма
   * накапливается по мере обхода, поэтому стоимость линейна по числу операций периода. Считать
   * накопленный итог отдельно для каждой операции было бы квадратичным расчётом на каждом расчёте
   * транзакции и на каждой смене лимита.
   *
   * @param flags флаги по идентификатору, посчитанные для всего периода
   * @param skip операция, чей флаг уже записан и которую надо пропустить; {@code null} — пересчитать
   *     все
   * @return сколько флагов изменилось
   */
  private int storeChangedFlags(
      List<ExpenseTransaction> ordered, Map<UUID, Boolean> flags, ExpenseTransaction skip) {
    int updated = 0;
    for (ExpenseTransaction candidate : ordered) {
      if (skip != null && candidate.id().equals(skip.id())) {
        continue;
      }
      if (!candidate.isResolved()) {
        continue;
      }
      boolean expected = Boolean.TRUE.equals(flags.get(candidate.id()));
      if (expected != Boolean.TRUE.equals(candidate.limitExceeded())) {
        transactionStore.updateSettlement(candidate.withLimitExceeded(expected));
        updated++;
      }
    }
    return updated;
  }

  /** Заменяет в списке запись с тем же идентификатором на рассчитанную. */
  private static List<ExpenseTransaction> withCandidate(
      List<ExpenseTransaction> stored, ExpenseTransaction candidate) {
    return java.util.stream.Stream.concat(
            stored.stream().filter(transaction -> !transaction.id().equals(candidate.id())),
            java.util.stream.Stream.of(candidate))
        .toList();
  }
}

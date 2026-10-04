package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.port.LimitStore;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
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
   * Снимает попытку, засчитанную при взятии транзакции в дорасчёт.
   *
   * <p>Отмена дорасчёта — не неудача: прерванный поток попытку не засчитывает (правило 17), но
   * claim уже успел её засчитать. Без снятия при {@code max-attempts: 1} один перезапуск сервиса
   * переводил бы в {@code FAILED} транзакции, которые никто не считал неудачными.
   */
  @Transactional
  public void releaseClaim(UUID transactionId) {
    transactionStore.releaseClaim(transactionId);
  }

  /**
   * Переводит транзакцию в {@code FAILED}, если попытки исчерпаны и она всё ещё не рассчитана.
   *
   * <p>Вызывается после обработки взятой транзакции. Рассчитанная не переводится: перевод при
   * заполненных курсе и сумме нарушил бы {@code ck_expense_tx_resolved_consistent}.
   */
  @Transactional
  public void markFailedIfExhausted(UUID transactionId) {
    transactionStore.markFailedIfExhausted(transactionId, maxAttempts);
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
    // Курс применён, флаг ещё не рассчитан: сначала кандидат без флага, чтобы посчитать накопленную
    // сумму периода вместе с ним. Сборку кандидата делает домен (ExpenseTransaction.resolved), иначе
    // округление курса и произведение amount × rate жили бы в двух местах и разъехались бы.
    ExpenseTransaction resolvedCandidate = transaction.resolved(rate, false);

    // Только суффикс периода, начиная с расчётной операции. У стоящих раньше накопленная сумма не
    // меняется, поэтому их флаги пересчитывать незачем, а накопленный итог до них даёт база одной
    // строкой. Читать весь период на каждый расчёт — значит платить за пачку квадратично: на 800
    // операциях одного месяца это десятки секунд вместо секунд.
    List<ExpenseTransaction> suffix =
        withCandidate(
            transactionStore.findResolvedInPeriodFrom(
                transaction.accountFrom(), transaction.category(), period, resolvedCandidate),
            resolvedCandidate);
    List<ExpenseTransaction> ordered = LimitCalculator.ordered(suffix);
    BigDecimal spentBefore =
        transactionStore.sumResolvedInPeriodBefore(
            transaction.accountFrom(), transaction.category(), period, resolvedCandidate);

    List<ExpenseLimit> limits =
        limitStore.findLimitsInPeriod(transaction.accountFrom(), transaction.category(), period);
    ExpenseLimit effectiveLimit = limitCalculator.effectiveLimit(resolvedCandidate, limits);

    // Флаги суффикса считаются с накопленного итога, и флаг самой операции — первый из них. Отдельный
    // пооперационный расчёт дал бы то же значение, но стал бы вторым правилом для одной величины.
    Map<UUID, Boolean> flags =
        limitCalculator.computeFlags(
            ordered,
            limits,
            LimitCalculator.spendingOf(
                resolvedCandidate.category(), resolvedCandidate.period(), spentBefore));
    boolean exceeded = Boolean.TRUE.equals(flags.get(resolvedCandidate.id()));

    ExpenseTransaction settled = resolvedCandidate.withLimitExceeded(exceeded);
    transactionStore.updateSettlement(settled);

    int refreshed = storeChangedFlags(ordered, flags, resolvedCandidate);

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
   * Записывает только изменившиеся флаги, чтобы не делать лишних UPDATE.
   *
   * <p>Флаги приходят готовыми: суффикс периода посчитан проходом {@link LimitCalculator#computeFlags}
   * с накопленным итогом, а полный период — при смене лимита. Считать накопленный итог отдельно для
   * каждой операции было бы квадратичным расчётом на каждом расчёте транзакции.
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

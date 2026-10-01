package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.exception.UnprocessableEntityException;
import com.idftech.exchangeservice.application.port.LimitStore;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.infra.config.LimitProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Приём транзакций и расчёт флага {@code limit_exceeded}.
 *
 * <h2>Асинхронность</h2>
 *
 * Контракт: {@code POST /api/v1/transactions} отвечает {@code 202 Accepted} сразу после сохранения
 * транзакции, а перевод в USD и расчёт флага выполняются после. Причина — внешний API курсов
 * медленный, и держать HTTP-запрос на нём нельзя. Транзакция никогда не теряется: если курс получить
 * не удалось, она остаётся в статусе {@code PENDING} и будет дорасчитана фоновой обработкой.
 *
 * <h2>Корректность при параллельных запросах</h2>
 *
 * Флаг зависит от накопленной суммы периода, поэтому два параллельных запроса одного клиента
 * должны обрабатываться последовательно. Это обеспечивает пессимистистная блокировка строки
 * {@code spend_period_lock}, взятая внутри транзакции БД в методе {@link #accept}: пока первый
 * поток не зафиксировал изменения, второй ждёт и видит уже обновлённый остаток.
 */
@Service
public class TransactionIntakeService {

  private static final Logger log = LoggerFactory.getLogger(TransactionIntakeService.class);

  private final TransactionStore transactionStore;
  private final LimitStore limitStore;
  private final LimitCalculator limitCalculator;
  private final ExchangeRateService exchangeRateService;
  private final LimitProperties limitProperties;
  private final Clock clock;

  public TransactionIntakeService(
      TransactionStore transactionStore,
      LimitStore limitStore,
      LimitCalculator limitCalculator,
      ExchangeRateService exchangeRateService,
      LimitProperties limitProperties,
      Clock clock) {
    this.transactionStore = transactionStore;
    this.limitStore = limitStore;
    this.limitCalculator = limitCalculator;
    this.exchangeRateService = exchangeRateService;
    this.limitProperties = limitProperties;
    this.clock = clock;
  }

  /**
   * Принимает транзакцию и сохраняет её в статусе {@code PENDING}.
   *
   * <p>Транзакция оплаты сама по себе является фактом и не зависит от доступности курса. Идентификатор
   * транзакции служит ключом идемпотентности: повторная отправка того же {@code transaction_id}
   * обновляет существующую запись, а не создаёт вторую.
   *
   * @param accountFrom счёт клиента — владелец лимита
   * @param accountTo счёт контрагента
   * @param currencyCode ISO-4217 код валюты операции
   * @param amount сумма операции
   * @param category категория расхода
   * @param occurredAt время операции с часовым поясом
   * @param transactionId идентификатор операции на стороне банка
   */
  @Transactional
  public ExpenseTransaction accept(
      UUID transactionId,
      String accountFrom,
      String accountTo,
      String currencyCode,
      java.math.BigDecimal amount,
      com.idftech.exchangeservice.domain.ExpenseCategory category,
      OffsetDateTime occurredAt) {

    log.info(
        "Accepting transaction {} for account {} amount {} {} at {}",
        transactionId,
        accountFrom,
        amount,
        currencyCode,
        occurredAt);

    ExpenseTransaction pending = new ExpenseTransaction(
        transactionId,
        accountFrom,
        accountTo,
        currencyOf(currencyCode),
        amount,
        category,
        occurredAt,
        null,
        null,
        com.idftech.exchangeservice.domain.TransactionStatus.PENDING,
        null);

    transactionStore.save(pending);
    return pending;
  }

  /**
   * Переводит сумму в USD и рассчитывает флаг превышения лимита.
   *
   * <p>Метод транзакционный: блокировка периода берётся на всё время расчёта, поэтому
   * параллельные вызовы для одного клиента выстраиваются в очередь.
   *
   * @return транзакция после расчёта либо в статусе {@code PENDING}, если курс недоступен
   */
  @Transactional
  public ExpenseTransaction settle(UUID transactionId) {
    ExpenseTransaction transaction = transactionStore.findById(transactionId).orElse(null);
    if (transaction == null) {
      log.warn("Transaction {} not found; nothing to settle", transactionId);
      return null;
    }
    if (transaction.isResolved()) {
      return transaction;
    }

    BudgetPeriod period = transaction.period();

    // Блокируем период до любого чтения: иначе параллельный поток успел бы прочитать ту же сумму.
    transactionStore.lockPeriod(transaction.accountFrom(), transaction.category(), period);

    java.time.LocalDate rateDate = transaction.occurredAt().atZoneSameInstant(limitProperties.zoneId()).toLocalDate();

    return exchangeRateService
        .resolveUsdRate(transaction.currency().getCurrencyCode(), rateDate)
        .map(rate -> settleWithRate(transaction, period, rate))
        .orElseGet(() -> leavePending(transaction));
  }

  private ExpenseTransaction settleWithRate(ExpenseTransaction transaction, BudgetPeriod period, java.math.BigDecimal rate) {
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
            com.idftech.exchangeservice.domain.TransactionStatus.RATE_RESOLVED,
            null);

    List<ExpenseTransaction> periodTransactions = withCandidate(transactionStore.findResolvedInPeriod(
            transaction.accountFrom(), transaction.category(), period), resolvedCandidate);
    List<ExpenseTransaction> ordered = LimitCalculator.ordered(periodTransactions);

    List<ExpenseLimit> limits =
        limitStore.findLimitsInPeriod(transaction.accountFrom(), transaction.category(), period);
    ExpenseLimit effectiveLimit = limitCalculator.effectiveLimit(resolvedCandidate, limits);
    boolean exceeded = limitCalculator.isExceeded(resolvedCandidate, effectiveLimit, ordered);

    ExpenseTransaction settled = resolvedCandidate.withLimitExceeded(exceeded);
    transactionStore.updateSettlement(settled);

    refreshStaleFlagsAfter(ordered, limits, resolvedCandidate);

    log.info(
        "Transaction {} settled: {} {} -> {} USD at rate {}, limit {} USD exceeded={}",
        transaction.id(),
        transaction.amount(),
        transaction.currency().getCurrencyCode(),
        settled.amountUsd(),
        rate,
        effectiveLimit.limitSum(),
        exceeded);
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
   */
  private void refreshStaleFlagsAfter(
      List<ExpenseTransaction> ordered, List<ExpenseLimit> limits, ExpenseTransaction settled) {
    for (ExpenseTransaction candidate : ordered) {
      if (candidate.id().equals(settled.id())) {
        continue;
      }
      if (!candidate.isResolved()) {
        continue;
      }
      ExpenseLimit limitAtMoment =
          limitCalculator.effectiveLimit(candidate, limits);
      boolean expected = limitCalculator.isExceeded(candidate, limitAtMoment, ordered);
      if (expected != Boolean.TRUE.equals(candidate.limitExceeded())) {
        transactionStore.updateSettlement(candidate.withLimitExceeded(expected));
      }
    }
  }

  private ExpenseTransaction leavePending(ExpenseTransaction transaction) {
    transactionStore.incrementSettlementAttempts(transaction.id());
    log.warn(
        "Rate unavailable for transaction {} ({} on {}); keeping PENDING for a later attempt",
        transaction.id(),
        transaction.currency().getCurrencyCode(),
        transaction.occurredAt());
    return transaction;
  }

  /** Заменяет в списке запись с тем же идентификатором на рассчитанный вариант. */
  private static List<ExpenseTransaction> withCandidate(
      List<ExpenseTransaction> stored, ExpenseTransaction candidate) {
    return java.util.stream.Stream.concat(
            stored.stream().filter(transaction -> !transaction.id().equals(candidate.id())),
            java.util.stream.Stream.of(candidate))
        .toList();
  }

  /**
   * Валюта операции по коду ISO 4217.
   *
   * <p>Код вида {@code ABC} удовлетворяет синтаксису, но не является валютой. Это смысловая ошибка,
   * а не ошибка формата, поэтому клиент получает {@code 422}, а не {@code 500} из
   * {@code IllegalArgumentException}.
   */
  private static java.util.Currency currencyOf(String currencyCode) {
    try {
      return java.util.Currency.getInstance(currencyCode);
    } catch (IllegalArgumentException e) {
      throw new UnprocessableEntityException(
          "unknown_currency", "Неизвестный код валюты ISO 4217: " + currencyCode);
    }
  }

  /**
   * Идентификатор операции на стороне банка.
   *
   * <p>Служит ключом идемпотентности: если банк прислал тот же {@code transaction_id}, повторная
   * обработка не создаст вторую запись. Если идентификатор не передан, сервис генерирует его сам.
   */
  public UUID resolveTransactionId(String externalId) {
    if (externalId == null || externalId.isBlank()) {
      return UUID.randomUUID();
    }
    try {
      return UUID.fromString(externalId.trim());
    } catch (IllegalArgumentException e) {
      log.warn("Client sent non-UUID transaction_id '{}'; generating a new one", externalId);
      return UUID.randomUUID();
    }
  }

  /** Транзакция по идентификатору либо пустой результат, если её нет. */
  @Transactional(readOnly = true)
  public Optional<ExpenseTransaction> findById(UUID transactionId) {
    return transactionStore.findById(transactionId);
  }

  /** Транзакции, ожидающие дорасчёта; используется фоновой обработкой. */
  @Transactional(readOnly = true)
  public List<ExpenseTransaction> findPending(int batchSize) {
    return transactionStore.findPending(Integer.MAX_VALUE, batchSize);
  }

  /** Досчитывает пачку транзакций. Каждая обрабатывается в собственной транзакции. */
  public int settlePending(int batchSize) {
    List<ExpenseTransaction> pending = findPending(batchSize);
    pending.forEach(
        transaction -> {
          try {
            settle(transaction.id());
          } catch (RuntimeException e) {
            log.warn("Settlement of transaction {} failed: {}", transaction.id(), e.toString());
          }
        });
    return pending.size();
  }

  /** Текущее время сервиса из бина часов. */
  public OffsetDateTime now() {
    return OffsetDateTime.now(clock.withZone(limitProperties.zoneId()));
  }
}

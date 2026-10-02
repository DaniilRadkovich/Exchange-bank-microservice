package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.exception.UnprocessableEntityException;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.infra.config.SettlementProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Приём транзакций и расчёт флага {@code limit_exceeded}.
 *
 * <h2>Асинхронность</h2>
 *
 * <p>Контракт: {@code POST /api/v1/transactions} отвечает {@code 202 Accepted} сразу после сохранения
 * транзакции, а перевод в USD и расчёт флага выполняются после. Причина — внешний API курсов
 * медленный, и держать HTTP-запрос на нём нельзя. Транзакция никогда не теряется: если курс получить
 * не удалось, она остаётся в статусе {@code PENDING} и будет дорасчитана фоновой обработкой.
 *
 * <h2>Расчёт в две фазы</h2>
 *
 * <p>Досчёт пачки разделён на фазы, потому что они конкурируют за разные ресурсы:
 * <ul>
 *   <li>{@link ParallelRateResolver} параллельно получает курсы на виртуальных потоках. Внешний API
 *       не наш, и ожидание сети не должно занимать соединение с PostgreSQL.
 *   <li>{@link SettlementApplier} применяет готовый курс под пессимистичной блокировкой периода.
 * </ul>
 *
 * <p>Смешивать их в одной транзакции нельзя: тогда весь HTTP-вызов выполнялся бы под блокировкой
 * {@code spend_period_lock}, и параллельные расчёты одного счёта выстроились бы в очередь.
 *
 * <h2>Корректность при параллельных запросах</h2>
 *
 * <p>Флаг зависит от накопленной суммы периода, поэтому два параллельных расчёта одного клиента
 * должны быть последовательны. Это обеспечивает пессимистистная блокировка строки
 * {@code spend_period_lock}, взятая до чтения накопленной суммы в {@link SettlementApplier}: пока
 * первый поток не зафиксировал изменения, второй ждёт и видит уже обновлённый остаток.
 *
 * <p>Параллелизм и корректность не противоречат друг другу: параллельно считаются транзакции
 * <em>разных</em> счётов и месяцев, а конкурирующие за один период выстраиваются в очередь на
 * блокировке — это и есть требуемая сериализация.
 */
@Service
public class TransactionIntakeService {

  private static final Logger log = LoggerFactory.getLogger(TransactionIntakeService.class);

  private final TransactionStore transactionStore;
  private final SettlementApplier settlementApplier;
  private final ExchangeRateService exchangeRateService;
  private final ParallelRateResolver parallelRateResolver;
  private final SettlementProperties settlementProperties;
  private final Clock clock;
  private final java.util.concurrent.Executor settlementExecutor;

  public TransactionIntakeService(
      TransactionStore transactionStore,
      SettlementApplier settlementApplier,
      ExchangeRateService exchangeRateService,
      ParallelRateResolver parallelRateResolver,
      SettlementProperties settlementProperties,
      Clock clock,
      @org.springframework.beans.factory.annotation.Qualifier("settlementExecutor")
          java.util.concurrent.Executor settlementExecutor) {
    this.transactionStore = transactionStore;
    this.settlementApplier = settlementApplier;
    this.exchangeRateService = exchangeRateService;
    this.parallelRateResolver = parallelRateResolver;
    this.settlementProperties = settlementProperties;
    this.clock = clock;
    this.settlementExecutor = settlementExecutor;
  }

  /**
   * Принимает транзакцию и сохраняет её в статусе {@code PENDING}.
   *
   * <p>Транзакция оплаты сама по себе является фактом и не зависит от доступности курса. Идентификатор
   * транзакции служит ключом идемпотентности: повторная отправка того же {@code transaction_id} не
   * создаёт вторую запись и не меняет первую, поэтому уже посчитанный флаг {@code limit_exceeded} не
   * пропадает до перерасчёта.
   *
   * @param accountFrom счёт клиента — владелец лимита
   * @param accountTo счёт контрагента
   * @param currencyCode ISO-4217 код валюты операции
   * @param amount сумма операции
   * @param category категория расхода
   * @param occurredAt время операции с часовым поясом
   * @param transactionId идентификатор операции на стороне банка
   * @return состояние транзакции из базы: у повторного приёма это уже принятая, возможно
   *     рассчитанная запись
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

    ExpenseTransaction stored = transactionStore.saveIfAbsent(pending);
    if (stored.status() != pending.status()) {
      // Повторная доставка. Молча отдавать PENDING клиенту нельзя: он увидит «не рассчитано» вместо
      // уже известного ответа, хотя счёт по факту давно закрыт.
      log.info("Transaction {} already accepted with status {}; duplicate delivery ignored", stored.id(), stored.status());
    }
    return stored;
  }

  /**
   * Переводит сумму в USD и рассчитывает флаг превышения лимита одной транзакции.
   *
   * <p>Метод намеренно не транзакционный. Раньше он был единым {@code @Transactional}-методом, и внешний
   * вызов курса выполнялся под блокировкой периода: соединение с PostgreSQL ждало сеть. Сейчас транзакция
   * открывается только вокруг расчёта флага — в {@link SettlementApplier#apply}, который вызывается через
   * прокси.
   *
   * <p>Аннотации {@code @Transactional} здесь быть не должно и в любом виде: транзакция метода по умолчанию
   * присоединялась бы к транзакции {@code apply} с {@code REQUIRED} и наследовала её режим. Если бы она была
   * ещё и {@code readOnly}, то {@code SELECT ... FOR UPDATE} внутри расчёта упал бы с «cannot execute SELECT
   * FOR UPDATE in a read-only transaction».
   *
   * @return транзакция после расчёта либо в статусе {@code PENDING}, если курс недоступен
   */
  public ExpenseTransaction settle(UUID transactionId) {
    ExpenseTransaction transaction = transactionStore.findById(transactionId).orElse(null);
    if (transaction == null) {
      log.warn("Transaction {} not found; nothing to settle", transactionId);
      return null;
    }
    if (transaction.isResolved()) {
      return transaction;
    }

    return exchangeRateService
        .resolveUsdRate(transaction.currency().getCurrencyCode(), rateDate(transaction))
        .map(rate -> settlementApplier.apply(transactionId, rate))
        .orElseGet(
            () -> {
              settlementApplier.registerUnresolvedAttempt(transactionId);
              return transaction;
            });
  }

  /**
   * Транзакции, ожидающие дорасчёта; используется фоновой обработкой.
   *
   * <p>Лимит попыток берётся из {@code exchange.settlement.max-attempts}. Раньше здесь стоял
   * {@code Integer.MAX_VALUE}, и настройка не действовала: транзакция с недоступным провайдером
   * дорасчитывалась планировщиком каждые 5 секунд вечно и никогда не переходила в {@code FAILED}.
   */
  @Transactional(readOnly = true)
  public List<ExpenseTransaction> findPending(int batchSize) {
    return transactionStore.findPending(settlementProperties.maxAttempts(), batchSize);
  }

  /**
   * Досчитывает пачку транзакций в две фазы (ТЗ п.1*).
   *
   * <p>Сначала параллельно на виртуальных потоках собираются курсы, затем каждый расчёт
   * применяется в собственной транзакции. Фазы разделены намеренно: если бы внешний вызов шёл внутри
   * второй фазы, он занял бы соединение с БД и заблокировал бы период на всё время ожидания сети.
   *
   * <p>Вторая фаза тоже параллельна, но конкурирующие за один период транзакции выстраиваются в
   * очередь на {@code spend_period_lock}. Это не потеря параллелизма, а требование корректности:
   * накопленная сумма общая для периода.
   *
   * @param batchSize максимальный размер пачки
   * @return число транзакций, взятых в обработку
   */
  public int settlePending(int batchSize) {
    List<ExpenseTransaction> pending = findPending(batchSize);
    if (pending.isEmpty()) {
      return 0;
    }

    Map<UUID, Optional<BigDecimal>> rates =
        parallelRateResolver.resolveRates(pending, this::rateDate, settlementExecutor);

    List<CompletableFuture<?>> settlements = new java.util.ArrayList<>(pending.size());
    for (ExpenseTransaction transaction : pending) {
      settlements.add(
          CompletableFuture.runAsync(
              () -> applySettlement(transaction, rates.get(transaction.id())), settlementExecutor));
    }
    awaitAll(settlements);

    return pending.size();
  }

  /** Применяет полученный курс; недоступный курс оставляет транзакцию в PENDING. */
  private void applySettlement(
      ExpenseTransaction transaction, Optional<BigDecimal> rate) {
    try {
      if (rate.isPresent()) {
        settlementApplier.apply(transaction.id(), rate.get());
      } else {
        settlementApplier.registerUnresolvedAttempt(transaction.id());
        log.warn(
            "Rate unavailable for transaction {} ({} on {}); keeping PENDING for a later attempt",
            transaction.id(),
            transaction.currency().getCurrencyCode(),
            transaction.occurredAt());
      }
    } catch (RuntimeException e) {
      // Транзакции не зависимы: падение одного расчёта не должно мешать остальным. Транзакция
      // останется PENDING и попадёт в следующий проход планировщика.
      log.warn("Settlement of transaction {} failed: {}", transaction.id(), e.toString());
    }
  }

  /**
   * Ждёт завершения пачки, не теряя исключения задач.
   *
   * <p>Ошибки уже залогированы в {@link #applySettlement}, поэтому здесь интересует только факт
   * завершения: без ожидания метод вернулся бы, пока расчёты ещё идут.
   */
  private void awaitAll(List<CompletableFuture<?>> settlements) {
    CompletableFuture.allOf(settlements.toArray(CompletableFuture[]::new)).join();
  }

  /** Дата, за которую берётся курс закрытия: операция в часовом поясе границ месяца лимита. */
  private LocalDate rateDate(ExpenseTransaction transaction) {
    return transaction.occurredAt().atZoneSameInstant(BudgetPeriod.LIMIT_TIMEZONE).toLocalDate();
  }


  /** Заменяет в списке запись с тем же идентификатором на рассчитанный вариант. */


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

  /** Текущее время сервиса из бина часов. */
  public OffsetDateTime now() {
    return OffsetDateTime.now(clock.withZone(BudgetPeriod.LIMIT_TIMEZONE));
  }
}

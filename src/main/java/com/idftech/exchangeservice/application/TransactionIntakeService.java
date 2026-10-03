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
 * <p>Первая фаза возвращает {@link RateResolution}, а не «курс или ничего»: недоступность курса у
 * провайдера и сбой нашей БД — разные события, и только второй требует внимания.
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
        .map(rate -> applyRate(transactionId, rate))
        .orElseGet(
            () -> {
              settlementApplier.registerUnresolvedAttempt(transactionId);
              log.warn(
                  "Rate unavailable for transaction {} ({}); attempt counted, keeping PENDING for a later attempt",
                  transactionId,
                  transaction.currency().getCurrencyCode());
              return transaction;
            });
  }

  /**
   * Применяет курс к транзакции, попытоку засчитывает и при сбое расчёта.
   *
   * <p>Попытка — это попытка независимо от причины: если расчёт упал, а счётчик не вырос, то
   * {@code findPending} вернёт транзакцию снова, а {@code exchange.settlement.max-attempts} никогда не
   * будет достигнут, и транзакция останется в {@code PENDING} навсегда — до перезапуска сервиса.
   * Исключение пробрасывается вызывающему: ручной дорасчёт должен видеть причину.
   */
  private ExpenseTransaction applyRate(UUID transactionId, BigDecimal rate) {
    try {
      return settlementApplier.apply(transactionId, rate);
    } catch (RuntimeException e) {
      registerFailedAttempt(transactionId, e);
      throw e;
    }
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

    Map<UUID, RateResolution> rates =
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

  /**
   * Применяет полученный курс, разбирая три исхода его получения.
   *
   * <p>Разбирать исходы по отдельности обязательно: «курса нет» — это ожидание следующего прохода
   * планировщика, а сбой нашей БД требует внимания и не должен выглядеть как недоступность биржи.
   * Транзакции в пачке не зависят, поэтому и сбой расчёта, и неожиданная ошибка разбора исходов
   * гасятся на одну задачу: остальные транзакции обязаны быть рассчитаны.
   */
  private void applySettlement(
      ExpenseTransaction transaction, RateResolution resolution) {
    try {
      switch (resolution) {
        case RateResolution.Resolved resolved ->
            settlementApplier.apply(transaction.id(), resolved.rate());
        case RateResolution.Unavailable ignored -> registerUnavailableRate(transaction);
        case RateResolution.Failed failed ->
            registerFailedAttempt(transaction.id(), failed.cause());
      }
    } catch (RuntimeException e) {
      registerFailedAttempt(transaction.id(), e);
    }
  }

  /** Провайдер не дал курса: ждём следующего прохода, попытка засчитана как ожидание. */
  private void registerUnavailableRate(ExpenseTransaction transaction) {
    settlementApplier.registerUnresolvedAttempt(transaction.id());
    log.warn(
        "Rate unavailable for transaction {} ({} on {}); attempt counted, keeping PENDING for a later attempt",
        transaction.id(),
        transaction.currency().getCurrencyCode(),
        transaction.occurredAt());
  }

  /**
   * Считает попытку, расчёт которой сорвался, и фиксирует причину.
   *
   * <p>Счётчик попыток обязан расти при любом сбое, а не только когда не пришёл курс: {@code
   * findPending} берёт транзакции, у которых попыток меньше {@code
   * exchange.settlement.max-attempts}, поэтому не посчитанная попытка даёт бесконечный ретрай каждые
   * {@code retry-delay} секунд и транзакция не доходит до {@code FAILED} никогда.
   *
   * <p>Уровень {@code ERROR} здесь, а не {@code WARN} как у недоступного курса, по существу: сбой
   * расчёта — это либо наша ошибка, либо данные, которые мы не смогли посчитать, и оба случая требуют
   * внимания. Подавить его нельзя и повторять вечно нельзя, поэтому транзакция доходит до
   * {@code FAILED}, а данные остаются на месте: {@code FAILED} означает «требуется ручной дорасчёт».
   *
   * <p>Само фиксирование попытки тоже может не пройти (например, соединение с PostgreSQL не
   * восстановилось). Тогда исходная ошибка не теряется, но остаётся непосчитанной попыткой: об этом
   * отдельная запись в лог, потому что тишина была бы хуже.
   */
  private void registerFailedAttempt(UUID transactionId, RuntimeException cause) {
    log.error(
        "Settlement of transaction {} failed: {}; attempt counted, transaction stays PENDING until"
            + " exchange.settlement.max-attempts is reached",
        transactionId,
        cause.toString(),
        cause);
    try {
      settlementApplier.registerUnresolvedAttempt(transactionId);
    } catch (RuntimeException registrationFailure) {
      log.error(
          "Could not count the failed settlement attempt of transaction {}", transactionId,
          registrationFailure);
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

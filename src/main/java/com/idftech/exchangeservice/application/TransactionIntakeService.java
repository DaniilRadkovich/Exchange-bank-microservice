package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.config.SettlementProperties;
import com.idftech.exchangeservice.application.exception.RateCallCancelledException;
import com.idftech.exchangeservice.application.exception.UnprocessableEntityException;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p>Ожидание клиента снимается событием приёма: зафиксированная строка ставит проход фоновой
 * дорасчётки в очередь ({@link PendingSettlementScheduler#onTransactionAccepted}), поэтому флаг
 * появляется сразу, а не через {@code exchange.settlement.retry-delay}. Плановый проход остаётся
 * страховкой на случай, если событие не дошло.
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
  private final Executor settlementExecutor;
  private final ApplicationEventPublisher events;

  public TransactionIntakeService(
      TransactionStore transactionStore,
      SettlementApplier settlementApplier,
      ExchangeRateService exchangeRateService,
      ParallelRateResolver parallelRateResolver,
      SettlementProperties settlementProperties,
      Clock clock,
      @Qualifier("settlementExecutor") Executor settlementExecutor,
      ApplicationEventPublisher events) {
    this.transactionStore = transactionStore;
    this.settlementApplier = settlementApplier;
    this.exchangeRateService = exchangeRateService;
    this.parallelRateResolver = parallelRateResolver;
    this.settlementProperties = settlementProperties;
    this.clock = clock;
    this.settlementExecutor = settlementExecutor;
    this.events = events;
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
      BigDecimal amount,
      ExpenseCategory category,
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
        TransactionStatus.PENDING,
        null);

    ExpenseTransaction stored = transactionStore.saveIfAbsent(pending);
    if (stored.status() != pending.status()) {
      log.info("Transaction {} already accepted with status {}; duplicate delivery ignored", stored.id(), stored.status());
      return stored;
    }

    events.publishEvent(new TransactionAccepted(stored.id()));
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
   * <p>Попытка засчитывается при любом исходе, включая сбой получения курса на нашей стороне. Раньше
   * исключение из {@code resolveUsdRate} выходило раньше ветки {@code orElseGet}, счётчик не рос, и
   * ручной дорасчёт зависал в бесконечном ретрае: {@code FAILED} был недостижим ровно в том пути,
   * который вызывает человек. Пакетный {@code settlePending} с этим был справлен, поэтому баг и жил.
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

    Optional<BigDecimal> rate;
    try {
      rate =
          exchangeRateService.resolveUsdRate(
              transaction.currency().getCurrencyCode(), rateDate(transaction));
    } catch (RuntimeException e) {
      registerFailedAttempt(transactionId, e);
      throw e;
    }

    return rate
        .map(resolved -> applyRate(transactionId, resolved))
        .orElseGet(
            () -> {
              registerUnavailableRate(transaction);
              return reloaded(transaction);
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
   * Берёт пачку в дорасчёт: блокирует строки и засчитывает им попытку одной транзакцией.
   *
   * <p>Отдельный метод, а не {@code findPending}, потому что взятие пачки обязано быть атомарным:
   * обычный {@code SELECT} позволял двум задачам взять одни и те же строки. Здесь же попытка
   * засчитывается сразу — сбой между взятием и расчётом не оставит транзакцию с незасчитанной
   * попыткой.
   */
  @Transactional
  public List<ExpenseTransaction> claimPending(int batchSize) {
    return transactionStore.claimPending(settlementProperties.maxAttempts(), batchSize);
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
   * <p>Пачка берётся через {@link #claimPending(int)}: строки блокируются и засчитываются одной
   * транзакцией, поэтому две реплики сервиса (или два прохода планировщика) не разбирают одну
   * пачку дважды.
   *
   * @param batchSize максимальный размер пачки
   * @return число транзакций, взятых в обработку
   */
  public int settlePending(int batchSize) {
    List<ExpenseTransaction> pending = claimPending(batchSize);
    if (pending.isEmpty()) {
      return 0;
    }

    Map<UUID, RateResolution> rates =
        parallelRateResolver.resolveRates(pending, this::rateDate, settlementExecutor);

    List<CompletableFuture<?>> settlements = new ArrayList<>(pending.size());
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
   * <p>Попытка уже засчитана при взятии пачки ({@link #claimPending(int)}), поэтому здесь она не
   * засчитывается повторно: двойной счёт привёл бы к тому, что транзакция достигает {@code FAILED}
   * вдвое быстрее. Разбирать исходы по отдельности обязательно: «курса нет» — это ожидание следующего
   * прохода планировщика, а сбой нашей БД требует внимания и не должен выглядеть как недоступность
   * биржи. Транзакции в пачке не зависят, поэтому и сбой расчёта, и неожиданная ошибка разбора
   * исходов гасятся на одну задачу: остальные транзакции обязаны быть рассчитаны.
   *
   * <p>После обработки транзакция переводится в {@code FAILED}, если попытки исчерпаны и она всё ещё
   * не рассчитана. Рассчитанная не переводится: у неё уже есть курс и сумма.
   */
  private void applySettlement(
      ExpenseTransaction transaction, RateResolution resolution) {
    try {
      switch (resolution) {
        case RateResolution.Resolved resolved ->
            settlementApplier.apply(transaction.id(), resolved.rate());
        case RateResolution.Unavailable ignored ->
            log.warn(
                "Rate unavailable for transaction {} ({} on {}); attempt counted at claim, keeping"
                    + " PENDING for a later attempt",
                transaction.id(),
                transaction.currency().getCurrencyCode(),
                transaction.occurredAt());
        case RateResolution.Failed failed ->
            log.error(
                "Settlement of transaction {} failed: {}",
                transaction.id(),
                failed.cause().toString(),
                failed.cause());
      }
    } catch (RateCallCancelledException e) {
      log.info("Settlement of transaction {} cancelled; attempt not counted", transaction.id());
      settlementApplier.releaseClaim(transaction.id());
      return;
    } catch (RuntimeException e) {
      log.error("Settlement of transaction {} failed: {}", transaction.id(), e.toString(), e);
    }
    settlementApplier.markFailedIfExhausted(transaction.id());
  }

  /**
   * Провайдер не дал курса: ждём следующего прохода, попытка засчитана как ожидание.
   *
   * <p>Если попытка не засчиталась, транзакцию посчитал кто-то ещё (вторая реплика или соседний
   * проход планировщика), и курс ей больше не нужен. Это не авария, поэтому и уровень другой: сообщение
   * уровня {@code WARN} говорило бы, что банку нужно что-то делать, когда делать нечего.
   */
  private void registerUnavailableRate(ExpenseTransaction transaction) {
    if (settlementApplier.registerUnresolvedAttempt(transaction.id())) {
      log.warn(
          "Rate unavailable for transaction {} ({} on {}); attempt counted, keeping PENDING for a later attempt",
          transaction.id(),
          transaction.currency().getCurrencyCode(),
          transaction.occurredAt());
    } else {
      log.debug(
          "Rate unavailable for transaction {} ({} on {}), but the transaction is already resolved; nothing to retry",
          transaction.id(),
          transaction.currency().getCurrencyCode(),
          transaction.occurredAt());
    }
  }

  /**
   * Состояние транзакции после засчитанной попытки, а не снимок, взятый до неё.
   *
   * <p>Снимок устаревает в обе стороны: попытка могла перевести транзакцию в {@code FAILED}, если
   * достигнут {@code exchange.settlement.max-attempts}, и вызывающий получил бы {@code PENDING} для
   * транзакции, которую дорасчёту уже не подлежит. Перечитывание стоит одного SELECT и происходит
   * только на ветке недоступного курса, то есть не на платёжном пути.
   */
  private ExpenseTransaction reloaded(ExpenseTransaction beforeAttempt) {
    return transactionStore.findById(beforeAttempt.id()).orElse(beforeAttempt);
  }

  /**
   * Считает попытку, расчёт которой сорвался, и фиксирует причину.
   *
   * <p>Счётчик попыток обязан расти при любом сбое, а не только когда не пришёл курс: {@code
   * findPending} берёт транзакции, у которых попыток меньше {@code
   * exchange.settlement.max-attempts}, поэтому не посчитанная попытка даёт бесконечный ретрай каждые
   * {@code retry-delay} секунд и транзакция не доходит до {@code FAILED} никогда.
   *
   * <p>Отмена расчёта — единственное исключение из этого правила, и проверяется она здесь, в одном
   * месте: {@link RateCallCancelledException} означает, что дорасчёт не состоялся по вине остановки
   * сервиса, а не провался. Засчитывать такую попытку нельзя — при {@code max-attempts: 1} один
   * перезапуск переводил бы в {@code FAILED} транзакции, которые никто не считал неудачными.
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
    if (cause instanceof RateCallCancelledException) {
      log.info(
          "Settlement of transaction {} cancelled before the rate was applied; attempt not counted,"
              + " the transaction stays PENDING",
          transactionId);
      return;
    }
    log.error("Settlement of transaction {} failed: {}", transactionId, cause.toString(), cause);
    try {
      if (settlementApplier.registerUnresolvedAttempt(transactionId)) {
        log.error(
            "Attempt counted; the transaction stays PENDING until exchange.settlement.max-attempts"
                + " is reached");
      } else {
        log.warn(
            "Attempt not counted: transaction {} is already resolved or gone", transactionId);
      }
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

  /**
   * Валюта операции по коду ISO 4217.
   *
   * <p>Код вида {@code ABC} удовлетворяет синтаксису, но не является валютой. Это смысловая ошибка,
   * а не ошибка формата, поэтому клиент получает {@code 422}, а не {@code 500} из
   * {@code IllegalArgumentException}.
   */
  private static Currency currencyOf(String currencyCode) {
    try {
      return Currency.getInstance(currencyCode);
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
   *
   * <p>Неразбираемое значение не подменяется сгенерированным. Раньше было наоборот: банк присылал
   * свой идентификатор, а сервис молча выдавал случайный UUID. Ключ идемпотентности при этом менялся
   * на каждой доставке, потерянный ответ приводил к повторной отправке, а та создавала вторую
   * запись и удваивала расход месяца — данные не терялись только потому, что банк умел повторять.
   * Формат проверяется на границе в {@code TransactionRequest}, поэтому сюда значение приходит уже
   * разбираемым, а {@link IllegalArgumentException} означал бы ошибку в нашей проверке.
   */
  public UUID resolveTransactionId(String externalId) {
    if (externalId == null || externalId.isBlank()) {
      return UUID.randomUUID();
    }
    return UUID.fromString(externalId.trim());
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

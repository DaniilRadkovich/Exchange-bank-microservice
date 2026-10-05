package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import com.idftech.exchangeservice.application.config.LimitProperties;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Адаптер порта {@link TransactionStore} поверх Spring Data JPA.
 *
 * <p>Здесь же реализована блокировка периода. Это ключевая часть требования ТЗ о многопоточности:
 * два параллельных запроса одного клиента не должны увидеть один и тот же остаток лимита. Поэтому
 * перед расчётом флага берётся пессимистистная блокировка строки-замка
 * {@code spend_period_lock(account_from, expense_category, budget_period)} — второй поток
 * выстроится в очередь и увидит уже обновлённый остаток.
 *
 * <p>Плюс {@link ExpenseTransactionEntity} переводится в статус {@code RATE_RESOLVED} внутри той же
 * транзакции БД, что гарантирует атомарность «посчитали остаток → выставили флаг».
 */
@Repository
@Transactional
public class JpaTransactionStore implements TransactionStore {

  private static final String LOCK_SQL = """
      SELECT 1 FROM spend_period_lock
      WHERE account_from = :accountFrom
        AND expense_category = :expenseCategory
        AND budget_period = :budgetPeriod
      FOR UPDATE
      """;

  private static final String LOCK_INSERT = """
      INSERT INTO spend_period_lock (account_from, expense_category, budget_period)
      VALUES (:accountFrom, :expenseCategory, :budgetPeriod)
      ON CONFLICT (account_from, expense_category, budget_period) DO NOTHING
      """;

  /**
   * Засчитывает попытку дорасчёта одной командой: инкремент и переход в {@code FAILED} считает база.
   *
   * <p>Правая часть {@code SET} в {@code UPDATE} всегда читает старое значение строки, поэтому
   * {@code settlement_attempts + 1} здесь — это именно новое значение попытки.
   */
  private static final String REGISTER_ATTEMPT = """
      UPDATE expense_transaction
      SET settlement_attempts = settlement_attempts + 1,
          status = CASE WHEN settlement_attempts + 1 >= :maxAttempts THEN :failed ELSE status END
      WHERE id = :id
        AND status <> :resolved
      """;

  /**
   * Снимает попытку, засчитанную при взятии транзакции в дорасчёт.
   *
   * <p>Отмена дорасчёта — не неудача, поэтому попытка не должна оставаться в счётчике. Условие
   * {@code settlement_attempts > 0} не даёт уйти в отрицательное значение, если сработают два
   * прерванных прохода подряд.
   */
  private static final String RELEASE_CLAIM = """
      UPDATE expense_transaction
      SET settlement_attempts = settlement_attempts - 1
      WHERE id = :id
        AND status = 'PENDING'
        AND settlement_attempts > 0
      """;

  /**
   * Переводит в {@code FAILED} транзакцию, у которой исчерпаны попытки.
   *
   * <p>Условие {@code status = 'PENDING'} обязательно: рассчитанная транзакция попытками больше не нужна,
   * а перевод её в {@code FAILED} при заполненных курсе и сумме нарушил бы
   * {@code ck_expense_tx_resolved_consistent}.
   */
  private static final String MARK_FAILED = """
      UPDATE expense_transaction
      SET status = :failed
      WHERE id = :id
        AND status = 'PENDING'
        AND settlement_attempts >= :maxAttempts
      """;

  private static final String INSERT_IF_ABSENT = """
      INSERT INTO expense_transaction (
          id, account_from, account_to, currency_code, amount, expense_category,
          occurred_at, usd_rate, amount_usd, status, limit_exceeded, settlement_attempts)
      VALUES (
          :id, :accountFrom, :accountTo, :currencyCode, :amount, :expenseCategory,
          :occurredAt, :usdRate, :amountUsd, :status, :limitExceeded, 0)
      ON CONFLICT (id) DO NOTHING
      """;

  private final EntityManager entityManager;
  private final ExpenseTransactionJpaRepository repository;
  private final LimitAnalyticsQueryRepository analyticsRepository;
  private final LimitProperties limitProperties;
  private final PersistenceMapper mapper;

  public JpaTransactionStore(
      EntityManager entityManager,
      ExpenseTransactionJpaRepository repository,
      LimitAnalyticsQueryRepository analyticsRepository,
      LimitProperties limitProperties,
      PersistenceMapper mapper) {
    this.entityManager = entityManager;
    this.repository = repository;
    this.analyticsRepository = analyticsRepository;
    this.limitProperties = limitProperties;
    this.mapper = mapper;
  }

  /**
   * Вставка «если ещё нет» одной нативной командой {@code ON CONFLICT DO NOTHING}.
   *
   * <p>Приём транзакции идемпотентен по {@code id}: повторная доставка того же {@code transaction_id}
   * не должна менять уже рассчитанную строку. Раньше здесь был {@code repository.save()}, который
   * делает merge и затирал у существующей записи курс, сумму в USD и флаг {@code limit_exceeded} —
   * до пересчёта клиент видел превышение как непревышение.
   *
   * <p>Проверка существования в Java здесь не годится: два параллельных повтора одного
   * {@code transaction_id} (типичный сетевой ретрай с нескольких инстансов) оба увидели бы
   * «нет такой строки» и один из них получил бы нарушение первичного ключа вместо успешного
   * {@code 202}. Решение отдаётся базе, которая и делает вставку атомарной.
   *
   * <p>Блокировку периода приём <b>не</b> берёт, и это осознанно. Она нужна расчёту, который читает
   * накопленную сумму; приём только вставляет строку {@code PENDING}, в накопленную сумму не
   * входящую. Раньше здесь стоял вызов {@code ensurePeriodLock}, и приём вставал в очередь за
   * расчётом того же «счёт + категория + месяц»: один {@code POST} ждал бы окончания всей пачки
   * дорасчёта, а каждый такой запрос удерживал соединение пула, пока ждал. Соединение, занятое
   * ожиданием строки, недоступно расчёту, который за этой строкой стоит, поэтому при неудачном
   * соотношении пула и параллелизма ожидание блокировки выедало пул целиком. Регресс-тест на это —
   * {@code ConcurrentSettlementIntegrationTest#intakeDoesNotWaitForSettlementPeriodLock}.
   */
  @Override
  public ExpenseTransaction saveIfAbsent(ExpenseTransaction transaction) {
    entityManager
        .createNativeQuery(INSERT_IF_ABSENT)
        .setParameter("id", transaction.id())
        .setParameter("accountFrom", transaction.accountFrom())
        .setParameter("accountTo", transaction.accountTo())
        .setParameter("currencyCode", transaction.currency().getCurrencyCode())
        .setParameter("amount", transaction.amount())
        .setParameter("expenseCategory", transaction.category().code())
        .setParameter("occurredAt", transaction.occurredAt().withOffsetSameInstant(ZoneOffset.UTC))
        .setParameter("usdRate", transaction.usdRate())
        .setParameter("amountUsd", transaction.amountUsd())
        .setParameter("status", transaction.status().name())
        .setParameter("limitExceeded", transaction.limitExceeded())
        .executeUpdate();
    return repository.findById(transaction.id()).map(this::toDomain).orElseThrow();
  }

  @Override
  public void lockPeriod(String accountFrom, ExpenseCategory category, BudgetPeriod period) {
    ensurePeriodLock(accountFrom, category, period.value().toString());
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ExpenseTransaction> findById(UUID id) {
    return repository.findById(id).map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExpenseTransaction> findResolvedInPeriod(
      String accountFrom, ExpenseCategory category, BudgetPeriod period) {
    JpaLimitStore.PeriodBounds bounds = JpaLimitStore.bounds(period);
    return repository
        .findResolvedInPeriod(accountFrom, category, bounds.start(), bounds.endExclusive())
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExpenseTransaction> findResolvedInPeriodFrom(
      String accountFrom, ExpenseCategory category, BudgetPeriod period, ExpenseTransaction from) {
    JpaLimitStore.PeriodBounds bounds = JpaLimitStore.bounds(period);
    return repository
        .findResolvedInPeriodFrom(
            accountFrom, category, bounds.start(), bounds.endExclusive(),
            from.occurredAt().toInstant(), from.id())
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public BigDecimal sumResolvedInPeriodBefore(
      String accountFrom, ExpenseCategory category, BudgetPeriod period, ExpenseTransaction before) {
    JpaLimitStore.PeriodBounds bounds = JpaLimitStore.bounds(period);
    BigDecimal sum =
        repository.sumResolvedInPeriodBefore(
            accountFrom, category, bounds.start(), bounds.endExclusive(),
            before.occurredAt().toInstant(), before.id());
    return sum == null ? BigDecimal.ZERO : sum;
  }

  /**
   * Пишет только результат расчёта: курс, сумму в USD и флаг превышения.
   *
   * <p>Неудачные попытки сюда не попадают — у них отдельный путь
   * {@link #registerUnresolvedAttempt(UUID, int)}. Раньше здесь был {@code else} со счётчиком
   * попыток, и вызов с неразрешённой транзакцией тихо увеличивал счётчик второй раз, не доходя до
   * {@code FAILED}.
   */
  @Override
  public void updateSettlement(ExpenseTransaction transaction) {
    if (transaction.status() != TransactionStatus.RATE_RESOLVED) {
      throw new IllegalArgumentException(
          "updateSettlement accepts only RATE_RESOLVED, got " + transaction.status());
    }
    repository
        .findById(transaction.id())
        .ifPresent(
            entity ->
                entity.applySettlement(
                    transaction.usdRate(),
                    transaction.amountUsd(),
                    Boolean.TRUE.equals(transaction.limitExceeded())));
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExpenseTransaction> findPending(int maxAttempts, int batchSize) {
    return repository.findPending(maxAttempts, PageRequest.of(0, batchSize)).stream()
        .map(this::toDomain)
        .toList();
  }

  /**
   * Берёт пачку в дорасчёт одной транзакцией: блокирует строки и засчитывает им попытку.
   *
   * <p>Блокировка и инкремент обязаны идти в одной транзакции: между ними другая задача увидела бы те же
   * строки без блокировки и взяла их второй раз. После фиксации блокировки снимаются, поэтому взятые
   * транзакции перечитываются заново — за время между фиксацией и чтением их мог посчитать кто-то
   * другой, и расчёт обязан увидеть уже рассчитанную строку.
   */
  @Override
  @Transactional
  public List<ExpenseTransaction> claimPending(int maxAttempts, int batchSize) {
    List<UUID> ids = repository.lockPendingIds(maxAttempts, batchSize);
    if (ids.isEmpty()) {
      return List.of();
    }
    repository.incrementAttempts(ids);
    // Контекст персистентности держит сущности со старым счётчиком после пакетного UPDATE.
    entityManager.clear();
    return repository.findAllById(ids).stream().map(this::toDomain).toList();
  }

  @Override
  @Transactional
  public void releaseClaim(UUID id) {
    entityManager
        .createNativeQuery(RELEASE_CLAIM)
        .setParameter("id", id)
        .executeUpdate();
  }

  @Override
  @Transactional
  public void markFailedIfExhausted(UUID id, int maxAttempts) {
    entityManager
        .createNativeQuery(MARK_FAILED)
        .setParameter("id", id)
        .setParameter("maxAttempts", maxAttempts)
        .setParameter("failed", TransactionStatus.FAILED.name())
        .executeUpdate();
  }

  /**
   * Засчитывает попытку дорасчёта атомарно, без чтения счётчика в Java.
   *
   * <p>Раньше здесь был {@code findById} с прибавлением единицы и последующей записью сущности. Это
   * read-modify-write, и он терял инкременты: два конкурентных дорасчёта одной транзакции (два прохода
   * планировщика либо две реплики сервиса) читали одно и то же значение и писали одно и то же
   * увеличенное. Счётчик зависал на месте, а вместе с ним и переход в {@code FAILED}: при {@code
   * exchange.settlement.max-attempts: 1} транзакция не доходила до него никогда и оставалась
   * {@code PENDING} до перезапуска сервиса. Теперь инкремент и статус вычисляет сама база, и
   * конкурентные попытки выстраиваются в очередь на блокировке строки, а не затирают друг друга.
   *
   * <p>Условие {@code status <> 'RATE_RESOLVED'} обязательно. Рассчитанная транзакция попытками
   * больше не нужна, а перевод её обратно в {@code FAILED} при заполненных курсе и сумме нарушил бы
   * CHECK {@code ck_expense_tx_resolved_consistent} — то есть неудачный дорасчёт упал бы с ошибкой
   * целостности вместо того, чтобы просто ничего не делать.
   *
   * <p>После нативного {@code UPDATE} сущность в контексте персистентности может нести старое
   * значение счётчика. Это безопасно: решение о статусе принимает тот же {@code UPDATE}, а отбор
   * транзакций в дорасчёт ({@link #findPending}) фильтрует по счётчику в SQL, а не по уже загруженной
   * сущности.
   *
   * @return {@code true}, если попытка засчитана
   */
  @Override
  public boolean registerUnresolvedAttempt(UUID id, int maxAttempts) {
    return entityManager
        .createNativeQuery(REGISTER_ATTEMPT)
        .setParameter("id", id)
        .setParameter("maxAttempts", maxAttempts)
        .setParameter("failed", TransactionStatus.FAILED.name())
        .setParameter("resolved", TransactionStatus.RATE_RESOLVED.name())
        .executeUpdate()
        > 0;
  }

  /**
   * Блокирует период «счёт + категория + месяц» до конца текущей транзакции БД.
   *
   * <p>Строка-замок создаётся лениво: если её ещё нет, вставляем и повторяем {@code SELECT FOR
   * UPDATE}. Уникальный ключ {@code (account_from, expense_category, budget_period)} делает вставку
   * идемпотентной и безопасной при гонке двух потоков.
   *
   * <p>Категория пишется кодом ({@code product}), а не именем enum: в нативных запросах нет
   * конвертера JPA, и только код совпадает с тем, что пишет {@link ExpenseCategoryConverter}.
   */
  private void ensurePeriodLock(String accountFrom, ExpenseCategory category, String budgetPeriod) {
    boolean locked = tryLock(accountFrom, category, budgetPeriod);
    if (!locked) {
      entityManager.createNativeQuery(LOCK_INSERT)
          .setParameter("accountFrom", accountFrom)
          .setParameter("expenseCategory", category.code())
          .setParameter("budgetPeriod", budgetPeriod)
          .executeUpdate();
      entityManager.flush();
      tryLock(accountFrom, category, budgetPeriod);
    }
  }

  /**
   * Пытается взять блокировку строки-замка.
   *
   * <p>Блокировка задаётся самим SQL ({@code FOR UPDATE}), а не через {@code setLockMode}: Hibernate
   * запрещает указывать режим блокировки для нативных запросов и выбрасывает
   * {@code Illegal attempt to set lock mode for a native query}.
   *
   * @return {@code true}, если строка-замок существует и теперь заблокирована
   */
  private boolean tryLock(String accountFrom, ExpenseCategory category, String budgetPeriod) {
    return !entityManager
        .createNativeQuery(LOCK_SQL)
        .setParameter("accountFrom", accountFrom)
        .setParameter("expenseCategory", category.code())
        .setParameter("budgetPeriod", budgetPeriod)
        .getResultList()
        .isEmpty();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExceededTransaction> findExceededTransactions(
      String accountFrom, Integer limit, int offset) {
    return analyticsRepository
        .findExceeded(
            accountFrom,
            limitProperties.defaultSum(),
            limitProperties.defaultCurrency(),
            BudgetPeriod.LIMIT_TIMEZONE.getId(),
            limit,
            Math.max(0, offset))
        .stream()
        .map(this::toExceeded)
        .toList();
  }

  /** Лимиты с потраченной суммой и остатком; используется клиентским API (ТЗ п.6). */
  @Override
  @Transactional(readOnly = true)
  public List<LimitWithSpent> findLimitsWithSpent(String accountFrom, BudgetPeriod period) {
    JpaLimitStore.PeriodBounds bounds = JpaLimitStore.bounds(period);
    return analyticsRepository
        .findLimitsWithSpentAmount(
            accountFrom,
            bounds.start(),
            bounds.endExclusive(),
            limitProperties.defaultSum().setScale(ExpenseLimit.USD_SCALE, RoundingMode.UNNECESSARY),
            limitProperties.defaultCurrency())
        .stream()
        .map(row -> new LimitWithSpent(
            row.getLimitId(),
            row.getAccountFrom(),
            ExpenseCategory.fromCode(row.getExpenseCategory()),
            row.getLimitSum(),
            Currency.getInstance(row.getLimitCurrency()),
            row.getLimitDatetime().atOffset(ZoneOffset.UTC),
            row.getSpentUsd(),
            row.getRemainingUsd(),
            Boolean.TRUE.equals(row.getInCurrentPeriod())))
        .toList();
  }

  private ExceededTransaction toExceeded(LimitAnalyticsQueryRepository.ExceededRow row) {
    return new ExceededTransaction(
        row.getTransactionId(),
        row.getAccountFrom(),
        row.getAccountTo(),
        Currency.getInstance(row.getCurrencyCode()),
        row.getAmount(),
        ExpenseCategory.fromCode(row.getExpenseCategory()),
        row.getOccurredAt().atOffset(ZoneOffset.UTC),
        row.getAmountUsd(),
        row.getUsdRate(),
        row.getLimitSum(),
        row.getLimitDatetime().atOffset(ZoneOffset.UTC),
        Currency.getInstance(row.getLimitCurrency()),
        row.getRunningTotalUsd());
  }

  private ExpenseTransaction toDomain(ExpenseTransactionEntity entity) {
    return mapper.toDomain(entity);
  }
}

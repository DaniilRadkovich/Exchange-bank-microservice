package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import com.idftech.exchangeservice.infra.config.LimitProperties;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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

  private final EntityManager entityManager;
  private final ExpenseTransactionJpaRepository repository;
  private final LimitAnalyticsQueryRepository analyticsRepository;
  private final LimitProperties limitProperties;

  public JpaTransactionStore(
      EntityManager entityManager,
      ExpenseTransactionJpaRepository repository,
      LimitAnalyticsQueryRepository analyticsRepository,
      LimitProperties limitProperties) {
    this.entityManager = entityManager;
    this.repository = repository;
    this.analyticsRepository = analyticsRepository;
    this.limitProperties = limitProperties;
  }

  @Override
  public ExpenseTransaction save(ExpenseTransaction transaction) {
    ensurePeriodLock(transaction.accountFrom(), transaction.category(), transaction.period().value().toString());
    ExpenseTransactionEntity entity = new ExpenseTransactionEntity(
        transaction.id(),
        transaction.accountFrom(),
        transaction.accountTo(),
        transaction.currency().getCurrencyCode(),
        transaction.amount(),
        transaction.category(),
        transaction.occurredAt().toInstant(),
        transaction.usdRate(),
        transaction.amountUsd(),
        transaction.status(),
        transaction.limitExceeded());
    return toDomain(repository.save(entity));
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
  public void updateSettlement(ExpenseTransaction transaction) {
    repository.findById(transaction.id()).ifPresent(entity -> {
      if (transaction.status() == TransactionStatus.RATE_RESOLVED) {
        entity.applySettlement(transaction.usdRate(), transaction.amountUsd(), Boolean.TRUE.equals(transaction.limitExceeded()));
      } else {
        entity.registerSettlementAttempt();
      }
    });
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExpenseTransaction> findPending(int maxAttempts, int batchSize) {
    return repository.findPending(maxAttempts, PageRequest.of(0, batchSize)).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public void incrementSettlementAttempts(UUID id) {
    repository.findById(id).ifPresent(ExpenseTransactionEntity::registerSettlementAttempt);
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
  public List<ExceededTransaction> findExceededTransactions(String accountFrom) {
    return analyticsRepository
        .findExceeded(
            accountFrom,
            limitProperties.defaultSum(),
            limitProperties.defaultCurrency(),
            limitProperties.zoneId().getId())
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
        .findLimitsWithSpentAmount(accountFrom, bounds.start(), bounds.endExclusive())
        .stream()
        .map(row -> new LimitWithSpent(
            row.getLimitId(),
            row.getAccountFrom(),
            ExpenseCategory.fromCode(row.getExpenseCategory()),
            row.getLimitSum(),
            Currency.getInstance(row.getLimitCurrency()),
            row.getLimitDatetime().atOffset(ZoneOffset.UTC),
            row.getSpentUsd(),
            row.getRemainingUsd()))
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
    return new ExpenseTransaction(
        entity.getId(),
        entity.getAccountFrom(),
        entity.getAccountTo(),
        Currency.getInstance(entity.getCurrencyCode()),
        entity.getAmount(),
        entity.getExpenseCategory(),
        entity.getOccurredAt().atOffset(ZoneOffset.UTC),
        entity.getUsdRate(),
        entity.getAmountUsd(),
        entity.getStatus(),
        entity.getLimitExceeded());
  }
}

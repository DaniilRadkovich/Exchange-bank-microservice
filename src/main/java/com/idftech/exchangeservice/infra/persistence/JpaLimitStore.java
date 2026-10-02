package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.application.port.LimitStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Адаптер порта {@link LimitStore} поверх Spring Data JPA.
 *
 * <p>Здесь и только здесь доменный тип превращается в персистентный и обратно: домен не знает про
 * Hibernate, инфраструктура не знает про правила лимитов.
 */
@Repository
@Transactional
public class JpaLimitStore implements LimitStore {

  private final ExpenseLimitJpaRepository repository;

  public JpaLimitStore(ExpenseLimitJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExpenseLimit> findLimitsInPeriod(String accountFrom, ExpenseCategory category, BudgetPeriod period) {
    PeriodBounds bounds = bounds(period);
    return repository.findLimitsInPeriod(
            accountFrom, category, bounds.start(), bounds.endExclusive()).stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ExpenseLimit> findAllByAccount(String accountFrom) {
    return repository.findAllByAccount(accountFrom).stream().map(this::toDomain).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ExpenseLimit> findAtInstant(
      String accountFrom, ExpenseCategory category, Instant limitDatetime) {
    return repository.findAtInstant(accountFrom, category, limitDatetime).map(this::toDomain);
  }

  @Override
  public ExpenseLimit save(ExpenseLimit limit) {
    ExpenseLimitEntity entity = new ExpenseLimitEntity(
        limit.id() != null ? limit.id() : UUID.randomUUID(),
        limit.accountFrom(),
        limit.category(),
        limit.limitSum(),
        limit.currency().getCurrencyCode(),
        limit.limitDatetime().toInstant());
    return toDomain(repository.save(entity));
  }

  private ExpenseLimit toDomain(ExpenseLimitEntity entity) {
    return new ExpenseLimit(
        entity.getId(),
        entity.getAccountFrom(),
        entity.getExpenseCategory(),
        entity.getLimitSum(),
        java.util.Currency.getInstance(entity.getLimitCurrency()),
        entity.getLimitDatetime().atOffset(java.time.ZoneOffset.UTC));
  }

  /** Границы месяца в часовом поясе лимитов, представленные как полуинтервал {@code [start, end)}. */
  static PeriodBounds bounds(BudgetPeriod period) {
    YearMonth yearMonth = period.value();
    Instant start = yearMonth.atDay(1).atStartOfDay(period.zoneId()).toInstant();
    Instant end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(period.zoneId()).toInstant();
    return new PeriodBounds(start, end);
  }

  record PeriodBounds(Instant start, Instant endExclusive) {}
}

package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.domain.ExpenseCategory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data репозиторий лимитов; используется адаптером {@code LimitStore}. */
public interface ExpenseLimitJpaRepository extends JpaRepository<ExpenseLimitEntity, UUID> {

  /** Лимиты пары «счёт + категория» в пределах периода, в хронологическом порядке. */
  @Query(
      """
      SELECT l FROM ExpenseLimitEntity l
      WHERE l.accountFrom = :accountFrom
        AND l.expenseCategory = :category
        AND l.limitDatetime >= :periodStart
        AND l.limitDatetime < :periodEnd
      ORDER BY l.limitDatetime
      """)
  List<ExpenseLimitEntity> findLimitsInPeriod(
      @Param("accountFrom") String accountFrom,
      @Param("category") ExpenseCategory category,
      @Param("periodStart") Instant periodStart,
      @Param("periodEnd") Instant periodEnd);

  /** Все лимиты счёта, новые первыми. */
  @Query(
      """
      SELECT l FROM ExpenseLimitEntity l
      WHERE l.accountFrom = :accountFrom
      ORDER BY l.limitDatetime DESC
      """)
  List<ExpenseLimitEntity> findAllByAccount(@Param("accountFrom") String accountFrom);

  /**
   * Лимит, установленный ровно в этот момент. Запрос точечный, а не поиск «последнего»: уникальное
   * ограничение {@code uc_expense_limit_instant} гарантирует не более одного такого лимита.
   */
  @Query(
      """
      SELECT l FROM ExpenseLimitEntity l
      WHERE l.accountFrom = :accountFrom
        AND l.expenseCategory = :category
        AND l.limitDatetime = :limitDatetime
      """)
  Optional<ExpenseLimitEntity> findAtInstant(
      @Param("accountFrom") String accountFrom,
      @Param("category") ExpenseCategory category,
      @Param("limitDatetime") Instant limitDatetime);
}

package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.TransactionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data репозиторий транзакций; используется адаптером {@code TransactionStore}. */
public interface ExpenseTransactionJpaRepository extends JpaRepository<ExpenseTransactionEntity, UUID> {

  /** Разрешённые транзакции пары «счёт + категория» за период, в хронологическом порядке. */
  @Query(
      """
      SELECT t FROM ExpenseTransactionEntity t
      WHERE t.accountFrom = :accountFrom
        AND t.expenseCategory = :category
        AND t.occurredAt >= :periodStart
        AND t.occurredAt < :periodEnd
        AND t.status = com.idftech.exchangeservice.domain.TransactionStatus.RATE_RESOLVED
      ORDER BY t.occurredAt, t.id
      """)
  List<ExpenseTransactionEntity> findResolvedInPeriod(
      @Param("accountFrom") String accountFrom,
      @Param("category") ExpenseCategory category,
      @Param("periodStart") Instant periodStart,
      @Param("periodEnd") Instant periodEnd);

  /** Транзакции, ожидающие дорасчёта, с наименьшим временем совершения. */
  @Query(
      value =
          """
          SELECT t FROM ExpenseTransactionEntity t
          WHERE t.status <> com.idftech.exchangeservice.domain.TransactionStatus.RATE_RESOLVED
            AND t.settlementAttempts < :maxAttempts
          ORDER BY t.occurredAt
          """,
      countQuery = """
          SELECT COUNT(t) FROM ExpenseTransactionEntity t
          WHERE t.status <> com.idftech.exchangeservice.domain.TransactionStatus.RATE_RESOLVED
            AND t.settlementAttempts < :maxAttempts
          """)
  Page<ExpenseTransactionEntity> findPending(
      @Param("maxAttempts") int maxAttempts, Pageable pageable);
}

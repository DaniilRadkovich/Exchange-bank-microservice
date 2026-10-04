package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

  /**
   * Разрешённые транзакции периода начиная с указанной позиции, в хронологическом порядке.
   *
   * <p>Часть периода, а не весь период: расчёт флага операции меняет флаги только тех, что стоят за ней
   * по времени, а у стоящих раньше накопленная сумма не меняется. Полная выборка на каждый расчёт
   * делала стоимость пачки квадратичной — на 800 операциях одного месяца это десятки секунд.
   *
   * <p>Позиция сравнивается построчно {@code (occurred_at, id)}, тем же порядком, что и при расчёте
   * флагов: равенство только по времени не определяет, кто раньше.
   */
  @Query(
      """
      SELECT t FROM ExpenseTransactionEntity t
      WHERE t.accountFrom = :accountFrom
        AND t.expenseCategory = :category
        AND t.occurredAt >= :periodStart
        AND t.occurredAt < :periodEnd
        AND (t.occurredAt, t.id) >= (:fromOccurredAt, :fromId)
        AND t.status = com.idftech.exchangeservice.domain.TransactionStatus.RATE_RESOLVED
      ORDER BY t.occurredAt, t.id
      """)
  List<ExpenseTransactionEntity> findResolvedInPeriodFrom(
      @Param("accountFrom") String accountFrom,
      @Param("category") ExpenseCategory category,
      @Param("periodStart") Instant periodStart,
      @Param("periodEnd") Instant periodEnd,
      @Param("fromOccurredAt") Instant fromOccurredAt,
      @Param("fromId") UUID fromId);

  /**
   * Накопленная сумма разрешённых транзакций периода строго до указанной позиции.
   *
   * <p>Суммирование делает база, а прикладной код получает одно число: перенос накопленного итога в
   * Java означал бы снова прочитать весь период.
   */
  @Query(
      """
      SELECT COALESCE(SUM(t.amountUsd), 0)
      FROM ExpenseTransactionEntity t
      WHERE t.accountFrom = :accountFrom
        AND t.expenseCategory = :category
        AND t.occurredAt >= :periodStart
        AND t.occurredAt < :periodEnd
        AND (t.occurredAt, t.id) < (:beforeOccurredAt, :beforeId)
        AND t.status = com.idftech.exchangeservice.domain.TransactionStatus.RATE_RESOLVED
      """)
  BigDecimal sumResolvedInPeriodBefore(
      @Param("accountFrom") String accountFrom,
      @Param("category") ExpenseCategory category,
      @Param("periodStart") Instant periodStart,
      @Param("periodEnd") Instant periodEnd,
      @Param("beforeOccurredAt") Instant beforeOccurredAt,
      @Param("beforeId") UUID beforeId);

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

  /**
   * Идентификаторы транзакций, взятых в дорасчёт этой задачей.
   *
   * <p>{@code FOR UPDATE SKIP LOCKED} — это и есть claim: две задачи, прочитавшие пачку одновременно,
   * не получают одних и тех же строк. Вторая пропускает заблокированные первой и берёт следующие.
   * Без блокировки две реплики сервиса (или два прохода планировщика) разбирали одну пачку дважды:
   * счётчик попыток рос вдвое быстрее, а внешний API получал двойные платные вызовы.
   */
  @Query(
      value =
          """
          SELECT id FROM expense_transaction
          WHERE status = 'PENDING' AND settlement_attempts < :maxAttempts
          ORDER BY occurred_at
          LIMIT :batchSize
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<UUID> lockPendingIds(
      @Param("maxAttempts") int maxAttempts, @Param("batchSize") int batchSize);

  /** Засчитывает попытку взятым в дорасчёт транзакциям; вызывается в той же транзакции, что и блокировка. */
  @Modifying
  @Query(
      "UPDATE ExpenseTransactionEntity t SET t.settlementAttempts = t.settlementAttempts + 1"
          + " WHERE t.id IN :ids")
  int incrementAttempts(@Param("ids") List<UUID> ids);
}

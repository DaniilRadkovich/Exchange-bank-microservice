package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.TransactionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Персистентное представление расходной транзакции.
 *
 * <p>Значение {@code limitExceeded} равно {@code null}, пока транзакция в статусе
 * {@code PENDING}: флаг появляется только после применения курса и получения суммы в USD.
 */
@Entity
@Table(name = "expense_transaction")
@Getter
@NoArgsConstructor
public class ExpenseTransactionEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "account_from", nullable = false, length = 10)
  private String accountFrom;

  @Column(name = "account_to", nullable = false, length = 10)
  private String accountTo;

  @Column(name = "currency_code", nullable = false, length = 3, columnDefinition = "bpchar(3)")
  // char(3), а не varchar: код валюты всегда ровно три знака, и CHAR не дополняет его
  // пробелами при чтении. Тип задан явно, иначе Hibernate ожидает varchar и validate падает.
  private String currencyCode;

  @Column(name = "amount", nullable = false, precision = 19, scale = 2)
  private BigDecimal amount;

  // Конвертер, а не @Enumerated: см. ExpenseLimitEntity — @Enumerated перебил бы autoApply.
  @Column(name = "expense_category", nullable = false, length = 16)
  private ExpenseCategory expenseCategory;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "usd_rate", precision = 19, scale = 10)
  private BigDecimal usdRate;

  /**
   * Сумма в USD — произведение суммы операции на курс, поэтому колонка шире {@code amount}: при курсе
   * выше единицы произведение не помещается в {@code NUMERIC(19, 2)} и запись падала бы с «numeric
   * field overflow». Ширины согласованы миграцией {@code 004-amount-usd-precision.sql}.
   */
  @Column(name = "amount_usd", precision = 30, scale = 2)
  private BigDecimal amountUsd;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private TransactionStatus status;

  @Column(name = "limit_exceeded")
  private Boolean limitExceeded;

  @Column(name = "settlement_attempts", nullable = false)
  private int settlementAttempts;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  public ExpenseTransactionEntity(
      UUID id,
      String accountFrom,
      String accountTo,
      String currencyCode,
      BigDecimal amount,
      ExpenseCategory expenseCategory,
      Instant occurredAt,
      BigDecimal usdRate,
      BigDecimal amountUsd,
      TransactionStatus status,
      Boolean limitExceeded) {
    this.id = id;
    this.accountFrom = accountFrom;
    this.accountTo = accountTo;
    this.currencyCode = currencyCode;
    this.amount = amount;
    this.expenseCategory = expenseCategory;
    this.occurredAt = occurredAt;
    this.usdRate = usdRate;
    this.amountUsd = amountUsd;
    this.status = status;
    this.limitExceeded = limitExceeded;
    this.settlementAttempts = 0;
  }

  /** Фиксирует результат расчёта: применённый курс, сумму в USD и флаг превышения. */
  public void applySettlement(BigDecimal usdRate, BigDecimal amountUsd, boolean limitExceeded) {
    this.usdRate = usdRate;
    this.amountUsd = amountUsd;
    this.limitExceeded = limitExceeded;
    this.status = TransactionStatus.RATE_RESOLVED;
  }

  /**
   * Фиксирует неудачную попытку дорасчёта.
   *
   * <p>Счётчик растёт всегда, а в {@code FAILED} транзакция переводится начиная с попытки, номер
   * которой достиг лимита. Иначе неограниченный ретрай внешнего API молча крутил бы транзакцию
   * вечно: в {@code PENDING} она выглядит как «курс вот-вот придёт», и непонятно, ждать её или
   * поднимать тревогу.
   *
   * <p>Сумма в USD и флаг превышения остаются {@code null} — CHECK-ограничение
   * {@code ck_expense_tx_resolved_consistent} требует их отсутствия для любого статуса, кроме
   * {@code RATE_RESOLVED}. Попытка вручную не теряется: {@code apply} досчитывает транзакцию и в
   * этом статусе.
   */
  public void registerSettlementAttempt(int maxAttempts) {
    this.settlementAttempts++;
    if (this.settlementAttempts >= maxAttempts) {
      this.status = TransactionStatus.FAILED;
    }
  }
}

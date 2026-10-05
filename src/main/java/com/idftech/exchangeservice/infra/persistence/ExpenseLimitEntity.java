package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.domain.ExpenseCategory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Персистентное представление месячного лимита.
 *
 * <p>Схема описана в Liquibase (schema first), поэтому сущность только отображается на таблицу:
 * {@code spring.jpa.hibernate.ddl-auto=validate}, а не {@code update}.
 */
@Entity
@Table(name = "expense_limit")
@Getter
@NoArgsConstructor
public class ExpenseLimitEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "account_from", nullable = false, length = 10)
  private String accountFrom;

  @Column(name = "expense_category", nullable = false, length = 16)
  private ExpenseCategory expenseCategory;

  @Column(name = "limit_sum", nullable = false, precision = 19, scale = 2)
  private BigDecimal limitSum;

  @Column(name = "limit_currency", nullable = false, length = 3, columnDefinition = "bpchar(3)")
  private String limitCurrency;

  @Column(name = "limit_datetime", nullable = false)
  private Instant limitDatetime;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  public ExpenseLimitEntity(
      UUID id,
      String accountFrom,
      ExpenseCategory expenseCategory,
      BigDecimal limitSum,
      String limitCurrency,
      Instant limitDatetime) {
    this.id = id;
    this.accountFrom = accountFrom;
    this.expenseCategory = expenseCategory;
    this.limitSum = limitSum;
    this.limitCurrency = limitCurrency;
    this.limitDatetime = limitDatetime;
  }
}

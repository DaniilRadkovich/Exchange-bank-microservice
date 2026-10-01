package com.idftech.exchangeservice.application.port;

import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт хранилища расходных транзакций.
 *
 * <p>Сигнатуры намеренно не раскрывают способ доступа (JPQL, нативный SQL, несколько запросов):
 * доменный и прикладной слои зависят только от этого интерфейса, а реализация в
 * {@code infra.persistence} решает, как получить данные.
 */
public interface TransactionStore {

  /**
   * Сохраняет транзакцию и при необходимости создаёт строку-замок для пары «счёт + категория +
   * месяц». Возвращает сохранённую транзакцию.
   */
  ExpenseTransaction save(ExpenseTransaction transaction);

  Optional<ExpenseTransaction> findById(UUID id);

  /**
   * Блокирует пару «счёт + категория + месяц» пессимистичной блокировкой до конца текущей транзакции
   * БД.
   *
   * <p>Это механизм корректности при многопоточности: два параллельных расчёта флага для одного
   * клиента выстраиваются в очередь, и второй видит уже обновлённый остаток лимита, а не тот же
   * самый.
   */
  void lockPeriod(String accountFrom, ExpenseCategory category, BudgetPeriod period);

  /**
   * Все разрешённые транзакции пары «счёт + категория» за период, упорядоченные по времени
   * совершения. Нужны для расчёта накопленной суммы и флага превышения лимита.
   */
  List<ExpenseTransaction> findResolvedInPeriod(
      String accountFrom, ExpenseCategory category, BudgetPeriod period);

  /** Фиксирует результат расчёта: применённый курс, сумму в USD и флаг превышения. */
  void updateSettlement(ExpenseTransaction transaction);

  /** Транзакции, ожидающие дорасчёта, не превысившие лимит попыток; используется фоновой обработкой. */
  List<ExpenseTransaction> findPending(int maxAttempts, int batchSize);

  /**
   * Транзакции счёта, превысившие лимит, вместе с параметрами превышенного лимита (ТЗ п.6).
   *
   * <p>Реализация обязана собрать результат одним SQL-запросом с JOIN, подзапросом и
   * агрегирующими функциями — это прямое требование ТЗ, а не оптимизация.
   */
  List<ExceededTransaction> findExceededTransactions(String accountFrom);

  /**
   * Лимиты счёта вместе с потраченной суммой за период и остатком.
   *
   * <p>Вторая половина требования ТЗ п.6 про агрегирующие функции и группировку: сумма расходов
   * периода считается через {@code GROUP BY} и {@code SUM}, остаток — как разность лимита и этой
   * суммы. Суммируются все разрешённые операции периода независимо от дат установки лимитов.
   */
  List<LimitWithSpent> findLimitsWithSpent(String accountFrom, BudgetPeriod period);

  /** Строка «лимит + потрачено + остаток»; формат переносится из нативного SQL без JPA-сущности. */
  record LimitWithSpent(
      UUID limitId,
      String accountFrom,
      ExpenseCategory category,
      BigDecimal limitSum,
      Currency limitCurrency,
      OffsetDateTime limitDatetime,
      BigDecimal spentUsd,
      BigDecimal remainingUsd) {}

  /** Фиксирует число попыток дорасчёта, чтобы не ретраить бесконечно. */
  void incrementSettlementAttempts(UUID id);
}

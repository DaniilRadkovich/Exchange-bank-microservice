package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.port.LimitStore;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Прикладной сервис чтения лимитов и превышений — бэкенд клиентского API (ТЗ п.6).
 */
@Service
public class LimitQueryService {

  private static final Logger log = LoggerFactory.getLogger(LimitQueryService.class);

  private final LimitStore limitStore;
  private final TransactionStore transactionStore;
  private final LimitCalculator limitCalculator;

  public LimitQueryService(LimitStore limitStore, TransactionStore transactionStore, LimitCalculator limitCalculator) {
    this.limitStore = limitStore;
    this.transactionStore = transactionStore;
    this.limitCalculator = limitCalculator;
  }

  /**
   * Транзакции, превысившие месячный лимит, вместе с параметрами превышенного лимита.
   *
   * <p>Результат собирается одним SQL-запросом с JOIN, подзапросом и агрегирующими функциями —
   * это прямое требование ТЗ п.6, а не оптимизация.
   */
  @Transactional(readOnly = true)
  public List<ExceededTransaction> findExceeded(String accountFrom) {
    log.debug("Loading exceeded transactions for account {}", accountFrom);
    return transactionStore.findExceededTransactions(accountFrom);
  }

  /** Все лимиты счёта, новые первыми. */
  @Transactional(readOnly = true)
  public List<ExpenseLimit> findAllLimits(String accountFrom) {
    return limitStore.findAllByAccount(accountFrom);
  }

  /**
   * Лимиты счёта вместе с расходом текущего месяца и остатком.
   *
   * <p>Отдельный метод вместо {@link #findAllLimits}, потому что остаток требует агрегации по
   * транзакциям периода: без неё поля {@code spent_usd} и {@code remaining_usd} в ответе были бы
   * объявлены, но всегда пусты.
   */
  @Transactional(readOnly = true)
  public List<TransactionStore.LimitWithSpent> findAllLimitsWithSpent(String accountFrom) {
    return transactionStore.findLimitsWithSpent(accountFrom, currentPeriod());
  }

  /** Текущий месячный период по часам сервиса — тот же, что и у расчёта лимитов. */
  private BudgetPeriod currentPeriod() {
    return BudgetPeriod.of(YearMonth.from(limitCalculator.now().atZoneSameInstant(BudgetPeriod.LIMIT_TIMEZONE)));
  }

  /**
   * Признак того, что транзакция превысила лимит, действовавший на момент её совершения.
   * Используется при дорасчёте транзакций в статусе {@code PENDING}.
   */
  public boolean isExceeded(ExpenseTransaction transaction, List<ExpenseTransaction> orderedPeriod) {
    List<ExpenseLimit> limits = limitStore.findLimitsInPeriod(
        transaction.accountFrom(),
        transaction.category(),
        BudgetPeriod.of(transaction.occurredAt()));
    ExpenseLimit effective = limitCalculator.effectiveLimit(transaction, limits);
    return limitCalculator.isExceeded(transaction, effective, orderedPeriod);
  }
}

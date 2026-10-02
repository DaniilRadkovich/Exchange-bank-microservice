package com.idftech.exchangeservice.application.port;

import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import java.util.List;

/** Порт хранилища месячных лимитов. */
public interface LimitStore {

  /**
   * Все лимиты пары «счёт + категория», установленные в пределах периода, в хронологическом
   * порядке. Нужны {@link com.idftech.exchangeservice.application.LimitCalculator}, чтобы выбрать
   * лимит, действовавший на момент конкретной транзакции.
   */
  List<ExpenseLimit> findLimitsInPeriod(String accountFrom, ExpenseCategory category, BudgetPeriod period);

  /** Все лимиты счёта, новые первыми. */
  List<ExpenseLimit> findAllByAccount(String accountFrom);

  ExpenseLimit save(ExpenseLimit limit);
}

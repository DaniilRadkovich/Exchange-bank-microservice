package com.idftech.exchangeservice.application.port;

import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Порт хранилища месячных лимитов. */
public interface LimitStore {

  /**
   * Все лимиты пары «счёт + категория», установленные в пределах периода, в хронологическом
   * порядке. Нужны {@link com.idftech.exchangeservice.application.LimitCalculator}, чтобы выбрать
   * лимит, действовавший на момент конкретной транзакции.
   */
  List<ExpenseLimit> findLimitsInPeriod(String accountFrom, ExpenseCategory category, BudgetPeriod period);

  /**
   * Лимит, установленный ровно в этот момент, либо пусто.
   *
   * <p>Нужен {@link com.idftech.exchangeservice.application.LimitCommandService}, чтобы не создать
   * второй лимит на тот же момент: действующий лимит ищется в SQL по значению момента, и два лимита
   * с одинаковым {@code limit_datetime} дали бы одну транзакцию дважды в ответе п.6.
   */
  Optional<ExpenseLimit> findAtInstant(String accountFrom, ExpenseCategory category, Instant limitDatetime);

  ExpenseLimit save(ExpenseLimit limit);
}

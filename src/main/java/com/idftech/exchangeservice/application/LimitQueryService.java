package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import java.time.YearMonth;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Прикладной сервис чтения лимитов и превышений — бэкенд клиентского API (ТЗ п.6).
 *
 * <p>Сервис только читает. Второй путь вычисления флага превышения здесь не нужен и раньше создавал
 * риск: {@code isExceeded} в этом классе собирал тот же ответ из лимитов и транзакций, что и
 * {@link LimitCalculator}, но вызывался из одного места и со временем разошёлся бы с ним.
 */
@Service
public class LimitQueryService {

  private static final Logger log = LoggerFactory.getLogger(LimitQueryService.class);

  /**
   * Максимальный размер страницы превышений. Без него параметр — это просто ещё один способ вытянуть
   * с сервиса неограниченную выборку, а пагинация без предела её не удерживает.
   */
  static final int MAX_PAGE_SIZE = 500;

  private final TransactionStore transactionStore;
  private final LimitCalculator limitCalculator;

  public LimitQueryService(TransactionStore transactionStore, LimitCalculator limitCalculator) {
    this.transactionStore = transactionStore;
    this.limitCalculator = limitCalculator;
  }

  /**
   * Страница транзакций, превысивших месячный лимит, вместе с параметрами превышенного лимита.
   *
   * <p>Результат собирается одним SQL-запросом с JOIN, подзапросом и агрегирующими функциями —
   * это прямое требование ТЗ п.6, а не оптимизация.
   *
   * @param limit размер страницы; {@code null} — без ограничения (потолок ответа задаёт
   *     {@link #MAX_PAGE_SIZE}, иначе клиент одним запросом увёл бы память сервиса)
   * @param offset сколько строк пропустить
   */
  /** Вся выборка: потолок страницы всё равно действует, поэтому «без ограничения» означает «до потолка». */
  @Transactional(readOnly = true)
  public List<ExceededTransaction> findExceeded(String accountFrom) {
    return findExceeded(accountFrom, null, 0);
  }

  @Transactional(readOnly = true)
  public List<ExceededTransaction> findExceeded(String accountFrom, Integer limit, int offset) {
    log.debug("Loading exceeded transactions for account {} (limit={}, offset={})", accountFrom, limit, offset);
    return transactionStore.findExceededTransactions(
        accountFrom, cap(limit), Math.max(0, offset));
  }

  /**
   * Потолок одной страницы.
   *
   * <p>Без него параметр — это просто ещё один способ вытянуть с сервиса неограниченную выборку, а
   * пагинация без предела её не удерживает.
   */
  private static Integer cap(Integer limit) {
    if (limit == null) {
      return MAX_PAGE_SIZE;
    }
    return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
  }

  /**
   * Лимиты счёта вместе с расходом текущего месяца и остатком.
   *
   * <p>Остаток требует агрегации по транзакциям периода: без неё поля {@code spent_usd} и
   * {@code remaining_usd} в ответе были бы объявлены, но всегда пусты.
   */
  @Transactional(readOnly = true)
  public List<TransactionStore.LimitWithSpent> findAllLimitsWithSpent(String accountFrom) {
    return transactionStore.findLimitsWithSpent(accountFrom, currentPeriod());
  }

  /**
   * Текущий месячный период по часам сервиса — тот же, что и у расчёта лимитов.
   *
   * <p>Без дополнительного пересчёта часового пояса: {@code now()} уже отдаёт время в
   * {@code BudgetPeriod.LIMIT_TIMEZONE}, и повторный перевод маскировал бы рассинхрон поясов.
   */
  private BudgetPeriod currentPeriod() {
    return BudgetPeriod.of(YearMonth.from(limitCalculator.now()));
  }
}

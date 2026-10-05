package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.exception.ConflictException;
import com.idftech.exchangeservice.application.port.LimitStore;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Транзакционная часть установки лимита: проверка, запись и пересчёт флагов периода.
 *
 * <h2>Зачем отдельный бин</h2>
 *
 * <p>Две технические причины, а не стилистические.
 *
 * <p>{@code @Transactional} работает через прокси, поэтому транзакционный метод нельзя вызвать из
 * того же класса: {@code LimitCommandService} обязан отдать транзакционную работу отдельному бину.
 *
 * <p>Кроме того, соединение PostgreSQL не должно открываться на потоке HTTP-запроса. Пока идёт
 * пересчёт флагов периода, соединение занято и ждёт блокировки, а число таких запросов ничем не
 * ограничено: широкая волна смен лимита съедала бы пул целиком, и первым от этого страдал бы приём
 * операций. Поэтому {@link LimitCommandService} выполняет этот компонент в общем ограниченном пуле
 * расчёта, где соединение берётся уже после того, как задача дождалась своей очереди. Граница
 * та же, что и у дорасчёта транзакций, и её проверяет {@code SettlementPoolGuard}.
 */
@Component
public class LimitChangeApplier {

  private static final Logger log = LoggerFactory.getLogger(LimitChangeApplier.class);

  private final LimitStore limitStore;
  private final TransactionStore transactionStore;
  private final SettlementApplier settlementApplier;

  public LimitChangeApplier(
      LimitStore limitStore,
      TransactionStore transactionStore,
      SettlementApplier settlementApplier) {
    this.limitStore = limitStore;
    this.transactionStore = transactionStore;
    this.settlementApplier = settlementApplier;
  }

  /**
   * Записывает новый лимит и пересчитывает флаги периода в одной транзакции.
   *
   * <p>Пересчёт внутри той же транзакции и под той же блокировкой периода — не оптимизация, а
   * условие: клиент не должен увидеть промежуточное состояние «новый лимит — старые флаги».
   * Разносить две фазы по разным транзакциям ради экономии соединения нельзя: между ними
   * «новый лимит» успел бы прочитаться вместе с не пересчитанными флагами, и при сбое пересчёта
   * лимит остался бы записанным навсегда.
   *
   * @param now момент установки лимита, уже приведённый к микросекундам вызывающим кодом
   * @param period месячный период, к которому относится лимит
   */
  @Transactional
  public ExpenseLimit apply(
      String accountFrom,
      ExpenseCategory category,
      BigDecimal limitSum,
      OffsetDateTime now,
      BudgetPeriod period) {

    transactionStore.lockPeriod(accountFrom, category, period);
    if (limitStore.findAtInstant(accountFrom, category, now.toInstant()).isPresent()) {
      throw new ConflictException(
          "limit_already_set",
          "Лимит счёта %s по категории %s уже установлен в %s: обновление лимита запрещено, новый "
                  .formatted(accountFrom, category.code(), now)
              + "лимит устанавливается отдельным запросом позже");
    }

    ExpenseLimit limit = ExpenseLimit.create(UUID.randomUUID(), accountFrom, category, limitSum, now);
    ExpenseLimit saved = limitStore.save(limit);

    settlementApplier.recalculatePeriod(accountFrom, category, period);

    log.info(
        "Limit {} USD set for account {} category {} at {}",
        limitSum,
        accountFrom,
        category.code(),
        now);
    return saved;
  }
}
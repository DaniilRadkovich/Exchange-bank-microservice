package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.exception.ConflictException;
import com.idftech.exchangeservice.application.port.LimitStore;
import com.idftech.exchangeservice.application.port.TransactionStore;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Установка нового лимита (ТЗ п.5).
 *
 * <p>Дата установки всегда берётся из бина {@code Clock}, поэтому клиент не может «отмотать» лимит в
 * прошлое или в будущее — это закрывает требование ТЗ без дополнительной валидации входных данных.
 *
 * <p>Обновление лимита не поддерживается: вместо {@code PUT} есть только {@code POST}, создающий новую
 * запись. Прежние лимиты остаются в истории, что и требуется для расчёта флагов задним числом.
 */
@Service
public class LimitCommandService {

  private static final Logger log = LoggerFactory.getLogger(LimitCommandService.class);

  private final LimitStore limitStore;
  private final TransactionStore transactionStore;
  private final SettlementApplier settlementApplier;
  private final Clock clock;

  public LimitCommandService(
      LimitStore limitStore,
      TransactionStore transactionStore,
      SettlementApplier settlementApplier,
      Clock clock) {
    this.limitStore = limitStore;
    this.transactionStore = transactionStore;
    this.settlementApplier = settlementApplier;
    this.clock = clock;
  }

  /**
   * Создаёт новый месячный лимит для пары «счёт + категория».
   *
   * <p>Несколько лимитов в пределах одного месяца допустимы — это прямо следует из сценария ТЗ, где
   * лимит 1000 USD от 01.01 сменяется лимитом 2000 USD от 10.01. Запрещено не обновлять лимит, а не
   * устанавливать новый, поэтому вместо изменения записи создаётся новая, а старая остаётся в
   * истории и участвует в расчёте флагов задним числом.
   *
   * <p>Точность суммы проверяется на границе API: {@code @Digits} в DTO отклоняет третьи знаки как
   * ошибку формата ({@code 400}). Дублировать ту же проверку здесь означало бы две реализации одного
   * правила, поэтому сервис доверяет вызывающему коду.
   */
  @Transactional
  public ExpenseLimit createLimit(String accountFrom, ExpenseCategory category, BigDecimal limitSum) {
    // PostgreSQL хранит TIMESTAMPTZ с точностью до микросекунды, а OffsetDateTime.now() выдаёт
    // наносекунды. Без приведения два запроса, отличающиеся на наносекунды, выглядели бы для БД одним
    // и тем же моментом: проверка «лимит уже установлен» их бы не увидела, а ограничение
    // uc_expense_limit_instant отклонило бы вторую запись невнятной ошибкой 409 без reason.
    OffsetDateTime now =
        OffsetDateTime.now(clock.withZone(BudgetPeriod.LIMIT_TIMEZONE)).truncatedTo(ChronoUnit.MICROS);
    BudgetPeriod period = BudgetPeriod.of(now);

    // Проверка и вставка идут под блокировкой периода: без неё два параллельных запроса прошли бы
    // проверку одновременно и оба записали бы лимит на один момент.
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

    // Флаги операций, уже рассчитанных под прежним порогом, с новым лимитом не согласуются: те же
    // суммы, другой порог. Пересчёт идёт в этой же транзакции и под той же блокировкой периода, что
    // и дорасчёт, поэтому клиент не увидит промежуточного состояния «новый лимит — старые флаги».
    settlementApplier.recalculatePeriod(accountFrom, category, period);

    log.info(
        "Limit {} USD set for account {} category {} at {}", limitSum, accountFrom, category.code(), now);
    return saved;
  }
}

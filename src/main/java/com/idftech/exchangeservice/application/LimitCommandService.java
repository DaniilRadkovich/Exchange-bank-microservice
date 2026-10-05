package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Service;

/**
 * Установка нового лимита (ТЗ п.5).
 *
 * <p>Дата установки всегда берётся из бина {@code Clock}, поэтому клиент не может «отмотать» лимит в
 * прошлое или в будущее — это закрывает требование ТЗ без дополнительной валидации входных данных.
 *
 * <p>Обновление лимита не поддерживается: вместо {@code PUT} есть только {@code POST}, создающий новую
 * запись. Прежние лимиты остаются в истории, что и требуется для расчёта флагов задним числом.
 *
 * <h2>Где открывается соединение</h2>
 *
 * <p>Метод намеренно не транзакционный, а транзакционная работа уходит в {@link LimitChangeApplier}
 * через общий ограниченный пул расчёта. Пока идёт пересчёт флагов периода, соединение с PostgreSQL
 * занято и ждёт блокировки; на потоке HTTP-запроса число таких ожиданий ничем не ограничено, и
 * волна смен лимита выедала бы пул целиком — первыми от этого страдают приём операций и чтение
 * лимитов, а не сам расчёт. В ограниченном пуле задача сначала дожидается очереди и только потом
 * берёт соединение, поэтому держать соединение во время ожидания блокировки больше чем
 * {@code exchange.settlement.parallelism} задач не может.
 *
 * <p>Аннотации {@code @Transactional} здесь быть не должно по той же причине, что и в {@code
 * TransactionIntakeService.settle}: соединение бралось бы до постановки задачи в очередь.
 */
@Service
public class LimitCommandService {

  private final LimitChangeApplier limitChangeApplier;
  private final java.util.concurrent.Executor limitChangeExecutor;
  private final Clock clock;

  public LimitCommandService(
      LimitChangeApplier limitChangeApplier,
      Clock clock,
      @org.springframework.beans.factory.annotation.Qualifier("settlementExecutor")
          java.util.concurrent.Executor limitChangeExecutor) {
    this.limitChangeApplier = limitChangeApplier;
    this.clock = clock;
    this.limitChangeExecutor = limitChangeExecutor;
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
   *
   * <p>Момент установки берётся и приводится к микросекундам до постановки задачи в очередь:
   * PostgreSQL хранит {@code TIMESTAMPTZ} с точностью до микросекунды, а {@code OffsetDateTime.now()}
   * выдаёт наносекунды. Без приведения два запроса, отличающиеся на наносекунды, выглядели бы для БД
   * одним и тем же моментом: проверка «лимит уже установлен» их бы не увидела, а ограничение
   * {@code uc_expense_limit_instant} отклонило бы вторую запись невнятной ошибкой 409 без reason.
   */
  public ExpenseLimit createLimit(String accountFrom, ExpenseCategory category, BigDecimal limitSum) {
    OffsetDateTime now =
        OffsetDateTime.now(clock.withZone(BudgetPeriod.LIMIT_TIMEZONE)).truncatedTo(ChronoUnit.MICROS);
    BudgetPeriod period = BudgetPeriod.of(now);

    return awaitLimitChange(
        () -> limitChangeApplier.apply(accountFrom, category, limitSum, now, period));
  }

  /**
   * Выполняет смену лимита в ограниченном пуле и разворачивает исключение задачи.
   *
   * <p>{@code join()} оборачивает всё в {@code CompletionException}, и без разворачивания обработчик
   * ошибок увидел бы обёртку вместо {@code ConflictException}: клиент получил бы 500 вместо 409 на
   * «лимит уже установлен».
   */
  private ExpenseLimit awaitLimitChange(
      java.util.function.Supplier<ExpenseLimit> limitChange) {
    try {
      return CompletableFuture.supplyAsync(limitChange, limitChangeExecutor).join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error fatal) {
        throw fatal;
      }
      throw e;
    }
  }
}

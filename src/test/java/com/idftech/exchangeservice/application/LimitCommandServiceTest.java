package com.idftech.exchangeservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idftech.exchangeservice.application.exception.ConflictException;
import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Смена лимита занимает соединение не на потоке HTTP-запроса.
 *
 * <p>Пока идёт пересчёт флагов периода, соединение с PostgreSQL занято и ждёт блокировки периода. На
 * потоке запроса число таких ожиданий ничем не ограничено, и волна смен лимита съедала бы пул целиком:
 * первыми от этого переставали работать приём операций и чтение лимитов, а не сам расчёт. Поэтому
 * транзакционная работа ушла в {@link LimitChangeApplier} и выполняется в общем ограниченном пуле
 * расчёта, где задача сначала дожидается очереди и только потом берёт соединение.
 *
 * <p>Пересчёт при этом остался в той же транзакции, что и запись лимита: разносить их значило бы
 * показать клиенту промежуточное состояние «новый лимит — старые флаги», а при сбое пересчёта лимит
 * остался бы записанным навсегда.
 */
class LimitCommandServiceTest {

  private static final String ACCOUNT = "0000000123";
  private static final OffsetDateTime NOW = OffsetDateTime.parse("2022-01-10T12:00:00.123456Z");

  private final LimitChangeApplier applier = mock(LimitChangeApplier.class);
  private final LimitCommandService service =
      new LimitCommandService(applier, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), Runnable::run);

  @Test
  @DisplayName("Транзакционная работа уходит аппликатору, а не выполняется в сервисе")
  void limitChangeIsDelegatedToTransactionalApplier() {
    ExpenseLimit expected = limit();
    when(applier.apply(any(), any(), any(), any(), any())).thenReturn(expected);

    ExpenseLimit created = service.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("2000.00"));

    assertThat(created).isSameAs(expected);
    verify(applier)
        .apply(
            eq(ACCOUNT),
            eq(ExpenseCategory.PRODUCT),
            eq(new BigDecimal("2000.00")),
            eq(NOW),
            eq(BudgetPeriod.of(NOW)));
  }

  @Test
  @DisplayName("Конфликт доходит до вызывающего без CompletionException")
  void conflictReachesTheCallerUnwrapped() {
    // join() оборачивает всё в CompletionException: без разворачивания обработчик ошибок увидел бы
    // обёртку и отдал бы клиенту 500 вместо 409.
    when(applier.apply(any(), any(), any(), any(), any()))
        .thenThrow(new ConflictException("limit_already_set", "Лимит уже установлен"));

    assertThatThrownBy(
            () -> service.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("2000.00")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Лимит уже установлен");
  }

  @Test
  @DisplayName("Момент установки приводится к микросекундам до записи в БД")
  void installationInstantIsTruncatedToMicros() {
    OffsetDateTime withNanos = NOW.plusNanos(999);

    service.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("2000.00"));

    verify(applier).apply(any(), any(), any(), eq(withNanos.truncatedTo(ChronoUnit.MICROS)), any());
  }

  @Test
  @DisplayName("Сервис не транзакционный, аппликатор — да")
  void transactionLivesInTheApplierOnly() throws NoSuchMethodException {
    // Аннотация на сервисе означала бы, что соединение берётся до постановки задачи в очередь, —
    // ровно то, ради чего работа ушла в аппликатор.
    assertThat(
            LimitCommandService.class
                .getMethod("createLimit", String.class, ExpenseCategory.class, BigDecimal.class)
                .isAnnotationPresent(Transactional.class))
        .isFalse();
    assertThat(
            LimitChangeApplier.class
                .getMethod(
                    "apply",
                    String.class,
                    ExpenseCategory.class,
                    BigDecimal.class,
                    OffsetDateTime.class,
                    BudgetPeriod.class)
                .isAnnotationPresent(Transactional.class))
        .isTrue();
  }

  private static ExpenseLimit limit() {
    return ExpenseLimit.create(
        java.util.UUID.randomUUID(),
        ACCOUNT,
        ExpenseCategory.PRODUCT,
        new BigDecimal("2000.00"),
        NOW.truncatedTo(ChronoUnit.MICROS));
  }
}
package com.idftech.exchangeservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Первая фаза дорасчёта обязана различать «курса нет» и «получение сломалось».
 *
 * <p>Раньше отказ задачи превращался в пустой курс, и сбой записи курса в нашу БД попадал в лог как
 * «курс недоступен»: нарушение ограничения нашей схемы выглядело как недоступность биржи, и транзакция
 * уходила в {@code FAILED} с неверной причиной.
 *
 * <p>Границу между «виноват провайдер» и «виноват сервис» проводит {@link ExchangeRateService}: пустой
 * результат означает первое, исключение — второе. Резолвер не имеет права это стирать.
 */
class ParallelRateResolverTest {

  private static final String ACCOUNT = "0000000460";
  private static final LocalDate DATE = LocalDate.parse("2022-01-10");
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();

  private ExchangeRateService exchangeRateService;
  private ParallelRateResolver resolver;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    exchangeRateService = mock(ExchangeRateService.class);
    resolver = new ParallelRateResolver(exchangeRateService);
    executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  @DisplayName("Сбой записи курса в БД — это Failed, а не «курс недоступен»")
  void serviceFailureIsNotReportedAsUnavailableRate() {
    DataIntegrityViolationException brokenCache =
        new DataIntegrityViolationException("ck_exchange_rate_close_rate");
    when(exchangeRateService.resolveUsdRate(eq("KZT"), eq(DATE))).thenThrow(brokenCache);

    RateResolution resolution = resolve(pending("KZT", DATE));

    assertThat(resolution).isInstanceOf(RateResolution.Failed.class);
    assertThat(((RateResolution.Failed) resolution).cause()).isSameAs(brokenCache);
  }

  @Test
  @DisplayName("Пустой результат провайдера остаётся «курс недоступен»")
  void emptyProviderResultStaysUnavailable() {
    when(exchangeRateService.resolveUsdRate(eq("KZT"), eq(DATE))).thenReturn(Optional.empty());

    RateResolution resolution = resolve(pending("KZT", DATE));

    assertThat(resolution).isInstanceOf(RateResolution.Unavailable.class);
  }

  @Test
  @DisplayName("Полученный курс доходит до транзакции без искажений")
  void resolvedRateIsCarriedToTransaction() {
    BigDecimal rate = new BigDecimal("0.0025");
    when(exchangeRateService.resolveUsdRate(eq("KZT"), eq(DATE))).thenReturn(Optional.of(rate));

    RateResolution resolution = resolve(pending("KZT", DATE));

    assertThat(resolution).isInstanceOf(RateResolution.Resolved.class);
    assertThat(((RateResolution.Resolved) resolution).rate()).isEqualTo(rate);
  }

  @Test
  @DisplayName("Сбой по одной паре не отменяет курсы по другим")
  void brokenPairDoesNotAffectOtherCurrencies() {
    LocalDate nextDay = DATE.plusDays(1);
    when(exchangeRateService.resolveUsdRate(eq("KZT"), eq(DATE)))
        .thenThrow(new DataIntegrityViolationException("ck_exchange_rate_close_rate"));
    when(exchangeRateService.resolveUsdRate(eq("EUR"), eq(nextDay)))
        .thenReturn(Optional.of(new BigDecimal("1.08")));

    ExpenseTransaction broken = pending("KZT", DATE);
    ExpenseTransaction healthy = pending("EUR", nextDay);
    Map<UUID, RateResolution> result = resolveAll(broken, healthy);

    assertThat(result.get(broken.id())).isInstanceOf(RateResolution.Failed.class);
    assertThat(result.get(healthy.id())).isInstanceOf(RateResolution.Resolved.class);
  }

  @Test
  @DisplayName("Одинаковые пары «валюта + дата» дедуплицируются")
  void sameCurrencyAndDateShareOneResolution() {
    when(exchangeRateService.resolveUsdRate(eq("KZT"), eq(DATE)))
        .thenReturn(Optional.of(new BigDecimal("0.0025")));

    ExpenseTransaction first = pending("KZT", DATE);
    ExpenseTransaction second = pending("KZT", DATE);
    Map<UUID, RateResolution> result = resolveAll(first, second);

    assertThat(result).hasSize(2);
    assertThat(result.values()).allMatch(RateResolution.Resolved.class::isInstance);
    verify(exchangeRateService).resolveUsdRate(eq("KZT"), eq(DATE));
  }

  @Test
  @DisplayName("Пустая пачка не порождает ни одного запроса")
  void emptyBatchMakesNoRequests() {
    assertThat(resolveAll()).isEmpty();
    verifyNoInteractions(exchangeRateService);
  }

  @Test
  @DisplayName("Исчершение памяти не считается неудачной попыткой дорасчёта")
  void fatalErrorIsNotSwallowedAsRateFailure() {
    when(exchangeRateService.resolveUsdRate(any(), any()))
        .thenThrow(new OutOfMemoryError("no room for the rate cache"));

    assertThatThrownBy(() -> resolve(pending("KZT", DATE))).isInstanceOf(OutOfMemoryError.class);
  }

  private RateResolution resolve(ExpenseTransaction transaction) {
    return resolveAll(transaction).get(transaction.id());
  }

  private Map<UUID, RateResolution> resolveAll(ExpenseTransaction... transactions) {
    return resolver.resolveRates(
        List.of(transactions),
        transaction -> transaction.occurredAt().toLocalDate(),
        executor);
  }

  private ExpenseTransaction pending(String currencyCode, LocalDate rateDate) {
    return new ExpenseTransaction(
        nextId(),
        ACCOUNT,
        "0000009999",
        Currency.getInstance(currencyCode),
        new BigDecimal("100.00"),
        ExpenseCategory.PRODUCT,
        OffsetDateTime.of(rateDate.atTime(12, 0), ZoneOffset.UTC),
        null,
        null,
        TransactionStatus.PENDING,
        null);
  }

  private static UUID nextId() {
    return new UUID(0, ID_SEQUENCE.incrementAndGet());
  }
}
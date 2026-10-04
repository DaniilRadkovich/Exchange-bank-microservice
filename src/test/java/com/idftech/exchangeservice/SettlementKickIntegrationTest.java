package com.idftech.exchangeservice;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import io.restassured.RestAssured;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Досчёт по событию приёма: флаг {@code limit_exceeded} появляется сразу, без ожидания планового
 * прохода.
 *
 * <p>Отдельный контекст нужен именно ради этого: в обычном тестовом профиле фоновая обработка
 * выключена ({@code exchange.settlement.processing-enabled=false}), иначе результат проверки зависел
 * бы от фоновой задачи. Здесь она включена, а пауза планировщика — час. Поэтому рассчитать
 * транзакцию может только событие приёма: плановый проход в пределах теста не наступит, и утверждение
 * «посчитано за секунды» проверяет именно kick, а не совпадение с таймером.
 *
 * <p>Ожидание здесь — граница с дедлайном, а не {@code Thread.sleep} с фиксированной паузой: ждать
 * асинхронную задачу и есть смысл проверки. В остальных тестах проекта расчёт вызывается явно, и
 * фиксированных пауз в них нет.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "exchange.settlement.processing-enabled=true",
      "exchange.settlement.retry-delay=1h"
    })
@ActiveProfiles("test")
class SettlementKickIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000123";
  private static final String COUNTERPARTY = "0000009999";

  /** 100 000 KZT по курсу 0.0025 — ровно 250 USD. */
  private static final String SUM_KZT = "100000.00";

  private static final BigDecimal CLOSE_RATE = new BigDecimal("0.0025");

  /** Лимит по умолчанию 1000 USD: превышение начинается с пятой операции по 250 USD. */
  private static final int EXCEEDED_FROM_OPERATION = 5;

  private static final int OPERATIONS = 10;

  @Autowired
  private TransactionIntakeService intakeService;

  @BeforeEach
  void setUpRestAssured() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
  }

  @Test
  @DisplayName("Флаг limit_exceeded появляется сразу после приёма, а не через retry-delay")
  void acceptedTransactionIsSettledWithoutWaitingForTheScheduledPass() {
    stubRate("KZT", CLOSE_RATE);

    // 500 000 KZT по курсу 0.0025 — 1250 USD против лимита по умолчанию 1000 USD.
    String id = postTransaction("500000.00", "2022-01-10T00:00:00Z");

    ExpenseTransaction settled = awaitResolved(UUID.fromString(id));

    assertThat(settled.status()).isEqualTo(TransactionStatus.RATE_RESOLVED);
    assertThat(settled.usdRate()).isEqualByComparingTo("0.0025");
    assertThat(settled.amountUsd()).isEqualByComparingTo("1250.00");
    assertThat(settled.limitExceeded()).isTrue();
    // Один запрос к платному API: курс попал в собственный кэш и в тот же проход.
    assertThat(rateApiCallCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Пачка приёмов считается целиком: кумулятивные флаги не теряются и не дублируются")
  void burstOfAcceptedTransactionsIsSettledCompletely() {
    stubRate("KZT", CLOSE_RATE);

    List<UUID> ids = new ArrayList<>();
    for (int operation = 1; operation <= OPERATIONS; operation++) {
      // Разная минута в каждой операции: порядок по времени однозначен, и ожидаемый флаг
      // каждой операции можно назвать, а не вычислить по порядку идентификаторов.
      ids.add(UUID.fromString(postTransaction(SUM_KZT, "2022-01-10T00:%02d:00Z".formatted(operation))));
    }

    ids.forEach(this::awaitResolved);

    // 10 операций по 250 USD — 2500 USD против лимита 1000 USD. Остаток ровно 0 (четвёртая
    // операция) превышением не считается, поэтому превышены операции с пятой по десятую.
    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("", hasSize(OPERATIONS - EXCEEDED_FROM_OPERATION + 1))
        .body("[0].datetime", equalTo("2022-01-10T00:05:00Z"))
        .body("[0].amount_usd", equalTo(250.00f))
        .body("[0].limit_sum", equalTo(1000.00f))
        .body("[0].limit_currency_shortname", equalTo("USD"))
        .body("[5].datetime", equalTo("2022-01-10T00:10:00Z"))
        .body("[5].spent_usd", equalTo(2500.00f));

    // Приёмы не плодили платные запросы: дедупликация работает внутри прохода, а между проходами
    // курс уже лежит в собственной БД (ТЗ п.3) — поэтому внешний API опрошен ровно один раз.
    assertThat(rateApiCallCount())
        .as("десять операций одной пары «валюта + дата» — один запрос к внешнему API")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Недоступный курс не мешает приёму: транзакция ждёт следующего прохода")
  void unavailableRateKeepsTransactionPendingInsteadOfFailingTheIntake() {
    stubRateProviderFailure();

    String id = postTransaction(SUM_KZT, "2022-01-10T00:00:00Z");

    // Приём ответил 202 (это проверяет postTransaction), а транзакция осталась PENDING: попытка
    // досчёта потрачена, данные не потеряны, FAILED достижим только после исчерпания попыток.
    awaitAttemptCounted(UUID.fromString(id));
    assertThat(intakeService.findById(UUID.fromString(id)).orElseThrow().status())
        .isEqualTo(TransactionStatus.PENDING);
  }

  private String postTransaction(String sum, String datetime) {
    Map<String, Object> body = new HashMap<>();
    body.put("account_from", ACCOUNT);
    body.put("account_to", COUNTERPARTY);
    body.put("currency_shortname", "KZT");
    body.put("sum", new BigDecimal(sum));
    body.put("expense_category", "product");
    body.put("datetime", datetime);

    return given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/transactions")
        .then()
        .statusCode(202)
        .extract()
        .path("transaction_id");
  }

  /** Ждёт расчёта транзакции фоновым проходом; дедлайн вместо фиксированной паузы. */
  private ExpenseTransaction awaitResolved(UUID id) {
    return await(id, transaction -> transaction.isResolved());
  }

  /** Ждёт, пока проход потратит попытку: значит, фоновая обработка дошла до транзакции. */
  private void awaitAttemptCounted(UUID id) {
    await(
        id,
        transaction ->
            jdbcTemplate.queryForObject(
                    "SELECT settlement_attempts FROM expense_transaction WHERE id = ?",
                    Integer.class,
                    id)
                > 0);
  }

  private ExpenseTransaction await(
      UUID transactionId, java.util.function.Predicate<ExpenseTransaction> condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    ExpenseTransaction current = intakeService.findById(transactionId).orElseThrow();
    while (System.nanoTime() < deadline) {
      if (condition.test(current)) {
        return current;
      }
      LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
      current = intakeService.findById(transactionId).orElseThrow();
    }
    throw new AssertionError(
        "Transaction %s was not processed by the background pass within 30 s, status is %s"
            .formatted(transactionId, current.status()));
  }
}

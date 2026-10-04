package com.idftech.exchangeservice;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import com.idftech.exchangeservice.application.LimitCommandService;
import com.idftech.exchangeservice.application.TransactionIntakeService;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import com.idftech.exchangeservice.domain.TransactionStatus;
import io.restassured.RestAssured;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * REST-слой целиком: настоящий HTTP-порт, настоящий JSON, настоящая БД.
 *
 * <p>Проверяется контракт, который видит клиент: коды ответов, имена полей в snake_case из ТЗ,
 * формат ошибок {@code application/problem+json} и то, что асинхронный приём действительно отвечает
 * сразу, не дожидаясь внешнего API.
 */
class ExpenseApiIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000123";
  private static final String COUNTERPARTY = "0000009999";
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();

  /** Предел попыток дорасчёта из application-test.yaml: столько нужно, чтобы дойти до FAILED. */
  private static final int MAX_ATTEMPTS = 5;

  @Autowired
  private TransactionIntakeService intakeService;

  @Autowired
  private LimitCommandService limitCommandService;

  @BeforeEach
  void setUpRestAssured() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
  }

  @Test
  @DisplayName("POST /transactions отвечает 202 и статусом PENDING, не дожидаясь внешнего API")
  void postTransactionIsAcceptedAsynchronously() {
    // Внешний API не заглушен: приём всё равно должен пройти мгновенно.
    Map<String, Object> body = transactionBody("1000.00", "product", "2022-01-02T10:00:00Z");

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/transactions")
        .then()
        .statusCode(202)
        .body("status", equalTo("PENDING"))
        .body("limit_exceeded", nullValue())
        .body("amount_usd", nullValue())
        .body("transaction_id", notNullValue())
        .body("account_from", equalTo(ACCOUNT))
        .body("currency_shortname", equalTo("USD"));
  }

  @Test
  @DisplayName("Поля ответа используют имена из ТЗ")
  void responseUsesFieldNamesFromSpec() {
    String transactionId = postTransaction("1000.00", "product", "2022-01-02T10:00:00Z");

    given()
        .when()
        .get("/api/v1/transactions/" + transactionId)
        .then()
        .statusCode(200)
        .body("transaction_id", equalTo(transactionId))
        .body("account_from", equalTo(ACCOUNT))
        .body("account_to", equalTo(COUNTERPARTY))
        .body("currency_shortname", equalTo("USD"))
        .body("sum", equalTo(1000.00f))
        .body("expense_category", equalTo("product"))
        .body("status", equalTo("PENDING"));
  }

  @Test
  @DisplayName("GET /limits/exceeded отдаёт превышения с параметрами лимита (ТЗ п.6)")
  void exceededEndpointReturnsLimitDetails() {
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("1000.00"));
    settleInDatabase("2022-01-02", "500.00");
    settleInDatabase("2022-01-03", "600.00");

    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].limit_sum", equalTo(1000.00f))
        .body("[0].limit_currency_shortname", equalTo("USD"))
        .body("[0].amount_usd", equalTo(600.00f))
        .body("[0].spent_usd", equalTo(1100.00f))
        .body("[0].exceeded_by_usd", equalTo(100.00f))
        .body("[0].expense_category", equalTo("product"));
  }

  @Test
  @DisplayName("GET /limits/exceeded для счёта без превышений возвращает пустой массив")
  void exceededEndpointReturnsEmptyArray() {
    given()
        .queryParam("account_from", "0000000777")
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("$", hasSize(0));
  }

  @Test
  @DisplayName("POST /limits возвращает 201 и созданный лимит с датой от сервиса")
  void postLimitReturnsCreated() {
    testClock.set(utc("2022-01-10").plusHours(10));

    given()
        .contentType("application/json")
        .body(Map.of("account_from", ACCOUNT, "expense_category", "service", "limit_sum", 2000.00))
        .when()
        .post("/api/v1/limits")
        .then()
        .statusCode(201)
        .body("limit_sum", equalTo(2000.00f))
        .body("limit_currency_shortname", equalTo("USD"))
        .body("expense_category", equalTo("service"))
        .body("limit_datetime", notNullValue())
        .body("limit_id", notNullValue());
  }

  @Test
  @DisplayName("GET /limits возвращает лимиты новыми первыми")
  void limitsAreListedNewestFirst() {
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("1000.00"));
    testClock.set(utc("2022-01-10").plusHours(10));
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("2000.00"));

    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits")
        .then()
        .statusCode(200)
        .body("$", hasSize(2))
        .body("[0].limit_sum", equalTo(2000.00f))
        .body("[1].limit_sum", equalTo(1000.00f));
  }

  @Test
  @DisplayName("GET /limits показывает лимит по умолчанию для пары без установленного лимита")
  void defaultLimitIsShownForPairWithoutInstalledLimit() {
    settleInDatabase("2022-01-05", "600.00");
    settleInDatabase("2022-01-06", "500.00");

    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        // Лимита по умолчанию нет в базе, поэтому и идентификатора нет: выдумывать его нельзя.
        .body("[0].limit_id", nullValue())
        .body("[0].account_from", equalTo(ACCOUNT))
        .body("[0].expense_category", equalTo("product"))
        .body("[0].limit_sum", equalTo(1000.00f))
        .body("[0].limit_currency_shortname", equalTo("USD"))
        // Момент установки лимита по умолчанию — начало месяца в зоне лимитов.
        .body("[0].limit_datetime", equalTo("2022-01-01T00:00:00Z"))
        .body("[0].spent_usd", equalTo(1100.00f))
        .body("[0].remaining_usd", equalTo(-100.00f));
  }

  @Test
  @DisplayName("Лимит по умолчанию не дублирует установленный и добавляется по паре")
  void defaultLimitIsAddedOnlyForPairWithoutInstalledLimit() {
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("2000.00"));
    settleInDatabase("2022-01-05", "600.00");
    settleInDatabase(ExpenseCategory.SERVICE, "2022-01-06", "300.00");

    // Новые первыми: установленный лимит от 01.01 10:00 новее лимита по умолчанию от 01.01 00:00.
    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits")
        .then()
        .statusCode(200)
        .body("$", hasSize(2))
        .body("[0].limit_id", notNullValue())
        .body("[0].expense_category", equalTo("product"))
        .body("[0].limit_sum", equalTo(2000.00f))
        .body("[0].spent_usd", equalTo(600.00f))
        .body("[0].remaining_usd", equalTo(1400.00f))
        .body("[1].limit_id", nullValue())
        .body("[1].expense_category", equalTo("service"))
        .body("[1].limit_sum", equalTo(1000.00f))
        .body("[1].spent_usd", equalTo(300.00f))
        .body("[1].remaining_usd", equalTo(700.00f));
  }

  @Test
  @DisplayName("Лимит прошлого месяца остаётся в списке, но без расхода и остатка")
  void limitOfPreviousMonthIsListedWithoutCurrentMonthNumbers() {
    // История лимитов нужна клиенту, а вот её числа за текущий месяц — враньё: в феврале январский
    // лимит 1000 USD выглядел бы нетронутым, и это противоречило бы превышению по январским же
    // операциям в GET /limits/exceeded. Поэтому у строки прошлого месяца расход и остаток null,
    // а in_current_period отличает её от действующей.
    testClock.set(utc("2022-01-01").plusHours(10));
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("1000.00"));
    settleInDatabase("2022-01-20", "1500.00");

    testClock.set(utc("2022-02-10").plusHours(10));
    settleInDatabase("2022-02-05", "100.00");

    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits")
        .then()
        .statusCode(200)
        // Две строки: февральский лимит по умолчанию (в феврале есть расход, а своего лимита нет)
        // и январский лимит в истории — без чисел за текущий месяц.
        .body("$", hasSize(2))
        .body("[0].limit_datetime", equalTo("2022-02-01T00:00:00Z"))
        .body("[0].spent_usd", equalTo(100.00f))
        .body("[0].in_current_period", equalTo(true))
        .body("[1].limit_datetime", equalTo("2022-01-01T10:00:00Z"))
        .body("[1].spent_usd", nullValue())
        .body("[1].remaining_usd", nullValue())
        .body("[1].in_current_period", equalTo(false));
  }

  @Test
  @DisplayName("Превышения отдаются постранично: limit и offset не теряют и не дублируют строки")
  void exceededTransactionsArePaged() {
    testClock.set(utc("2022-01-01").plusHours(10));
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("1000.00"));
    for (int day = 1; day <= 5; day++) {
      settleInDatabase("2022-01-%02d".formatted(day), "300.00");
    }
    // Первые три операции по 300 дают 900 — без превышения; с четвёртой идёт 1200, и дальше сумма
    // только растёт, то есть превышены операции с 4-го по 5-й.

    given()
        .queryParam("account_from", ACCOUNT)
        .queryParam("limit", 1)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].datetime", equalTo("2022-01-04T00:00:00Z"));

    given()
        .queryParam("account_from", ACCOUNT)
        .queryParam("limit", 1)
        .queryParam("offset", 1)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].datetime", equalTo("2022-01-05T00:00:00Z"));

    // Без параметров — вся выборка, как и до появления пагинации.
    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("$", hasSize(2));
  }

  @Test
  @DisplayName("Неположительный limit отклоняется как ошибка формата")
  void nonPositivePageSizeIsRejected() {
    given()
        .queryParam("account_from", ACCOUNT)
        .queryParam("limit", 0)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(400)
        .contentType("application/problem+json");
  }

  @Test
  @DisplayName("Лимит прошлого месяца не поглощает расход текущего: остаток совпадает с флагом")
  void previousMonthLimitDoesNotAbsorbCurrentMonthSpending() {
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal("5000.00"));

    // Часы уезжают в февраль: лимит января в феврале уже не действует — операция считается по
    // умолчанию 1000 USD, и ответ лимитов обязан говорить о том же лимите, что и флаг превышения.
    testClock.set(utc("2022-02-10").plusHours(10));
    settleInDatabase("2022-02-05", "1100.00");

    // Новые первыми: дефолт февраля от 01.02 новее январского лимита от 01.01.
    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits")
        .then()
        .statusCode(200)
        .body("$", hasSize(2))
        .body("[0].limit_id", nullValue())
        .body("[0].limit_sum", equalTo(1000.00f))
        .body("[0].limit_datetime", equalTo("2022-02-01T00:00:00Z"))
        .body("[0].spent_usd", equalTo(1100.00f))
        .body("[0].remaining_usd", equalTo(-100.00f))
        .body("[0].in_current_period", equalTo(true))
        // Январский лимит остаётся в истории, но февральский расход к нему не присоединяется, а его
        // собственные числа за январь в ответ не попадают: null вместо нуля, иначе клиент прочитал бы
        // «осталось 5000» и «февраль ничего не потратил» там, где январь уже превышен.
        .body("[1].limit_id", notNullValue())
        .body("[1].limit_sum", equalTo(5000.00f))
        .body("[1].limit_datetime", equalTo("2022-01-01T10:00:00Z"))
        .body("[1].spent_usd", nullValue())
        .body("[1].remaining_usd", nullValue())
        .body("[1].in_current_period", equalTo(false));

    // Тот же лимит в ответе о превышениях: 1000, а не 5000.
    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].limit_sum", equalTo(1000.00f))
        .body("[0].spent_usd", equalTo(1100.00f))
        .body("[0].exceeded_by_usd", equalTo(100.00f));
  }

  @Test
  @DisplayName("Счёт без лимитов и без расходов не получает лимит по умолчанию")
  void accountWithoutSpendingAndLimitsHasNoRows() {
    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits")
        .then()
        .statusCode(200)
        .body("$", hasSize(0));
  }

  @Test
  @DisplayName("Некорректный номер счёта отклоняется с 400 и ProblemDetail")
  void invalidAccountNumberIsRejected() {
    Map<String, Object> body = transactionBody("100.00", "product", "2022-01-02T10:00:00Z");
    body.put("account_from", "12345");

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/transactions")
        .then()
        .statusCode(400)
        .contentType("application/problem+json")
        .body("status", equalTo(400))
        .body("title", notNullValue())
        .body("type", notNullValue())
        .body("errors", notNullValue());
  }

  @Test
  @DisplayName("Отрицательная сумма отклоняется с 400")
  void nonPositiveAmountIsRejected() {
    Map<String, Object> body = transactionBody("-100.00", "product", "2022-01-02T10:00:00Z");

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/transactions")
        .then()
        .statusCode(400)
        .contentType("application/problem+json");
  }

  @Test
  @DisplayName("Неизвестная категория отклоняется с 400")
  void unknownCategoryIsRejected() {
    Map<String, Object> body = transactionBody("100.00", "subscription", "2022-01-02T10:00:00Z");

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/transactions")
        .then()
        .statusCode(400)
        .contentType("application/problem+json");
  }

  @Test
  @DisplayName("Нечитаемый JSON отклоняется с 400, а не с 500")
  void malformedJsonIsRejectedAsBadRequest() {
    given()
        .contentType("application/json")
        .body("{not json")
        .when()
        .post("/api/v1/transactions")
        .then()
        .statusCode(400)
        .contentType("application/problem+json");
  }

  @Test
  @DisplayName("Лимит с тремя знаками после точки отклоняется с 400 как ошибка формата")
  void limitWithExcessivePrecisionIsBadRequest() {
    given()
        .contentType("application/json")
        .body(Map.of("account_from", ACCOUNT, "expense_category", "product", "limit_sum", 1000.123))
        .when()
        .post("/api/v1/limits")
        .then()
        .statusCode(400)
        .contentType("application/problem+json")
        .body("errors", notNullValue());
  }

  @Test
  @DisplayName("Синтаксически верный, но несуществующий код валюты отклоняется с 422")
  void unknownCurrencyCodeIsUnprocessable() {
    Map<String, Object> body = transactionBody("100.00", "product", "2022-01-02T10:00:00Z");
    body.put("currency_shortname", "QQQ");

    given()
        .contentType("application/json")
        .body(body)
        .when()
        .post("/api/v1/transactions")
        .then()
        .statusCode(422)
        .contentType("application/problem+json")
        .body("reason", equalTo("unknown_currency"));
  }

  @Test
  @DisplayName("Запрос несуществующей транзакции отвечает 404 с ProblemDetail")
  void unknownTransactionReturnsNotFound() {
    given()
        .when()
        .get("/api/v1/transactions/" + UUID.randomUUID())
        .then()
        .statusCode(404)
        .contentType("application/problem+json")
        .body("status", equalTo(404))
        .body("resource", equalTo("transaction"));
  }

  @Test
  @DisplayName("Повторная установка лимита на тот же момент отвечает 409 с reason")
  void repeatedLimitAtSameInstantConflicts() {
    Map<String, Object> limitBody =
        Map.of("account_from", ACCOUNT, "expense_category", "product", "limit_sum", 1000.00);

    given().contentType("application/json").body(limitBody)
        .when().post("/api/v1/limits").then().statusCode(201);

    // Сумма другая, а момент установки — тот же: клиент хотел не «обновить», а поставить второй лимит
    // на уже занятое время. Это конфликт, а не ошибка формата.
    given().contentType("application/json")
        .body(Map.of("account_from", ACCOUNT, "expense_category", "product", "limit_sum", 2000.00))
        .when().post("/api/v1/limits")
        .then()
        .statusCode(409)
        .contentType("application/problem+json")
        .body("reason", equalTo("limit_already_set"));
  }

  @Test
  @DisplayName("Спецификация OpenAPI описывает все методы клиентского API")
  void openApiDocumentIsPublished() {
    String document =
        given().when().get("/v3/api-docs").then().statusCode(200).extract().asString();

    // Ключи путей содержат слэши, поэтому проверяются по подстроке, а не JsonPath.
    assertThat(document).contains("\"/api/v1/transactions\"");
    assertThat(document).contains("\"/api/v1/limits\"");
    assertThat(document).contains("\"/api/v1/limits/exceeded\"");
  }

  @Test
  @DisplayName("Не-Uuid в transaction_id отклоняется с 400, а не подменяется сгенерированным")
  void nonUuidTransactionIdIsBadRequest() {
    // Подмена идентификатора ломала идемпотентность: потерянный ответ банка приводил к повторной
    // доставке, та создавала вторую запись, и месячный расход удваивался. Молча подставлять UUID
    // нельзя — клиент должен узнать, что его идентификатор сервис не понимает.
    Map<String, Object> body = new HashMap<>(transactionBody("100.00", "product", "2022-01-02T10:00:00Z"));
    body.put("transaction_id", "TXN-000471");

    given().contentType("application/json").body(body)
        .when().post("/api/v1/transactions")
        .then()
        .statusCode(400)
        .contentType("application/problem+json")
        .body("type", equalTo("https://exchangeservice.example.com/problems/validation"))
        // Название отвергнутого поля обязано быть в ответе: клиент должен понять, что именно чинить.
        .body("errors.find { it.field == 'transaction_id' }.message", notNullValue());

    // Отказ не должен оставлять следов: ни принятой транзакции, ни лишней строки-заглушки.
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM expense_transaction", Integer.class)).isZero();
  }

  @Test
  @DisplayName("Пустой transaction_id означает «идентификатора нет», и сервис генерирует свой")
  void emptyTransactionIdIsAcceptedAndGenerated() {
    Map<String, Object> body = new HashMap<>(transactionBody("100.00", "product", "2022-01-02T10:00:00Z"));
    body.put("transaction_id", "");

    given().contentType("application/json").body(body)
        .when().post("/api/v1/transactions")
        .then()
        .statusCode(202)
        .body("transaction_id", notNullValue());
  }

  @Test
  @DisplayName("Повторный POST с тем же transaction_id не создаёт вторую запись")
  void repeatedTransactionIdIsIdempotent() {
    UUID id = UUID.randomUUID();
    Map<String, Object> body = new HashMap<>(transactionBody("100.00", "product", "2022-01-02T10:00:00Z"));
    body.put("transaction_id", id.toString());

    given().contentType("application/json").body(body)
        .when().post("/api/v1/transactions").then().statusCode(202);
    given().contentType("application/json").body(body)
        .when().post("/api/v1/transactions").then().statusCode(202);

    Integer rows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM expense_transaction WHERE id = ?", Integer.class, id);
    assertThat(rows).isEqualTo(1);
  }

  @Test
  @DisplayName("Повторный POST не стирает уже рассчитанные курс, сумму в USD и флаг превышения")
  void repeatedTransactionIdKeepsCalculatedFlag() {
    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, COUNTERPARTY, "USD", new BigDecimal("1200.00"),
            ExpenseCategory.PRODUCT, utc("2022-01-02"));
    // USD не обращается к внешнему API, поэтому флаг рассчитывается полностью и детерминированно.
    ExpenseTransaction resolved = intakeService.settle(pending.id());
    assertThat(resolved.limitExceeded()).isTrue();

    UUID id = pending.id();
    Map<String, Object> body =
        new HashMap<>(transactionBody("1200.00", "product", "2022-01-02T10:00:00Z"));
    body.put("transaction_id", id.toString());

    given().contentType("application/json").body(body)
        .when().post("/api/v1/transactions")
        .then()
        .statusCode(202)
        .body("status", equalTo("RATE_RESOLVED"))
        .body("limit_exceeded", equalTo(true));

    // Флаг читается из БД, а не из ответа расчёта: именно база решает, что сохранилось.
    assertThat(jdbcTemplate.queryForObject(
        "SELECT limit_exceeded FROM expense_transaction WHERE id = ?", Boolean.class, id)).isTrue();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT amount_usd FROM expense_transaction WHERE id = ?", BigDecimal.class, id))
        .isEqualByComparingTo("1200.00");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM expense_transaction WHERE id = ?", String.class, id))
        .isEqualTo("RATE_RESOLVED");
  }

  @Test
  @DisplayName("POST /transactions/{id}/settle досчитывает операцию, дошедшую до FAILED")
  void manualSettleRecoversFailedTransaction() {
    // Статус FAILED означает «требуется ручной расчёт», и он обязан быть достижим: без такого
    // эндпоинта операция навсегда осталась бы без курса и флага limit_exceeded.
    stubRateProviderFailure();
    String id = postForeignTransaction("10000.00", "2022-01-10T00:00:00Z");
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      intakeService.settle(UUID.fromString(id));
    }
    assertThat(statusInDatabase(id)).isEqualTo(TransactionStatus.FAILED.name());

    rateApi().resetAll();
    stubRate("KZT", new BigDecimal("0.0025"));

    given()
        .when()
        .post("/api/v1/transactions/{id}/settle", id)
        .then()
        .statusCode(200)
        .body("status", equalTo("RATE_RESOLVED"))
        .body("usd_rate", equalTo(0.0025f))
        .body("amount_usd", equalTo(25.00f))
        .body("limit_exceeded", equalTo(false));

    assertThat(statusInDatabase(id)).isEqualTo(TransactionStatus.RATE_RESOLVED.name());
  }

  @Test
  @DisplayName("POST /transactions/{id}/settle отвечает 202, пока курс недоступен")
  void manualSettleReportsPendingWhenRateIsUnavailable() {
    stubRateProviderFailure();
    String id = postForeignTransaction("10000.00", "2022-01-10T00:00:00Z");

    given()
        .when()
        .post("/api/v1/transactions/{id}/settle", id)
        .then()
        .statusCode(202)
        .body("status", equalTo("PENDING"))
        .body("amount_usd", nullValue());
  }

  @Test
  @DisplayName("POST /transactions/{id}/settle для несуществующей операции отвечает 404")
  void manualSettleOfUnknownTransactionIsNotFound() {
    given()
        .when()
        .post("/api/v1/transactions/{id}/settle", UUID.randomUUID())
        .then()
        .statusCode(404)
        .contentType("application/problem+json")
        .body("resource", equalTo("transaction"));
  }

  @Test
  @DisplayName("Параллельная повторная доставка одного transaction_id не приводит к 409 и дублю")
  void concurrentDuplicateDeliveryIsAcceptedOnce() throws Exception {
    UUID id = nextId();
    Map<String, Object> body =
        new HashMap<>(transactionBody("500.00", "service", "2022-01-02T10:00:00Z"));
    body.put("transaction_id", id.toString());
    String json = objectMapper.writeValueAsString(body);

    int threads = 8;
    var pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Integer>> responses = new ArrayList<>();
      var start = new CountDownLatch(1);
      for (int i = 0; i < threads; i++) {
        responses.add(
            pool.submit(
                () -> {
                  start.await();
                  return given()
                      .contentType("application/json")
                      .body(json)
                      .when()
                      .post("/api/v1/transactions")
                      .then()
                      .extract()
                      .statusCode();
                }));
      }
      start.countDown();
      for (Future<Integer> response : responses) {
        assertThat(response.get(30, TimeUnit.SECONDS)).isEqualTo(202);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM expense_transaction WHERE id = ?", Integer.class, id)).isEqualTo(1);
  }

  private String postTransaction(String sum, String category, String datetime) {
    return given()
        .contentType("application/json")
        .body(transactionBody(sum, category, datetime))
        .when()
        .post("/api/v1/transactions")
        .then()
        .statusCode(202)
        .extract()
        .path("transaction_id");
  }

  /** Операция в валюте, курс которой берётся у внешнего API: без заглушки недоступна. */
  private String postForeignTransaction(String sum, String datetime) {
    Map<String, Object> body = new HashMap<>(transactionBody(sum, "product", datetime));
    body.put("currency_shortname", "KZT");
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

  private String statusInDatabase(String id) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM expense_transaction WHERE id = ?", String.class, UUID.fromString(id));
  }

  private Map<String, Object> transactionBody(String sum, String category, String datetime) {
    Map<String, Object> body = new HashMap<>();
    body.put("account_from", ACCOUNT);
    body.put("account_to", COUNTERPARTY);
    body.put("currency_shortname", "USD");
    body.put("sum", new BigDecimal(sum));
    body.put("expense_category", category);
    body.put("datetime", datetime);
    return body;
  }

  /** Транзакция, принятая и рассчитанная в обход HTTP: нужна для подготовки read-модели. */
  private void settleInDatabase(String isoDate, String sum) {
    settleInDatabase(ExpenseCategory.PRODUCT, isoDate, sum);
  }

  private void settleInDatabase(ExpenseCategory category, String isoDate, String sum) {
    ExpenseTransaction pending =
        intakeService.accept(
            nextId(), ACCOUNT, COUNTERPARTY, "USD", new BigDecimal(sum),
            category, utc(isoDate));
    intakeService.settle(pending.id());
  }

  private static UUID nextId() {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ID_SEQUENCE.incrementAndGet()));
  }
}

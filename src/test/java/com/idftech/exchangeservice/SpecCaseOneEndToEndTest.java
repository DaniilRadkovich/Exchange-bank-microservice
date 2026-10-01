package com.idftech.exchangeservice;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import com.idftech.exchangeservice.application.TransactionIntakeService;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Сквозной тест «1 случая» из таблицы ТЗ (требование 6) целиком через HTTP.
 *
 * <p>Требование проверяет не набор функций, а сценарий клиента: приложение поднимается на реальном
 * порту, лимиты ставятся запросами {@code POST /limits}, операции приходят в {@code
 * POST /transactions}, а результат читается в {@code GET /limits/exceeded}. Ожидаемый ответ — ровно
 * две операции, 3 и 13 января, с параметрами превышенного лимита.
 *
 * <p>Два момента, ради которых сценарий повторяет таблицу, а не придумывает свои числа:
 *
 * <ul>
 *   <li>лимит меняется внутри месяца — 1000 от 1 января и 2000 от 10 января, поэтому флаги 3 и 13
 *       января сверяются с разными лимитами;
 *   <li>13 января две операции по 100, и превышение появляется только на второй: остаток ровно 0
 *       превышением не считается, а следующие 100 уже превышают.
 * </ul>
 *
 * <p>Даты лимитов задаются переводом часов тестового {@code Clock}, а не полем в теле запроса: по
 * ТЗ дату установки проставляет сервис. Сами лимиты и операции при этом создаются только HTTP-вызовами.
 *
 * <p>Расчёт флагов вызывается явно, как и во всех остальных интеграционных тестах проекта: фоновый
 * планировщик в тестовом профиле выключен, иначе проверка зависела бы от тайминга потока.
 */
class SpecCaseOneEndToEndTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000123";
  private static final String COUNTERPARTY = "0000009999";

  @Autowired
  private TransactionIntakeService intakeService;

  @BeforeEach
  void setUpRestAssured() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
  }

  @Test
  @DisplayName("ТЗ п.6, случай 1: через HTTP превышают только операции от 3 и 13 января")
  void caseOneFromSpecTableThroughHttp() {
    // --- Лимит 1000 USD с 1 января: устанавливается запросом, дата — со стороны сервиса.
    testClock.set(utc("2022-01-01").plusHours(10));
    Response firstLimit =
        given()
            .contentType("application/json")
            .body(limitBody("1000.00"))
            .when()
            .post("/api/v1/limits")
            .then()
            .statusCode(201)
            .body("limit_currency_shortname", equalTo("USD"))
            .body("limit_sum", equalTo(1000.00f))
            .extract()
            .response();
    assertThat(firstLimit.jsonPath().getString("limit_datetime"))
        .isEqualTo("2022-01-01T10:00:00Z");

    // --- Операции января до повышения лимита. USD: внешний API курсов не участвует.
    sendAndSettle("2022-01-02", "500.00");
    sendAndSettle("2022-01-03", "600.00");

    // --- Лимит повышен до 2000 USD с 10 января.
    testClock.set(utc("2022-01-10").plusHours(10));
    given()
        .contentType("application/json")
        .body(limitBody("2000.00"))
        .when()
        .post("/api/v1/limits")
        .then()
        .statusCode(201)
        .body("limit_sum", equalTo(2000.00f));

    sendAndSettle("2022-01-11", "100.00");
    sendAndSettle("2022-01-12", "700.00");
    sendAndSettle("2022-01-13", "100.00");
    sendAndSettle("2022-01-13", "100.00");

    // --- Клиентский ответ: ровно две операции, 3 и 13 января.
    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("", hasSize(2))
        .body("[0].datetime", equalTo("2022-01-03T00:00:00Z"))
        .body("[0].amount_usd", equalTo(600.00f))
        .body("[0].limit_sum", equalTo(1000.00f))
        .body("[0].limit_currency_shortname", equalTo("USD"))
        .body("[0].limit_datetime", equalTo("2022-01-01T10:00:00Z"))
        .body("[0].spent_usd", equalTo(1100.00f))
        .body("[1].datetime", equalTo("2022-01-13T00:00:00Z"))
        .body("[1].amount_usd", equalTo(100.00f))
        .body("[1].limit_sum", equalTo(2000.00f))
        .body("[1].limit_currency_shortname", equalTo("USD"))
        .body("[1].limit_datetime", equalTo("2022-01-10T10:00:00Z"))
        .body("[1].spent_usd", equalTo(2100.00f));
  }

  @Test
  @DisplayName("ТЗ п.6: остаток ровно 0 превышением не считается, 100 USD сверх лимита — считаются")
  void zeroRemainingIsNotAnExceedance() {
    testClock.set(utc("2022-01-01").plusHours(10));
    setLimitOverHttp("1000.00");

    sendAndSettle("2022-01-02", "500.00");
    sendAndSettle("2022-01-03", "500.00");

    // Ровно 1000 из 1000: флаг у обеих операций false.
    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("", hasSize(0));

    sendAndSettle("2022-01-04", "100.00");

    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("", hasSize(1))
        .body("[0].datetime", equalTo("2022-01-04T00:00:00Z"))
        .body("[0].spent_usd", equalTo(1100.00f));
  }

  @Test
  @DisplayName("ТЗ п.6: лимит января не действует в феврале — там снова действует лимит по умолчанию")
  void januaryLimitDoesNotLeakIntoFebruary() {
    // Лимит января намеренно очень большой. Если бы он переносился на февраль, превышения в феврале
    // не было бы вовсе, и тест не отличал бы правильное поведение от переноса.
    testClock.set(utc("2022-01-01").plusHours(10));
    setLimitOverHttp("5000.00");
    sendAndSettle("2022-01-31", "600.00");

    // 1100 USD в феврале: против лимита по умолчанию 1000 это превышение.
    sendAndSettle("2022-02-01", "1100.00");

    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits/exceeded")
        .then()
        .statusCode(200)
        .body("", hasSize(1))
        .body("[0].datetime", equalTo("2022-02-01T00:00:00Z"))
        .body("[0].limit_sum", equalTo(1000.00f))
        .body("[0].limit_datetime", equalTo("2022-02-01T00:00:00Z"))
        .body("[0].spent_usd", equalTo(1100.00f));
  }

  @Test
  @DisplayName("ТЗ п.6: GET /limits показывает остаток по установленному лимиту")
  void limitsEndpointReportsRemainingAmount() {
    testClock.set(utc("2022-01-01").plusHours(10));
    setLimitOverHttp("1000.00");
    sendAndSettle("2022-01-02", "500.00");
    sendAndSettle("2022-01-03", "600.00");

    given()
        .queryParam("account_from", ACCOUNT)
        .when()
        .get("/api/v1/limits")
        .then()
        .statusCode(200)
        .body("", hasSize(1))
        .body("[0].spent_usd", equalTo(1100.00f))
        .body("[0].remaining_usd", equalTo(-100.00f));
  }

  private void setLimitOverHttp(String limitSum) {
    given()
        .contentType("application/json")
        .body(limitBody(limitSum))
        .when()
        .post("/api/v1/limits")
        .then()
        .statusCode(201);
  }

  /**
   * Операция целиком через HTTP, затем явный расчёт.
   *
   * <p>Идентификатор операции не передаётся: он генерируется сервисом, а ответ содержит его, и по нему
   * вызывается расчёт. Две операции одного дня различаются последовательностью идентификаторов,
   * поэтому порядок в ответе предсказуем.
   */
  private void sendAndSettle(String isoDate, String sum) {
    Response response =
        given()
            .contentType("application/json")
            .body(transactionBody(sum, isoDate))
            .when()
            .post("/api/v1/transactions")
            .then()
            .statusCode(202)
            .body("status", equalTo("PENDING"))
            .extract()
            .response();

    intakeService.settle(UUID.fromString(response.jsonPath().getString("transaction_id")));
  }

  private Map<String, Object> limitBody(String limitSum) {
    Map<String, Object> body = new HashMap<>();
    body.put("account_from", ACCOUNT);
    body.put("expense_category", "product");
    body.put("limit_sum", new BigDecimal(limitSum));
    return body;
  }

  private Map<String, Object> transactionBody(String sum, String isoDate) {
    Map<String, Object> body = new HashMap<>();
    body.put("account_from", ACCOUNT);
    body.put("account_to", COUNTERPARTY);
    body.put("currency_shortname", "USD");
    body.put("sum", new BigDecimal(sum));
    body.put("expense_category", "product");
    body.put("datetime", isoDate + "T00:00:00Z");
    return body;
  }
}
package com.idftech.exchangeservice;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Контракт ошибок на уровне протокола: коды 400/404/405/406/415, которые Spring MVC выражает сам.
 *
 * <p>Отдельный класс не для красоты, а потому что {@code ProblemDetailExceptionHandler} объявлен с
 * {@code HIGHEST_PRECEDENCE} и перехватывает {@code Exception}. Из-за этого он обходит
 * {@code DefaultHandlerExceptionResolver}, и без явных обработчиков все ошибки протокола
 * превращаются в 500. Проверять их придётся здесь, а не в {@link ExpenseApiIntegrationTest}: там
 * сценарии связаны с содержимым тела запроса и перемешались бы с проверками Bean Validation.
 */
class ApiErrorContractIntegrationTest extends AbstractIntegrationTest {

  private static final String ACCOUNT = "0000000123";

  @BeforeEach
  void setUpRestAssured() {
    RestAssured.baseURI = "http://localhost";
    RestAssured.port = port;
  }

  @Test
  @DisplayName("Без обязательного параметра account_from клиент получает 400, а не 500")
  void missingRequiredQueryParameterIsBadRequest() {
    assertProblem(
        given().when().get("/api/v1/limits/exceeded").then(),
        400,
        "https://exchangeservice.example.com/problems/validation");
  }

  @Test
  @DisplayName("Некорректный UUID в пути отклоняется с 400")
  void malformedPathVariableIsBadRequest() {
    assertProblem(
        given().when().get("/api/v1/transactions/not-a-uuid").then(),
        400,
        "https://exchangeservice.example.com/problems/validation");
  }

  @Test
  @DisplayName("Метод, которого нет у ресурса, отвечает 405 и перечисляет поддерживаемые")
  void unsupportedMethodIsMethodNotAllowed() {
    assertProblem(
        given().when().post("/api/v1/transactions/" + UUID.randomUUID()).then(),
        405,
        "https://exchangeservice.example.com/problems/method-not-allowed");
  }

  @Test
  @DisplayName("Неизвестный путь отвечает 404, а не падает в обработчик Exception")
  void unknownPathIsNotFound() {
    assertProblem(
        given().when().get("/api/v1/definitely-not-a-route").then(),
        404,
        "https://exchangeservice.example.com/problems/not-found");
  }

  @Test
  @DisplayName("Тело не в application/json отвечает 415")
  void unsupportedContentTypeIsUnsupportedMediaType() {
    assertProblem(
        given()
            .contentType("text/plain")
            .body("account_from=" + ACCOUNT)
            .when()
            .post("/api/v1/limits")
            .then(),
        415,
        "https://exchangeservice.example.com/problems/unsupported-media-type");
  }

  @Test
  @DisplayName("Успешный ответ остаётся application/json, а не problem+json")
  void successIsNotServedAsProblemDetail() {
    given().queryParam("account_from", ACCOUNT).when().get("/api/v1/limits").then()
        .statusCode(200)
        .contentType("application/json");
  }

  @Test
  @DisplayName("Неизвестное поле в теле отклоняется с 400, а не игнорируется")
  void unknownJsonFieldIsBadRequest() {
    // Без fail-on-unknown-properties клиент отправил бы limit_datetime, решив, что задаёт дату
    // лимита, получил бы 201 с датой по умолчанию и обнаружил расхождение только в ответе.
    io.restassured.response.ValidatableResponse response =
        given()
            .contentType("application/json")
            .body(
                """
                {"account_from": "%s", "expense_category": "product", "limit_datetime": "2022-01-10T00:00:00Z"}
                """
                    .formatted(ACCOUNT))
            .when()
            .post("/api/v1/limits")
            .then();
    assertProblem(response, 400, "https://exchangeservice.example.com/problems/validation");
    // Название отвергнутого поля обязано быть в ответе: иначе клиент не поймёт, что именно
    // исправлять. Это же отличает настоящий отказ от неизвестного поля от любой другой ошибки 400.
    response.body("detail", org.hamcrest.Matchers.containsString("limit_datetime"));
    // Лимит при этом не создан.
    assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM expense_limit WHERE account_from = ?", Integer.class, ACCOUNT))
        .isZero();
  }

  @Test
  @DisplayName("Ошибка валидации называет поле так, как его видит клиент, а не по имени Java")
  void validationErrorNamesFieldAsClientSendsIt() {
    // Bean Validation называет поле по имени компонента записи: accountFrom. Клиент такого поля не
    // присылает, он шлёт account_from, и исправлять нужно именно его. Имя берётся из @JsonProperty,
    // поэтому тест проверяет отображение в целом, а не одно поле.
    io.restassured.response.ValidatableResponse response =
        given()
            .contentType("application/json")
            .body(
                """
                {"account_from": "12345", "account_to": "0000009999", "currency_shortname": "USD",
                 "sum": 100.00, "expense_category": "product", "datetime": "2022-01-02T10:00:00Z"}
                """
                    .formatted(ACCOUNT))
            .when()
            .post("/api/v1/transactions")
            .then();
    assertProblem(response, 400, "https://exchangeservice.example.com/problems/validation");
    response.body("errors[0].field", equalTo("account_from"));
    response.body("errors[0].message", notNullValue());
    assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM expense_transaction", Integer.class))
        .isZero();
  }

  @Test
  @DisplayName("Health отдаёт детали компонентов, а не только статус")
  void healthExposesComponentDetails() {
    // show-details: when-authorized при отсутствии Spring Security не показал бы деталей никогда —
    // health отвечал бы 'status: UP' и не давал понять, поднялся ли PostgreSQL.
    given().when().get("/actuator/health").then()
        .statusCode(200)
        .body("status", equalTo("UP"))
        .body("components.db.details.database", equalTo("PostgreSQL"))
        .body("components.db.status", equalTo("UP"));
  }

  private void assertProblem(
      io.restassured.response.ValidatableResponse response, int expectedStatus, String expectedType) {
    response
        .statusCode(expectedStatus)
        .contentType("application/problem+json")
        .body("status", equalTo(expectedStatus))
        .body("type", equalTo(expectedType))
        .body("title", notNullValue())
        .body("detail", notNullValue());
  }
}

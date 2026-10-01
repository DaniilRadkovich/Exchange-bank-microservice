package com.idftech.exchangeservice;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Базовая интеграционная среда: настоящий PostgreSQL, настоящий HTTP-сервис и заглушка внешнего
 * API курсов вместо платного провайдера.
 *
 * <p>Почему так: ТЗ требует проверить работу с БД, пессимистичные блокировки и SQL п.6. In-memory
 * подмена проверила бы только код, но не запросы — CHECK-ограничения, {@code SELECT FOR UPDATE} и
 * оконные функции живут именно в PostgreSQL.
 *
 * <p>Внешний API курсов подменён WireMock: он поднимается один раз на весь прогон и включается в
 * {@code @DynamicPropertySource} как {@code exchange.rates.base-url}, поэтому клиент курсов
 * настраивается сам и ни одна настройка теста не дублируется вручную.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AbstractIntegrationTest.ClockTestConfiguration.class)
abstract class AbstractIntegrationTest {

  /** Ответ провайдера курсов по умолчанию: одинаковая цена закрытия для всех дат. */
  protected static final BigDecimal DEFAULT_CLOSE_RATE = new BigDecimal("0.0025");

  /** Часы сервиса под управлением теста: позволяют «переводить время» между шагами сценария. */
  protected static final AtomicReference<OffsetDateTime> SERVICE_TIME =
      new AtomicReference<>(OffsetDateTime.parse("2022-01-01T10:00:00Z"));

  /**
   * PostgreSQL поднимается вручную, а не через аннотацию {@code @Container}.
   *
   * <p>Причина: расширение Testcontainers останавливает контейнер после каждого тестового класса. Spring
   * при этом переиспользует кэшированный контекст приложения, и уже созданный пул соединений
   * продолжает указывать на порт остановленного контейнера — следующий класс падает с
   * «Connection refused» при вполне рабочей схеме. Один контейнер на весь прогон с неизменным
   * портом, наоборот, позволяет переиспользовать один контекст и заметно ускоряет прогон.
   */
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("exchange")
          .withUsername("exchange")
          .withPassword("exchange");

  private static final WireMockServer RATE_API =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  static {
    POSTGRES.start();
    RATE_API.start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  RATE_API.stop();
                  POSTGRES.stop();
                }));
  }

  @LocalServerPort
  protected int port;

  @Autowired
  protected JdbcTemplate jdbcTemplate;

  @Autowired
  protected ObjectMapper objectMapper;

  @Autowired
  protected TestClock testClock;

  @DynamicPropertySource
  static void infrastructureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("exchange.rates.base-url", RATE_API::baseUrl);
    registry.add("exchange.rates.api-key", () -> "test-key");
  }

  @BeforeEach
  void resetInfrastructure() {
    // Каждый тест стартует с чистой БД: иначе счётчики идентификаторов и остатки прошлого теста
    // протекали бы в следующий.
    jdbcTemplate.execute("TRUNCATE TABLE expense_transaction, expense_limit, exchange_rate, spend_period_lock RESTART IDENTITY CASCADE");
    RATE_API.resetAll();
    SERVICE_TIME.set(OffsetDateTime.parse("2022-01-01T10:00:00Z"));
    testClock.set(SERVICE_TIME.get());
  }

  /** Заглушка успешного ответа провайдера: цена закрытия одинакова для всех дат. */
  protected void stubRate(String baseCurrency, BigDecimal close) {
    stubRate(baseCurrency, close, close);
  }

  /**
   * Заглушка ответа провайдера для валюты.
   *
   * @param close цена закрытия целевой даты
   * @param previousClose предыдущее закрытие; используется, если торгов на дату операции не было
   */
  protected void stubRate(String baseCurrency, BigDecimal close, BigDecimal previousClose) {
    RATE_API.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/time_series"))
            .withQueryParam("symbol", WireMock.equalTo(baseCurrency + "/USD"))
            .willReturn(
                WireMock.okJson(
                    """
                    {
                      "meta": {
                        "symbol": "%s/USD",
                        "interval": "1day",
                        "currency": "USD",
                        "exchange_timezone": "America/New_York"
                      },
                      "values": [
                        {
                          "datetime": "%s",
                          "close": "%s",
                          "previous_close": "%s"
                        }
                      ],
                      "status": "ok"
                    }
                    """
                        .formatted(baseCurrency, serviceDate(), close.toPlainString(),
                            previousClose.toPlainString()))));
  }

  /** Заглушка ответа без торгов на целевую дату: только предыдущее закрытие. */
  protected void stubRateWithoutTradingOnTargetDate(String baseCurrency, BigDecimal previousClose) {
    RATE_API.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/time_series"))
            .withQueryParam("symbol", WireMock.equalTo(baseCurrency + "/USD"))
            .willReturn(
                WireMock.okJson(
                    """
                    {
                      "meta": {"symbol": "%s/USD", "interval": "1day", "currency": "USD"},
                      "values": [
                        {"datetime": "%s", "close": "%s", "previous_close": "%s"}
                      ],
                      "status": "ok"
                    }
                    """
                        .formatted(baseCurrency, serviceDate().minusDays(1),
                            previousClose.toPlainString(), previousClose.toPlainString()))));
  }

  /** Заглушка недоступности внешнего API: сервер отвечает 500 на все запросы. */
  protected void stubRateProviderFailure() {
    RATE_API.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/time_series"))
            .willReturn(WireMock.serverError()));
  }

  /** Ответ-заглушка без данных: провайдер жив, но курса на запрошенную дату нет. */
  protected void stubRateEmpty() {
    RATE_API.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/time_series"))
            .willReturn(WireMock.okJson("{\"meta\":{\"symbol\":\"UNKNOWN/USD\"},\"values\":[],\"status\":\"ok\"}")));
  }

  protected WireMockServer rateApi() {
    return RATE_API;
  }

  /** Дата, к которой привязаны заглушки курсов: сегодняшний день часов сервиса. */
  protected OffsetDateTime serviceDate() {
    return SERVICE_TIME.get();
  }

  protected OffsetDateTime utc(String isoLocalDate) {
    return OffsetDateTime.parse(isoLocalDate + "T00:00:00Z");
  }

  /** Счётчик обращений к внешнему API за тест: доказывает, что используется собственный кэш. */
  protected int rateApiCallCount() {
    return RATE_API.countRequestsMatching(
            WireMock.getRequestedFor(WireMock.urlPathEqualTo("/time_series")).build())
        .getCount();
  }

  /**
   * Тестовая замена системных часов.
   *
   * <p>Дата установки лимита берётся сервисом из бина {@link Clock}, поэтому сценарии, где лимит
   * «появился» 10-го числа, воспроизводятся переводом часов, а не вызовом кода в обход API.
   */
  static class TestClock extends Clock {

    private final AtomicReference<OffsetDateTime> currentTime = new AtomicReference<>(SERVICE_TIME.get());

    void set(OffsetDateTime time) {
      currentTime.set(time);
      SERVICE_TIME.set(time);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return currentTime.get().toInstant();
    }

    OffsetDateTime currentTime() {
      return currentTime.get();
    }
  }

  /** Подменяет системные часы управляемыми: основной бин {@code Clock} тут же перестаёт использоваться. */
  @TestConfiguration
  static class ClockTestConfiguration {

    @Bean
    @Primary
    Clock testClock() {
      return new TestClock();
    }
  }
}
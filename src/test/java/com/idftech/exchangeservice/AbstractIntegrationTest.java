package com.idftech.exchangeservice;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
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
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = AbstractIntegrationTest.InfrastructureTestConfiguration.class)
@ActiveProfiles("test")
@Import(AbstractIntegrationTest.ClockTestConfiguration.class)
abstract class AbstractIntegrationTest {

  /** Ответ провайдера курсов по умолчанию: одинаковая цена закрытия для всех дат. */
  protected static final BigDecimal DEFAULT_CLOSE_RATE = new BigDecimal("0.0025");

  /** Часы сервиса под управлением теста: позволяют «переводить время» между шагами сценария. */
  protected static final AtomicReference<OffsetDateTime> SERVICE_TIME =
      new AtomicReference<>(OffsetDateTime.parse("2022-01-01T10:00:00Z"));

  /**
   * WireMock живёт весь прогон статикой: у него нет бина для {@code @ServiceConnection}, а контекст
   * Spring кэшируется между тестовыми классами — пересоздавать заглушку внешнего API на каждый класс
   * означало бы иной порт и потерянные счётчики одновременных обращений.
   */
  private static final WireMockServer RATE_API =
      new WireMockServer(
          WireMockConfiguration.options()
              .dynamicPort()
              .extensions(ConcurrentRequestCounterHolder.TRANSFORMER));

  static {
    RATE_API.start();
    Runtime.getRuntime().addShutdownHook(new Thread(RATE_API::stop));
  }

  @LocalServerPort
  protected int port;

  @Autowired
  protected JdbcTemplate jdbcTemplate;

  @Autowired
  protected ObjectMapper objectMapper;

  @Autowired
  protected TestClock testClock;

  /** Подключение к БД приходит из контейнера через {@code @ServiceConnection}, здесь только провайдер. */
  @DynamicPropertySource
  static void rateApiProperties(DynamicPropertyRegistry registry) {
    registry.add("exchange.rates.base-url", RATE_API::baseUrl);
    registry.add("exchange.rates.api-key", () -> "test-key");
  }

  @BeforeEach
  void resetInfrastructure() {
    // Каждый тест стартует с чистой БД: иначе счётчики идентификаторов и остатки прошлого теста
    // протекали бы в следующий.
    jdbcTemplate.execute("TRUNCATE TABLE expense_transaction, expense_limit, exchange_rate, spend_period_lock RESTART IDENTITY CASCADE");
    RATE_API.resetAll();
    ConcurrentRequestCounterHolder.COUNTER.reset();
    // Удержание сбрасывается вместе с остальной заглушкой: медленный ответ из одного теста
    // иначе протёк бы в следующий и исказил его измерения.
    ConcurrentRequestCounterHolder.TRANSFORMER.holdEachRequestFor(Duration.ZERO);
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

  /**
   * Заглушка ответа провайдера, которая держит каждый запрос заданное время и считает одновременные
   * обращения.
   *
   * <p>Нужна, чтобы доказать, что запросы действительно идут параллельно: без паузы на стороне
   * WireMock пачка расходуется быстрее любого измерения, и последовательный код проходит такую же
   * проверку, как параллельный. Пауза имитирует реальный сетевой вызов — именно его и нужно перекрыть
   * распараллеливанием.
   *
   * @param holdTime сколько провайдер «думает» перед ответом
   */
  protected void stubSlowRate(Duration holdTime) {
    ConcurrentRequestCounterHolder.TRANSFORMER.holdEachRequestFor(holdTime);
    RATE_API.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/time_series"))
            .willReturn(
                WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(slowRateBody())
                    .withTransformers(ConcurrencyCountingTransformer.NAME)));
  }

  private String slowRateBody() {
    return """
        {
          "meta": {"symbol": "SLOW/USD", "interval": "1day", "currency": "USD"},
          "values": [
            {"datetime": "%s", "close": "%s", "previous_close": "%s"}
          ],
          "status": "ok"
        }
        """
        .formatted(
            serviceDate(),
            DEFAULT_CLOSE_RATE.toPlainString(),
            DEFAULT_CLOSE_RATE.toPlainString());
  }

  /**
   * Пик одновременных обращений к внешнему API, зафиксированный с момента последнего сброса.
   *
   * <p>Нулевое значение означает, что заглушка с задержкой не выставлялась.
   */
  protected int peakConcurrentRateRequests() {
    return ConcurrentRequestCounterHolder.COUNTER.peak();
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

  /**
   * PostgreSQL в Testcontainers, объявленный бином контекста.
   *
   * <p>Раньше контейнер поднимался вручную в статическом блоке, и это ломалось предсказуемо:
   * расширение Testcontainers останавливает контейнер после каждого тестового класса, а Spring пере-
   * жиспользует кэшированный контекст, и уже созданный пул соединений продолжает указывать на порт
   * остановленного контейнера — следующий класс падал с «Connection refused» при вполне рабочей
   * схеме. Объявление бином решает это на уровне жизненного цикла: контейнер принадлежит кэшу
   * контекста, поэтому один контекст — один контейнер на все наследники.
   *
   * <p>Второй контекст (у {@code SettlementKickIntegrationTest} другие свойства) получит свой
   * контейнер — это осознанно: у каждой конфигурации своя БД, и тесты не зависят от порядка.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class InfrastructureTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
      return new PostgreSQLContainer<>("postgres:17-alpine")
          .withDatabaseName("exchange")
          .withUsername("exchange")
          .withPassword("exchange");
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

/**
 * Доступ к счётчику и преобразователю из статического блока, где создаётся WireMock.
 *
 * <p>Сервер поднимается один раз на весь прогон в статической инициализации, а счётчик нужен
 * экземпляру теста. Статическое поле — единственный способ связать их без передачи сервера в
 * конструктор каждого теста.
 */
final class ConcurrentRequestCounterHolder {

  static final ConcurrentRequestCounter COUNTER = new ConcurrentRequestCounter();

  static final ConcurrencyCountingTransformer TRANSFORMER =
      new ConcurrencyCountingTransformer(COUNTER);

  private ConcurrentRequestCounterHolder() {}
}

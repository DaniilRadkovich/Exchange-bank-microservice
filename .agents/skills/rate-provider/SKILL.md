---
name: rate-provider
description: Добавить или заменить провайдера биржевых курсов валют в проекте exchangeservice (Twelve Data, Alpha Vantage, Open Exchange Rates или другой). Используй, когда просят подключить другой внешний API курсов, изменить формат ответа провайдера, добавить поддержку новой валютной пары, разобраться с таймаутами, повторами или сбоями внешнего API, а также при жалобах, что транзакции зависают в статусе PENDING.
---

# Провайдер биржевых курсов

## Архитектура: где что менять

Курсы из внешнего API проходят три слоя. Менять нужно только первый — остальное останется прежним.

```
TwelveDataRateProvider        инфраструктура: HTTP-вызов, разбор JSON, DTO провайдера
  ↓ реализует
ExchangeRateProvider (порт)   application/port/ExchangeRateProvider.java — контракт
  ↓ использует
ExchangeRateService           application: сначала БД, потом провайдер; проверка возраста fallback
  ↓ отдаёт курс
TransactionIntakeService      вне транзакции достаёт курс (ParallelRateResolver)
  ↓
SettlementApplier             в своей транзакции пишет сумму в USD и флаг limit_exceeded
```

Повторы с экспоненциальной задержкой — не здесь, а в `RetryingCaller`: см. «Чего не делать».

Ключевая идея: `ExchangeRateService` сначала смотрит в БД (`RateCache`) и обращается к
провайдеру, только если курса нет. Новый провайдер не должен менять эту политику — иначе
исчезнет главное свойство сервиса: платные и медленные запросы не идут на каждый расчёт.

## Шаг 1. Модель ответа провайдера

Создай DTO в `infra/rate/` рядом с существующим `TimeSeriesResponse.java`. Имена полей приводь к
Java-конвенции, а JSON-имена указывай явно через `@JsonProperty`:

```java
public record AlphaVantageResponse(
    @JsonProperty("4. close") BigDecimal close,
    @JsonProperty("5. volume") Long volume) {}
```

Две особенности форматов, на которые уже натыкались:

- **Jackson 3.** Spring Boot 4 использует `tools.jackson.*` в тестах, но аннотации остаются
  `com.fasterxml.jackson.annotation.*` — они переехали в отдельный пакет. Проверь версию
  зависимости, прежде чем писать аннотацию.
- **Nullable обязателен.** `close` может отсутствовать: биржа не торговала в выходной. Тип должен
  быть обёрнут (`BigDecimal`), а не примитив, иначе ноль станет неотличим от «торгов не было».
  Fallback на `previous_close` живёт в домене: `ExchangeRate.applicableRate()`.

## Шаг 2. Реализация порта

Реализуй `infra/rate/AlphaVantageRateProvider.java`. Класс сам по себе Spring-компонентом **не**
является: бины провайдеров регистрируются в `infra/config/RateClientConfig.java`, как у
`TwelveDataRateProvider`.

```java
public class AlphaVantageRateProvider implements ExchangeRateProvider {

  private final RestClient client;
  private final RateProviderProperties properties;

  @Override
  public Optional<ExchangeRate> fetchDailyRate(String baseCurrency, LocalDate date) {
    // ... вызов, разбор, маппинг в домен
  }
}
```

Обязательные требования к реализации:

- Возвращай `Optional<ExchangeRate>`, а не бросай исключение: «курс не найден» — штатная ситуация,
  а не сбой. Пустой `Optional` оставляет транзакцию в `PENDING`.
- Лови сетевые ошибки и возвращай `Optional.empty()`. Исключение наружу превратит недоступность
  провайдера в `500` на клиенте вместо `PENDING` у транзакции.
- Используй `RestClient`, а не `RestTemplate`: таймауты настраиваются в `RateClientConfig`.
- Никогда не бросай курс, равный нулю: `ExchangeRate.applicableRate()` считает нулевое и
  отрицательное значение непригодным и бросает `IllegalStateException`, если пригодного значения
  нет вовсе. Проверку делай в провайдере, до конструирования `ExchangeRate`: сбой на нашей стороне
  должен выходить наружу (п.3 `AGENTS.md`), а пустой `Optional` — это отказ провайдера.
- Прерывание потока — не отказ провайдера: `RetryingCaller` бросает `RateCallCancelledException`, и
  его нельзя ловить вместе с сетевыми ошибками, иначе остановка сервиса снова станет «курсом
  недоступен» с засчитанной попыткой.
- **Проверяй, какие пары есть у провайдера, а не какие хочется.** Twelve Data котирует тенге только
  как `USD/KZT`: прямой `KZT/USD` возвращает 404 `symbol is missing or invalid`, и префикс `FX:` его
  не спасает (404 и на `FX:KZT/USD`, и на `CURRENCY:KZT/USD`). Список пар — `GET /forex_pairs`.
  Если прямой пары нет, бери обратную и обращай курс: `BigDecimal.ONE.divide(rate, RATE_SCALE,
  HALF_UP)`. Округление по умолчанию здесь не годится — тенге (≈0.0022) превратились бы в `0.00`,
  и сумма в USD молча уехала бы в ноль при формально «успешном» расчёте. Регрессия:
  `ExchangeRateIntegrationTest#currencyWithoutDirectUsdPairIsResolvedThroughInversePair`.

## Шаг 3. Регистрация бина

Добавь метод в `infra/config/RateClientConfig.java` — компонентное сканирование уже охватывает
пакет, новый `@Component` не нужен и не сработает так, как ожидаешь:

```java
@Bean
@ConditionalOnProperty(
    prefix = "exchange.rates",
    name = "provider",
    havingValue = "alphavantage",
    matchIfMissing = true)
public AlphaVantageRateProvider alphaVantageRateProvider(
    RestClient rateProviderRestClient,
    RateProviderProperties properties,
    RetryingCaller retryingCaller,
    MeterRegistry meterRegistry) {
  return new AlphaVantageRateProvider(rateProviderRestClient, properties, retryingCaller, meterRegistry);
}
```

И значение в конфигурации:

```yaml
exchange:
  rates:
    provider: ${RATES_API_PROVIDER:alphavantage}   # twelvedata | alphavantage | ...
```

Значение по умолчанию обязано совпадать с `havingValue` бина, у которого `matchIfMissing = true`:
именно оно выбирает провайдера по умолчанию. Форму `${RATES_API_PROVIDER:...}` не заменяй на
константу — переменная позволяет переключить провайдера на стенде, не правя репозиторий.

`matchIfMissing = true` обязателен у провайдера по умолчанию (`twelvedata`): иначе сервис не
поднимется с текущим `application.yaml`. Условие по `provider` гарантирует, что активен ровно один
провайдер: без него оба бина были бы в контексте, и выбор отдавался бы `@Primary` — то есть
молчаливому приоритету, а не значению из конфигурации.

Проверь, что в `TwelveDataRateProvider` условие тоже стоит, иначе при `provider: alphavantage`
Spring загрузит оба и один перекроет другой.

`RestClient` и `HttpClient` переиспользуются: таймауты и `baseUrl` заданы один раз в
`RateClientConfig` и берутся из `exchange.rates.*`. Второй провайдер со своим `baseUrl` требует
своего клиента — тогда добавь отдельный метод `@Bean`, а не переписывай общий.

## Шаг 4. Конфигурация

Секреты — только через переменные окружения, никогда не в yaml:

```yaml
exchange:
  rates:
    api-key: ${RATES_API_KEY:}
    base-url: ${RATES_API_BASE_URL:https://www.alphavantage.co/query}
    connect-timeout: 2s
    read-timeout: 3s
    max-retries: 3
```

## Шаг 5. Тест

Обязателен тест в `src/test/java/com/idftech/exchangeservice/ExchangeRateIntegrationTest.java`.
Внешний API мокается WireMock, реальный ключ не нужен. Проверяй минимум:

1. Успешная конвертация: сумма в USD равна произведению суммы на курс.
2. Отсутствие торгов: используется `previous_close`.
3. Отказ провайдера: транзакция остаётся `PENDING`, а не падает.
4. Пустой ответ провайдера: транзакция остаётся `PENDING`.
5. Кэш: повторный запрос за ту же пару и дату не ходит в провайдер.
6. Протухший резервный курс: запись в БД без `close` для даты операции отклоняется, если она старше
   `exchange.rates.max-fallback-age`, и транзакция не рассчитывается.
7. Исчерпание попыток: после `exchange.settlement.max-attempts` транзакция становится `FAILED`, а
   повторный `settle` после починки провайдера её рассчитывает.

```java
protected void stubRate(String baseCurrency, BigDecimal close, BigDecimal previousClose) { ... }
protected void stubRateProviderFailure() { ... }
protected void stubRateEmpty() { ... }
protected int rateApiCallCount() { ... }
```

Готово в `AbstractIntegrationTest`. Проверка кэша — через `rateApiCallCount()`: он считает реальные
обращения к заглушке и тем самым ловит регрессию «провайдер стал вызываться на каждый расчёт».

## Запуск

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ExchangeRateIntegrationTest test
```

## Чего не делать

- Не вызывай провайдера напрямую из `TransactionIntakeService`: политика «сначала БД» живёт в
  `ExchangeRateService`, и её обход означает платный запрос на каждую транзакцию.
- Не добавляй повторы внутрь реализации провайдера. Повторы с экспоненциальной задержкой уже
  реализованы в `RetryingCaller`; второй слой повторов превратит недоступность провайдера в лавину
  запросов.
- Не меняй `application-test.yaml` для проверки нового провайдера: URL заглушки подставляется
  через `@DynamicPropertySource`, а не вручную.
- Не отключай WireMock, «упростив» тест. Тест, который не проверяет отказ провайдера, не проверяет
  ровно то, ради чего писался.

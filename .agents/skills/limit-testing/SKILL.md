---
name: limit-testing
description: Написать или изменить тесты логики месячных лимитов (limit_exceeded, остаток, смена лимита, границы месяца) в проекте exchangeservice. Используй, когда просят покрыть тестами расчёт лимитов, воспроизвести сценарий из таблицы ТЗ, проверить граничный случай (остаток ровно 0, переход на новый месяц, смена лимита внутри месяца, поздняя операция с ранней датой) или разобраться, почему limit_exceeded выставлен неверно.
---

# Тесты логики месячных лимитов

## Что покрываем

Флаг `limit_exceeded` кумулятивный: он сравнивает накопленную сумму всех операций месяца «до и
включая текущую» с лимитом, действовавшим на момент её совершения. Ошибка почти всегда в
понимании этой формулировки, поэтому начинай с неё, а не с кода.

## Выбор уровня теста

| Что проверяем | Где писать |
| --- | --- |
| Чистый расчёт: накопленная сумма, флаг, месячные границы, tie-break | `src/test/java/com/idftech/exchangeservice/application/LimitCalculatorTest.java` |
| Сценарий целиком через сервисы и настоящий PostgreSQL | `src/test/java/com/idftech/exchangeservice/LimitScenarioIntegrationTest.java` |
| SQL п.6 (JOIN, подзапрос, оконная SUM), остаток и расход периода | `src/test/java/com/idftech/exchangeservice/ExceededTransactionsQueryIntegrationTest.java` |
| Конкурентный расчёт | `src/test/java/com/idftech/exchangeservice/ConcurrentSettlementIntegrationTest.java` |
| Параллельная двухфазная дорасчётка, ограничение соединений | `src/test/java/com/idftech/exchangeservice/ParallelSettlementIntegrationTest.java` |

Юнит-тесты `LimitCalculator` не поднимают Spring: он чистый, зависит только от `LimitProperties`
и `Clock`. Это быстрый способ прогнать десятки граничных случаев.

**Остаток и расход периода в `LimitCalculator` нет** — их считает SQL
(`TransactionStore.findLimitsWithSpent`), и это единственная реализация. Тестируй их в
`ExceededTransactionsQueryIntegrationTest`, иначе появится вторая, расходящаяся с SQL.

## Обязательные обвязки интеграционного теста

```java
class MyLimitTest extends AbstractIntegrationTest {
  private static final String ACCOUNT = "0000000123";
  private static final AtomicInteger ID_SEQUENCE = new AtomicInteger();

  @Autowired private TransactionIntakeService intakeService;
  @Autowired private LimitCommandService limitCommandService;

  private void setLimitAt(String isoDate, String sum) {
    testClock.set(utc(isoDate).plusHours(10));
    limitCommandService.createLimit(ACCOUNT, ExpenseCategory.PRODUCT, new BigDecimal(sum));
  }

  // Идентификаторы последовательные: при равном времени порядок определяется сравнением id,
  // и случайные UUID сделали бы тест недетерминированным.
  private static UUID nextId() {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(ID_SEQUENCE.incrementAndGet()));
  }
}
```

Наследуй `AbstractIntegrationTest`: он даёт PostgreSQL в Testcontainers, WireMock, управляемый
`TestClock` (`testClock.set(...)` переводит время сервиса) и очистку таблиц перед каждым тестом.
Свой контейнер не поднимай.

## Операции в USD

Бери валюту `USD`, если проверяешь расчёт лимитов: внешний API курсов тогда не участвует и результат
не зависит от заглушек. Проверка интеграции с провайдером — отдельная задача
(`ExchangeRateIntegrationTest`).

Расчёт выполняется явно, а не через HTTP:

```java
ExpenseTransaction settled = intakeService.settle(pending.id());
assertThat(settled.limitExceeded()).isTrue();
```

## Проверка сохранённого флага

Если тест про пересчёт флагов, проверять нужно значение в БД, а не возвращённое объектом:
возвращённый объект — это результат текущего расчёта, а проверяешь ты его влияние на другие записи.

```java
private boolean storedFlagOf(UUID transactionId) {
  return jdbcTemplate.queryForObject(
      "SELECT limit_exceeded FROM expense_transaction WHERE id = ?", Boolean.class, transactionId);
}
```

## Правила, которые тест обязан зафиксировать

- Строгое сравнение: остаток ровно 0 даёт `limit_exceeded = false`.
- Месячные границы в UTC: операция с другим часовым поясом попадает в месяц по UTC.
- Категории независимы: расходы `product` не влияют на флаг `service`.
- Смена лимита внутри месяца не меняет флаги более ранних операций (лимит не применяется задним
  числом) — но пересчитывает флаги более поздних, см. `raisingLimitClearsFlagOfTransactionDatedInFuture`
  и `loweringLimitSetsFlagOnLaterTransactions`.
- Ограничение попыток: после `exchange.settlement.max-attempts` транзакция становится `FAILED`, а не
  остаётся `PENDING` навсегда; повторный `settle` её досчитывает.
- Поздняя операция с ранней датой пересчитывает флаги более поздних операций.
- Остаток считается от расходов всего месяца с 1-го числа, а не от даты установки лимита.

## Запуск

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=LimitCalculatorTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=LimitScenarioIntegrationTest test
```

Нужен запущенный Docker. Интеграционные тесты занимают ~20 с на прогон из-за поднятия контейнера.

## Чего не делать

- Не вызывай `transactionStore.findResolvedInPeriod` и не пересчитывай сумму вручную «для
  проверки»: это продублирует `LimitCalculator` и тест будет проверять сам себя.
- Не используй `Thread.sleep` для ожидания расчёта. Расчёт синхронный в тесте, либо используй
  `Awaitility` (он есть в classpath) для фонового планировщика.
- Не проверяй `limit_exceeded` по возвращённому из `settle(...)` объекту, если речь о влиянии на
  другие записи: возвращённый объект отражает только текущий расчёт.
- Не добавляй `@Testcontainers` или свой контейнер: см. объяснение в `AbstractIntegrationTest`.
- Не модифицируй production-код ради прохождения теста. Если тест вскрыл ошибку расчёта —
  исправляй расчёт, а не ожидание.

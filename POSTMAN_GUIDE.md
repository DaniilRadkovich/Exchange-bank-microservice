# Postman Guide — ExchangeService

Коллекция подготовлена на основе контроллеров, DTO и интеграционных тестов (`ExpenseApiIntegrationTest`, `ApiErrorContractIntegrationTest`, `TransactionSettlementIntegrationTest`).

## 1. Запуск сервиса

Нужен JDK 21. Опции запуска:

**Dev-профиль (c приложением, готовым к работе):**
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Базовый URL по умолчанию: `http://localhost:8080`.

## 2. Импорт коллекции

1. Открыть Postman → Import → Upload Files
2. Выбрать `exchangeservice.postman_collection.json`
3. Импортировать

Коллекция содержит переменные:
- `baseUrl` = `http://localhost:8080`
- `accountFrom` = `0000000123`
- `accountTo` = `0000009999`
- `accountOther` = `0000000777`
- `transactionId` — автозаполняется после успешного POST /transactions (тест-скрипт)

## 3. Ключевые особенности API

- `POST /api/v1/transactions` — приём транзакции. Возвращает `202 Accepted`, статус `PENDING`. Расчёт usd_rate/amount_usd/limit_exceeded выполняется **асинхронно** (не дожидается внешнего API). Требование ТЗ: приём не зависит от доступности биржи.
- `GET /api/v1/transactions/{transactionId}` — состояние расчёта. После асинхронной обработки статус может стать `SETTLED` (или `FAILED` при ошибке), заполняются `usd_rate`, `amount_usd`, `limit_exceeded`.
- `POST /api/v1/limits` — создать месячный лимит (201 Created). Дата установки (`limit_datetime`) проставляется сервером (Clock), клиент её не задаёт. Нельзя обновить существующий лимит — создаётся новая запись.
- `GET /api/v1/limits?account_from=...` — список лимитов счёта, **новыми первыми**. Для пары (счёт+категория), у которой есть расход в текущем месяце, но нет лимита этой пары **в текущем месяце** (лимит January не действует на February), добавляется **лимит по умолчанию** 1000 USD (limit_id = `null`, limit_datetime = начало месяца в UTC).
- `GET /api/v1/limits/exceeded?account_from=...` — транзакции, превысившие лимит (ТЗ п.6). Содержит параметры превышенного лимита, `amount_usd`, `usd_rate`, `spent_usd`, `exceeded_by_usd`.
- Ошибки — `application/problem+json` (RFC 9457), типы вида `https://exchangeservice.example.com/problems/...`. Коды: 400 (валидация/неизвестные поля/неверный формат), 404, 405, 406, 415, 422 (семантически неприемлемо), 409 (конфликт при создании лимита).

Именования полей в snake_case строго соответствуют ТЗ.

## 4. Проверка по ключевым тест-кейсам (из ТЗ и интеграционных тестов)

Ниже — минимальные сценарии для ручной проверки. Для USD (как в большинстве примеров ниже) внешний API курсов не используется — удобно для проверки.

### Сценарий A. Базовый приём транзакции
1. `POST /api/v1/transactions` (первый запрос в коллекции) — отправить с суммой 1000.00 USD, product, 2022-01-02T10:00:00Z
2. Ожидаем: 202, status=PENDING, limit_exceeded=null, amount_usd=null, currency_shortname=USD, transaction_id заполнен
3. `GET /api/v1/transactions/{id}` — проверить состояние. После обработки (если есть доступ к БД/settlement) могут заполниться usd_rate (для USD обычно 1.0000000000), amount_usd=1000.00, limit_exceeded может быть null или false в зависимости от лимитов

### Сценарий B. Лимит по умолчанию + превышение (классика)
1. `POST /transactions` sum=500.00, product, 2022-01-02T10:00:00Z (USD)
2. Дождаться расчёта (settled) — можно повторить GET транзакции
3. `POST /transactions` sum=600.00, product, 2022-01-03T10:00:00Z
4. Дождаться расчёта
5. `GET /limits?account_from={{accountFrom}}` — должен вернуться массив с 1 элементом: limit_id=null, limit_sum=1000.00 USD, limit_datetime=2022-01-01T00:00:00Z (начало месяца UTC), spent_usd=1100.00, remaining_usd=-100.00, expense_category=product
6. `GET /limits/exceeded?account_from={{accountFrom}}` — 1 запись: вторая транзакция (600.00) превысила лимит 1000.00. Проверить: limit_sum=1000.00, limit_currency_shortname=USD, amount_usd=600.00, spent_usd=1100.00, exceeded_by_usd=100.00

### Сценарий C. Установленный лимит (POST /limits)
1. `POST /limits` body: {"account_from":"{{accountFrom}}","expense_category":"service","limit_sum":2000.00} → 201
   - limit_id не null, limit_sum=2000.00, limit_currency_shortname=USD, expense_category=service, limit_datetime не null
2. Отправить транзакции service суммарно >2000.00 в том же месяце
3. `GET /limits/exceeded` покажет превышение по service с этим лимитом
4. `GET /limits` вернёт лимиты **новыми первыми** (установленный сервисный 2000.00 будет первым, если другие есть)

### Сценарий D. Границы месяца (UTC)
Ключевой момент: границы месяца — **UTC** (BudgetPeriod.LIMIT_TIMEZONE=UTC). Транзакции разных месяцев считаются в разных периодах.
Примеры дат (UTC):
- Начало января: 2022-01-01T00:00:00Z
- Конец января: 2022-01-31T23:59:59.999999Z (включительно по логике периода)
- Переход в февраль: 2022-02-01T00:00:00Z

Рекомендуется проверить: транзакция 2022-01-31T23:59:59Z и 2022-02-01T00:00:00Z накапливаются в разных месяцах.

### Сценарий E. Кумулятивный limit_exceeded
limit_exceeded сравнивается с **накопленной суммой «до и включая текущую»** по лимиту, действовавшему на момент операции (не с остатком). При смене лимита внутри месяца порог меняется для более поздних операций (и может пересчитываться для уже посчитанных периодов — поведение соответствует реализации).

### Сценарий F. Ошибки валидации (400/422)
Проверить валидационные кейсы:
- Счета не 10 цифр (account_from="123") → 400 (Bean Validation)
- currency_shortname не 3 буквы ("US") или не буквы ("12D") → 400
- sum <= 0 ("0.00", "-5.00") → 400 (DecimalMin 0.01)
- sum с >2 знаками после точки ("100.001") → 400 (@Digits fraction=2)
- expense_category не product/service ("food") → 400 (регулярка)
- datetime null → 400
- неизвестное поле в JSON (например limit_datetime при POST /limits) → 400 + detail содержит имя поля (контракт)

Семантически неприемлемые значения могут давать 422 (ProblemDetail с type .../unprocessable-entity).

### Сценарий G. Ошибки протокола (из ApiErrorContract)
- GET /api/v1/limits/exceeded без account_from → 400, content-type application/problem+json, type содержит validation
- GET /api/v1/transactions/not-a-uuid → 400 (malformed path variable)
- POST /api/v1/transactions/{uuid} (несуществующий метод) → 405, включает Allow
- GET /api/v1/unknown → 404
- POST /api/v1/limits с Content-Type text/plain → 415
- Успешный GET /api/v1/limits?account_from=... → 200 application/json (не problem+json)

### Сценарий H. EUR/не-USD валюты
Для не-USD транзакций сервис запрашивает курс (через внешний провайдер). В dev/тестовой среде могут использоваться заглушки (WireMock) — в реальном запуске без заглушек запрос пойдёт во внешний API. Приём (202 PENDING) не зависит от доступности API.

## 5. Практические советы

- После массовой отправки транзакций: состояние (usd_rate, amount_usd, limit_exceeded, status) смотрите через `GET /api/v1/transactions/{id}`. Асинхронная обработка может занимать некоторое время (особенно при параллельных операциях).
- Для проверки пересчёта флагов при смене лимита: установите лимит, отправьте транзакции, создайте новый лимит (позже по времени), посмотрите `GET /limits/exceeded` и статусы транзакций — флаги могут пересчитываться для периода (recalculatePeriod).
- В интеграционных тестах используются USD, чтобы исключить зависимость от внешнего API — рекомендуем то же самое для базовой проверки REST-контракта.

## 6. Быстрый чек-лист (must-pass)

- [ ] POST /transactions → 202, PENDING, snake_case поля
- [ ] GET /transactions/{id} → 200, возвращает текущее состояние
- [ ] POST /limits → 201, limit_datetime от сервера, limit_id есть
- [ ] GET /limits → 200, newest-first, default limit (null id) при наличии расходов без установленного лимита
- [ ] GET /limits/exceeded → 200, массив (пустой или с записями), содержит limit_sum/datetime/currency + spent_usd/exceeded_by_usd
- [ ] Ошибки 400/404/405/415 → application/problem+json с type/title/detail/status
- [ ] Unknown JSON field → 400 + detail упоминает поле
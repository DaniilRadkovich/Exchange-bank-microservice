# Быстрые тест-кейсы для Postman

Набор готовых примеров для быстрой проверки по типичным сценариям.

## 1) Установить лимит и превысить его (классика ТЗ)

```http
POST {{baseUrl}}/api/v1/limits
Content-Type: application/json

{
  "account_from": "{{accountFrom}}",
  "expense_category": "product",
  "limit_sum": 1000.00
}
```

Ожидаем: 201. Сохраните limit_id из ответа (опционально).

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{
  "account_from": "{{accountFrom}}",
  "account_to": "{{accountTo}}",
  "currency_shortname": "USD",
  "sum": 500.00,
  "expense_category": "product",
  "datetime": "2022-01-02T10:00:00Z"
}
```

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{
  "account_from": "{{accountFrom}}",
  "account_to": "{{accountTo}}",
  "currency_shortname": "USD",
  "sum": 600.00,
  "expense_category": "product",
  "datetime": "2022-01-03T10:00:00Z"
}
```

После обработки:
- `GET {{baseUrl}}/api/v1/limits?account_from={{accountFrom}}` → spent_usd ~1100.00, remaining_usd ~ -100.00
- `GET {{baseUrl}}/api/v1/limits/exceeded?account_from={{accountFrom}}` → 1 элемент, exceeded_by_usd 100.00, limit_sum 1000.00

## 2) Лимит по умолчанию (без явного лимита)

Без вызова POST /limits:
```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{
  "account_from": "{{accountOther}}",
  "account_to": "{{accountTo}}",
  "currency_shortname": "USD",
  "sum": 600.00,
  "expense_category": "product",
  "datetime": "2022-01-05T10:00:00Z"
}
```

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{
  "account_from": "{{accountOther}}",
  "account_to": "{{accountTo}}",
  "currency_shortname": "USD",
  "sum": 500.00,
  "expense_category": "product",
  "datetime": "2022-01-06T10:00:00Z"
}
```

`GET {{baseUrl}}/api/v1/limits?account_from={{accountOther}}` → limit_id null, limit_sum 1000.00, limit_datetime 2022-01-01T00:00:00Z, spent 1100.00.

## 3) Границы месяца UTC

Январь vs Февраль:
```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{"account_from":"{{accountFrom}}","account_to":"{{accountTo}}","currency_shortname":"USD","sum":400.00,"expense_category":"product","datetime":"2022-01-31T23:59:59Z"}
```

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{"account_from":"{{accountFrom}}","account_to":"{{accountTo}}","currency_shortname":"USD","sum":400.00,"expense_category":"product","datetime":"2022-02-01T00:00:00Z"}
```

Первые 400.00 попадают в январь, вторые — в февраль. Лимит по умолчанию 1000 USD применяется отдельно к каждому месяцу.

## 4) Валидация — негативные кейсы (ожидаем 400)

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{"account_from":"123","account_to":"{{accountTo}}","currency_shortname":"USD","sum":100.00,"expense_category":"product","datetime":"2022-01-01T10:00:00Z"}
```

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{"account_from":"{{accountFrom}}","account_to":"{{accountTo}}","currency_shortname":"US","sum":100.00,"expense_category":"product","datetime":"2022-01-01T10:00:00Z"}
```

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{"account_from":"{{accountFrom}}","account_to":"{{accountTo}}","currency_shortname":"USD","sum":0.00,"expense_category":"product","datetime":"2022-01-01T10:00:00Z"}
```

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{"account_from":"{{accountFrom}}","account_to":"{{accountTo}}","currency_shortname":"USD","sum":100.001,"expense_category":"product","datetime":"2022-01-01T10:00:00Z"}
```

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{"account_from":"{{accountFrom}}","account_to":"{{accountTo}}","currency_shortname":"USD","sum":100.00,"expense_category":"food","datetime":"2022-01-01T10:00:00Z"}
```

## 5) Ручной досчёт операции

Служебный сценарий для транзакции, которая не рассчиталась автоматически (курс не пришёл, попытки
исчерпаны, статус `FAILED`).

```http
POST {{baseUrl}}/api/v1/transactions
Content-Type: application/json

{
  "account_from": "{{accountFrom}}",
  "account_to": "{{accountTo}}",
  "currency_shortname": "KZT",
  "sum": 10000.00,
  "expense_category": "product",
  "datetime": "2022-01-10T10:00:00Z"
}
```

Возьмите `transaction_id` из ответа и повторите запрос, пока не получите `status: FAILED`.

```http
POST {{baseUrl}}/api/v1/transactions/{{transactionId}}/settle
```

Ожидаем: `200` с `status: RATE_RESOLVED` и посчитанным `limit_exceeded`, либо `202` с
`status: PENDING`, если курс всё ещё недоступен (попытка потрачена). Для несуществующей операции —
`404`.

## 6) Ошибки протокола

```http
GET {{baseUrl}}/api/v1/limits/exceeded
```
→ 400 (нет account_from), application/problem+json

```http
GET {{baseUrl}}/api/v1/transactions/not-a-uuid
```
→ 400

```http
POST {{baseUrl}}/api/v1/transactions/{{$guid}}
```
→ 405 (метод не поддерживается)

```http
GET {{baseUrl}}/api/v1/no-such-path
```
→ 404

```http
POST {{baseUrl}}/api/v1/limits
Content-Type: text/plain

account_from=0000000123
```
→ 415

```http
POST {{baseUrl}}/api/v1/limits
Content-Type: application/json

{"account_from":"{{accountFrom}}","expense_category":"product","limit_datetime":"2022-01-10T00:00:00Z"}
```
→ 400 (неизвестное поле) + detail содержит "limit_datetime"
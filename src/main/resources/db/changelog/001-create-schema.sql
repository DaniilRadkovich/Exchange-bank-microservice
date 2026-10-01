--liquibase formatted sql

--changeset exchange:001-create-schema
-- Полиномиально независимые таблицы сервиса контроля расходных лимитов.
-- Подход schema first: схема описана здесь, JPA работает в режиме validate.

CREATE TABLE expense_limit (
    id                UUID        PRIMARY KEY,
    account_from      VARCHAR(10) NOT NULL,
    expense_category  VARCHAR(16) NOT NULL,
    limit_sum         NUMERIC(19, 2) NOT NULL,
    limit_currency    CHAR(3)     NOT NULL DEFAULT 'USD',
    limit_datetime    TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_expense_limit_sum_positive CHECK (limit_sum > 0),
    CONSTRAINT ck_expense_limit_category CHECK (expense_category IN ('product', 'service')),
    CONSTRAINT ck_expense_limit_currency CHECK (limit_currency = 'USD')
);

COMMENT ON TABLE expense_limit IS 'Месячные лимиты расходов в USD. Лимиты неизменяемы: обновление запрещено (ТЗ п.5), новый лимит — новая запись.';
COMMENT ON COLUMN expense_limit.limit_datetime IS 'Дата установки лимита, проставляется сервисом (текущее время бина Clock), клиент не может задать её в прошлом или будущем.';
COMMENT ON COLUMN expense_limit.account_from IS 'Банковский счёт клиента (10 цифр) — идентификатор владельца лимита.';

CREATE TABLE expense_transaction (
    id                UUID           PRIMARY KEY,
    account_from      VARCHAR(10)    NOT NULL,
    account_to        VARCHAR(10)    NOT NULL,
    currency_code     CHAR(3)        NOT NULL,
    amount            NUMERIC(19, 2) NOT NULL,
    expense_category  VARCHAR(16)    NOT NULL,
    occurred_at       TIMESTAMPTZ    NOT NULL,
    usd_rate          NUMERIC(19, 4),
    amount_usd        NUMERIC(19, 2),
    status            VARCHAR(20)    NOT NULL,
    limit_exceeded    BOOLEAN,
    settlement_attempts INT         NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_expense_tx_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_expense_tx_status CHECK (status IN ('PENDING', 'RATE_RESOLVED', 'FAILED')),
    CONSTRAINT ck_expense_tx_category CHECK (expense_category IN ('product', 'service')),
    CONSTRAINT ck_expense_tx_resolved_consistent CHECK (
        (status = 'RATE_RESOLVED' AND usd_rate IS NOT NULL AND amount_usd IS NOT NULL AND limit_exceeded IS NOT NULL)
            OR (status <> 'RATE_RESOLVED' AND amount_usd IS NULL AND limit_exceeded IS NULL)
        )
);

COMMENT ON TABLE expense_transaction IS 'Расходные операции клиентов. Сумма в валюте операции хранится всегда, сумма в USD — после применения биржевого курса.';
COMMENT ON COLUMN expense_transaction.usd_rate IS 'Применённый биржевой курс: close, либо previous_close, если на дату операции торгов не было.';
COMMENT ON COLUMN expense_transaction.status IS 'PENDING — принята, курс и флаг limit_exceeded досчитываются; RATE_RESOLVED — расчёт завершён; FAILED — курс получить не удалось.';
COMMENT ON COLUMN expense_transaction.settlement_attempts IS 'Число попыток дорасчёта: ограничивает бесконечные повторы при недоступном внешнем API курсов.';

CREATE TABLE exchange_rate (
    id               UUID         PRIMARY KEY,
    base_currency    CHAR(3)      NOT NULL,
    quote_currency   CHAR(3)      NOT NULL DEFAULT 'USD',
    rate_date        DATE         NOT NULL,
    close_rate       NUMERIC(19, 4),
    previous_close   NUMERIC(19, 4),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uc_exchange_rate_pair_date UNIQUE (base_currency, quote_currency, rate_date),
    CONSTRAINT ck_exchange_rate_positive CHECK (
        (close_rate IS NULL OR close_rate > 0) AND (previous_close IS NULL OR previous_close > 0)
        )
);

COMMENT ON TABLE exchange_rate IS 'Собственный кэш биржевых курсов. Заполняется из внешнего API и далее используется преимущественно из БД, чтобы не платить за каждый запрос (ТЗ п.3).';

CREATE TABLE spend_period_lock (
    account_from     VARCHAR(10) NOT NULL,
    expense_category VARCHAR(16) NOT NULL,
    budget_period    VARCHAR(7)  NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_spend_period_lock PRIMARY KEY (account_from, expense_category, budget_period)
);

COMMENT ON TABLE spend_period_lock IS 'Строка-замок на пару «счёт + категория + месяц». Берётся пессимистичной блокировкой (SELECT ... FOR UPDATE), чтобы параллельные транзакции одного клиента не увидели один и тот же остаток лимита (ТЗ: многопоточность).';

--changeset exchange:002-indexes
-- Индексы под три доступа: чтение лимитов по счёту+категории в хронологическом порядке,
-- выборка транзакций периода для расчёта остатка, выборка превышений для ТЗ п.6.

CREATE INDEX ix_expense_limit_lookup
    ON expense_limit (account_from, expense_category, limit_datetime DESC);

CREATE INDEX ix_expense_tx_period
    ON expense_transaction (account_from, expense_category, occurred_at);

CREATE INDEX ix_expense_tx_exceeded
    ON expense_transaction (account_from, expense_category, limit_exceeded, occurred_at)
    WHERE limit_exceeded;

CREATE INDEX ix_expense_tx_pending
    ON expense_transaction (status, occurred_at)
    WHERE status <> 'RATE_RESOLVED';

CREATE INDEX ix_exchange_rate_lookup
    ON exchange_rate (base_currency, quote_currency, rate_date DESC);

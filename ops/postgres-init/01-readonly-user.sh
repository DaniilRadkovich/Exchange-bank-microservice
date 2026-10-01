#!/bin/bash
# Пользователь базы данных только для чтения, которым подключается MCP-сервер агента.
#
# Смысл: агент может выполнять SELECT и видеть реальную схему и данные проекта, но не может
# ничего изменить. Права на запись нужны только сервису, который работает от пользователя
# exchange.
#
# Пароль не хранится в репозитории и передаётся переменной окружения. Скрипт выполняется один
# раз — при инициализации пустого тома, — поэтому переменная обязана быть задана до первого
# запуска compose. Если том exchange-pgdata уже есть, скрипт не выполнится: тогда
# пользователя нужно создать вручную, см. README.
#
# Пользователь создаётся отдельным скриптом, а не через POSTGRES_USER: официальный образ
# создаёт только одного суперпользователя.

set -euo pipefail

: "${EXCHANGE_READONLY_PASSWORD:?EXCHANGE_READONLY_PASSWORD is required to create the read-only user}"

# ON_ERROR_STOP=1 иначе psql продолжит после ошибки и скрипт отработает «успешно».
#
# Роль создаётся через \if, а не через DO с долларовым quoting: внутри $$...$$ psql не
# подставляет :'readonly_password', и такой вариант падает с syntax error.
psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     --set=readonly_password="$EXCHANGE_READONLY_PASSWORD" <<'SQL'
SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'exchange_readonly') AS role_exists \gset

\if :role_exists
\echo 'role exchange_readonly already exists, nothing to do'
\else
CREATE ROLE exchange_readonly LOGIN PASSWORD :'readonly_password';

-- Подключение нужно MCP-серверу, который обращается к БД через опубликованный порт Docker,
-- а не через unix-сокет контейнера.
GRANT CONNECT ON DATABASE exchange TO exchange_readonly;

-- Права только на чтение.
GRANT USAGE ON SCHEMA public TO exchange_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO exchange_readonly;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO exchange_readonly;

-- Liquibase создаёт таблицы уже после инициализации, поэтому действуют и будущие таблицы:
-- иначе агент не увидит схему из миграций.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO exchange_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON SEQUENCES TO exchange_readonly;
\endif
SQL

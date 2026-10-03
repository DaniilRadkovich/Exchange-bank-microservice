package com.idftech.exchangeservice.infra.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Репозитории проекций, которые требуют SQL, а не JPQL.
 *
 * <p>ТЗ прямо требует применить JOIN с подзапросом, агрегирующие функции и группировку при
 * получении лимитов. Ниже два запроса, в которых эти конструкции выражают предметную задачу, а не
 * искусственное усложнение:
 *
 * <ul>
 *   <li>{@link #findExceeded} — ТЗ п.6: {@code JOIN} с коррелированным подзапросом находит лимит,
 *       действовавший на момент транзакции; оконная агрегирующая функция {@code SUM} даёт
 *       накопленную сумму периода на момент превышения, которая показывается клиенту.
 *   <li>{@link #findLimitsWithSpentAmount} — лимиты с потраченной суммой и остатком:
 *       {@code JOIN} + подзапрос + {@code GROUP BY} + {@code SUM}.
 * </ul>
 *
 * <p>Признак превышения берётся из сохранённого флага {@code limit_exceeded}: он рассчитан
 * доменным сервисом под пессимистичной блокировкой, и дублировать эту логику в SQL означало бы
 * получить два источника правды. Оконная сумма в ответе — производная величина для клиента.
 */
public interface LimitAnalyticsQueryRepository extends Repository<ExpenseTransactionEntity, UUID> {

/**
   * Транзакции, превысившие лимит, с параметрами превышенного лимита (ТЗ п.6).
   *
   * <p>Лимит по умолчанию (1000 USD с датой в начале месяца) физически не хранится, поэтому
   * используется {@code LEFT JOIN} с {@code COALESCE}: строка подставляется только если в БД нет
   * лимита, установленного не позже даты транзакции.
   *
   * <p>Оконная сумма считается по <b>всем</b> разрешённым транзакциям периода, а фильтр
   * {@code limit_exceeded = TRUE} применяется во внешнем запросе. Если отфильтровать превышенные
   * транзакции до агрегации, накопленный итог исказится: непревышенные операции выпали бы из суммы.
   * Например, при расходах 500 (не превышен) и 600 (превышен) итог должен быть 1100, а не 600.
   *
   * <p>Раздел окна включает календарный месяц в UTC-зоне лимитов: сумма накапливается независимо в
   * каждом месяце, как и флаг {@code limit_exceeded}, который приложение считает по месяцам. Без
   * месяца в разделе итог января продолжил бы расти в феврале и противоречил бы сохранённому флагу.
   *
   * <p>По той же причине месяц ограничивает и поиск самого лимита: лимит действует в месяце его
   * установки, и операция следующего месяца считается против лимита по умолчанию, а не против
   * вчерашнего. Это ровно то, что делает доменный сервис через {@code BudgetPeriod}.
   */
  @Query(
      value =
          """
          WITH resolved AS (
              SELECT t.id               AS transaction_id,
                     t.account_from     AS account_from,
                     t.expense_category AS expense_category,
                     t.occurred_at      AS occurred_at,
                     t.amount_usd       AS amount_usd,
                     t.limit_exceeded   AS limit_exceeded,
                     SUM(t.amount_usd) OVER (
                         PARTITION BY t.account_from, t.expense_category,
                                     date_trunc('month', t.occurred_at AT TIME ZONE :limitTimezone)
                         ORDER BY t.occurred_at, t.id
                         ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                     ) AS running_total_usd
              FROM expense_transaction t
              WHERE t.account_from = :accountFrom
                AND t.status = 'RATE_RESOLVED'
          )
          SELECT r.transaction_id,
                 r.account_from     AS account_from,
                 t.account_to       AS account_to,
                 t.currency_code    AS currency_code,
                 t.amount           AS amount,
                 t.expense_category AS expense_category,
                 t.occurred_at      AS occurred_at,
                 t.amount_usd       AS amount_usd,
                 t.usd_rate         AS usd_rate,
                 COALESCE(l.limit_sum, CAST(:defaultLimitSum AS NUMERIC(19, 2))) AS limit_sum,
                 COALESCE(l.limit_currency, :defaultCurrency)                    AS limit_currency,
                 COALESCE(
                     l.limit_datetime,
                     date_trunc('month', t.occurred_at AT TIME ZONE :limitTimezone) AT TIME ZONE :limitTimezone
                 ) AS limit_datetime,
                 r.running_total_usd AS running_total_usd
          FROM resolved r
          JOIN expense_transaction t ON t.id = r.transaction_id
LEFT JOIN expense_limit l
            ON l.account_from = r.account_from
           AND l.expense_category = r.expense_category
           AND l.limit_datetime = (
               SELECT MAX(inner_l.limit_datetime)
               FROM expense_limit inner_l
               WHERE inner_l.account_from = r.account_from
                 AND inner_l.expense_category = r.expense_category
                 AND inner_l.limit_datetime <= r.occurred_at
                 -- Лимит принадлежит календарному месяцу, поэтому искать его нужно только внутри
                 -- месяца операции. Без этой границы лимит, установленный в прошлом месяце,
                 -- подставлялся бы к операции текущего: февральская операция получила бы в ответе
                 -- январский лимит вместо лимита по умолчанию, хотя её флаг посчитан по умолчанию.
                 AND inner_l.limit_datetime >= date_trunc(
                         'month', r.occurred_at AT TIME ZONE :limitTimezone) AT TIME ZONE :limitTimezone
           )
          WHERE r.limit_exceeded = TRUE
          ORDER BY r.occurred_at, r.transaction_id
          """,
      nativeQuery = true)
  List<ExceededRow> findExceeded(
      @Param("accountFrom") String accountFrom,
      @Param("defaultLimitSum") BigDecimal defaultLimitSum,
      @Param("defaultCurrency") String defaultCurrency,
      @Param("limitTimezone") String limitTimezone);

/**
   * Лимиты счёта вместе с фактически потраченной суммой и остатком.
   *
   * <p>Подзапрос с {@code GROUP BY} и {@code SUM} даёт расход периода по парам «счёт + категория», а
   * {@code LEFT JOIN} присоединяет его к каждому лимиту. Суммируются все разрешённые транзакции
   * периода независимо от дат установки лимитов — та же семантика остатка, что и в
   * {@code LimitCalculator} (в ТЗ при лимите 2000 USD от 10.01 остаток равен 900).
   *
   * <p>Возвращаются все лимиты счёта, новые первыми, а не только последний по паре: список лимитов
   * показывает историю изменений, и клиентский {@code GET /limits} отдаёт все записи.
   *
   * <p>Расход периода присоединяется <b>только к лимитам самого периода</b>, и это не оптимизация, а
   * требование согласованности: лимит принадлежит календарному месяцу, и ровно так же считает флаг —
   * {@code LimitCalculator} берёт лимиты через {@code findLimitsInPeriod}, а SQL п.6 ищет лимит
   * внутри месяца операции. Без границы в {@code JOIN} февральский расход присоединялся бы к январскому
   * лимиту, и клиент получал бы в двух ответах разные лимиты для одной операции: «осталось 3900» в
   * {@code GET /limits} и «превышен лимит 1000» в {@code GET /limits/exceeded}. Лимиты других месяцев
   * остаются в ответе как история, но с нулевым расходом: к текущему месяцу они не относятся.
   *
   * <p>Вторая ветка {@code UNION ALL} добавляет лимит по умолчанию для тех пар, у которых есть расход
   * в текущем месяце, но нет лимита <b>этого месяца</b>. Без неё клиент видел бы расход, посчитанный по
   * умолчанию 1000 USD, и пустой список лимитов: тот же расчёт уже выставил флаги превышения, а
   * лимита, по которому они считались, в ответе не было. Лимит по умолчанию физически не хранится,
   * поэтому {@code limit_id} такой строки — {@code NULL}, а сумма, валюта и момент установки приходят
   * параметрами из {@code LimitProperties} и {@code :periodStart}: тот же первый день месяца, что
   * использует {@code LimitCalculator.defaultLimit}.
   *
   * <p>Границы периода в обеих ветках одни и те же, что и в {@code findLimitsInPeriod}: начало включительно,
   * конец не включительно. Лимит, установленный ровно в полночь первого числа, принадлежит этому месяцу,
   * а ровно в полночь первого числа следующего — уже следующему.
   *
   * <p>Остаток у лимитов периода считается от суммы всего месяца независимо от момента установки: та же
   * семантика, что в {@code LimitCalculator} и в ТЗ (при лимите 2000 USD от 10.01 остаток равен 900).
   * Поэтому если лимит установили 20-го, а расход шёл с 5-го, часть расхода попала под лимит по
   * умолчанию, а остаток показан от нового лимита — приближение месячной величины. Параметры лимита
   * по каждой операции клиент видит в {@code GET /limits/exceeded}.
   */
  @Query(
      value =
          """
          WITH spent AS (
              SELECT t.account_from,
                     t.expense_category,
                     SUM(t.amount_usd) AS spent_usd
              FROM expense_transaction t
              WHERE t.account_from = :accountFrom
                AND t.status = 'RATE_RESOLVED'
                AND t.occurred_at >= :periodStart
                AND t.occurred_at < :periodEnd
              GROUP BY t.account_from, t.expense_category
          )
          SELECT l.id              AS limit_id,
                 l.account_from     AS account_from,
                 l.expense_category AS expense_category,
                 l.limit_sum        AS limit_sum,
                 l.limit_currency   AS limit_currency,
                 l.limit_datetime   AS limit_datetime,
                 COALESCE(s.spent_usd, CAST(0 AS NUMERIC(19, 2))) AS spent_usd,
                 l.limit_sum - COALESCE(s.spent_usd, CAST(0 AS NUMERIC(19, 2))) AS remaining_usd
          FROM expense_limit l
          LEFT JOIN spent s
            ON s.account_from = l.account_from
           AND s.expense_category = l.expense_category
           AND l.limit_datetime >= CAST(:periodStart AS TIMESTAMPTZ)
           AND l.limit_datetime < CAST(:periodEnd AS TIMESTAMPTZ)
          WHERE l.account_from = :accountFrom
          UNION ALL
          SELECT NULL::uuid        AS limit_id,
                 s.account_from     AS account_from,
                 s.expense_category AS expense_category,
                 CAST(:defaultSum AS NUMERIC(19, 2)) AS limit_sum,
                 CAST(:defaultCurrency AS VARCHAR(3)) AS limit_currency,
                 CAST(:periodStart AS TIMESTAMPTZ) AS limit_datetime,
                 s.spent_usd AS spent_usd,
                 CAST(:defaultSum AS NUMERIC(19, 2)) - s.spent_usd AS remaining_usd
          FROM spent s
          WHERE NOT EXISTS (
              SELECT 1
              FROM expense_limit other
              WHERE other.account_from = s.account_from
                AND other.expense_category = s.expense_category
                AND other.limit_datetime >= CAST(:periodStart AS TIMESTAMPTZ)
                AND other.limit_datetime < CAST(:periodEnd AS TIMESTAMPTZ)
          )
          ORDER BY limit_datetime DESC, limit_id DESC NULLS LAST
          """,
      nativeQuery = true)
  List<LimitWithSpentRow> findLimitsWithSpentAmount(
      @Param("accountFrom") String accountFrom,
      @Param("periodStart") Instant periodStart,
      @Param("periodEnd") Instant periodEnd,
      @Param("defaultSum") BigDecimal defaultSum,
      @Param("defaultCurrency") String defaultCurrency);

  /** Строка проекции п.6: транзакция, лимит и накопленная сумма на момент превышения. */
  interface ExceededRow {
    UUID getTransactionId();

    String getAccountFrom();

    String getAccountTo();

    String getCurrencyCode();

    BigDecimal getAmount();

    String getExpenseCategory();

    Instant getOccurredAt();

    BigDecimal getAmountUsd();

    BigDecimal getUsdRate();

    BigDecimal getLimitSum();

    Instant getLimitDatetime();

    String getLimitCurrency();

    BigDecimal getRunningTotalUsd();
  }

  /** Строка проекции «лимит + потрачено + остаток». */
  interface LimitWithSpentRow {
    UUID getLimitId();

    String getAccountFrom();

    String getExpenseCategory();

    BigDecimal getLimitSum();

    String getLimitCurrency();

    Instant getLimitDatetime();

    BigDecimal getSpentUsd();

    BigDecimal getRemainingUsd();
  }
}

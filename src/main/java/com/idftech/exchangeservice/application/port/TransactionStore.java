package com.idftech.exchangeservice.application.port;

import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExceededTransaction;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Порт хранилища расходных транзакций.
 *
 * <p>Сигнатуры намеренно не раскрывают способ доступа (JPQL, нативный SQL, несколько запросов):
 * доменный и прикладной слои зависят только от этого интерфейса, а реализация в
 * {@code infra.persistence} решает, как получить данные.
 */
public interface TransactionStore {

  /**
   * Создаёт запись транзакции, если её ещё нет, и создаёт строку-замок для пары «счёт + категория +
   * месяц». Возвращает состояние строки из базы.
   *
   * <p>Приём идемпотентен: при повторной доставке того же {@code id} возвращается уже существующая
   * транзакция, а она не затирается. Проверять существование до вставки на стороне Java нельзя —
   * параллельные ретраи обошли бы такую проверку, и один из них получил бы нарушение первичного
   * ключа вместо успешного приёма. Реализация обязана быть атомарной на уровне БД.
   */
  ExpenseTransaction saveIfAbsent(ExpenseTransaction transaction);

  Optional<ExpenseTransaction> findById(UUID id);

  /**
   * Блокирует пару «счёт + категория + месяц» пессимистичной блокировкой до конца текущей транзакции
   * БД.
   *
   * <p>Это механизм корректности при многопоточности: два параллельных расчёта флага для одного
   * клиента выстраиваются в очередь, и второй видит уже обновлённый остаток лимита, а не тот же
   * самый.
   */
  void lockPeriod(String accountFrom, ExpenseCategory category, BudgetPeriod period);

  /**
   * Все разрешённые транзакции пары «счёт + категория» за период, упорядоченные по времени
   * совершения. Нужны для расчёта накопленной суммы и флага превышения лимита.
   */
  List<ExpenseTransaction> findResolvedInPeriod(
      String accountFrom, ExpenseCategory category, BudgetPeriod period);

  /**
   * Фиксирует результат расчёта: применённый курс, сумму в USD и флаг превышения.
   *
   * <p>Принимает только транзакцию в статусе {@code RATE_RESOLVED}. Неудачные попытки — это отдельный
   * случай, {@link #registerUnresolvedAttempt(UUID, int)}: смешивать два разных исхода в одном методе
   * значило бы получить две точки, где считается счётчик попыток.
   */
  void updateSettlement(ExpenseTransaction transaction);

  /** Транзакции, ожидающие дорасчёта, не превысившие лимит попыток; используется фоновой обработкой. */
  List<ExpenseTransaction> findPending(int maxAttempts, int batchSize);

  /**
   * Транзакции счёта, превысившие лимит, вместе с параметрами превышенного лимита (ТЗ п.6).
   *
   * <p>Реализация обязана собрать результат одним SQL-запросом с JOIN, подзапросом и
   * агрегирующими функциями — это прямое требование ТЗ, а не оптимизация.
   */
  List<ExceededTransaction> findExceededTransactions(String accountFrom);

  /**
   * Лимиты счёта вместе с потраченной суммой за период и остатком.
   *
   * <p>Вторая половина требования ТЗ п.6 про агрегирующие функции и группировку: сумма расходов
   * периода считается через {@code GROUP BY} и {@code SUM}, остаток — как разность лимита и этой
   * суммы. Суммируются все разрешённые операции периода независимо от дат установки лимитов.
   *
   * <p>Список включает и лимит по умолчанию для пар, у которых есть расход в периоде, но нет
   * установленного лимита: расчёт уже применил к ним лимит по умолчанию, и клиент должен видеть,
   * по какому лимиту считались флаги. У такой строки {@code limitId} равен {@code null} — лимит по
   * умолчанию не хранится и идентификатора не имеет.
   */
  List<LimitWithSpent> findLimitsWithSpent(String accountFrom, BudgetPeriod period);

  /**
   * Строка «лимит + потрачено + остаток»; формат переносится из нативного SQL без JPA-сущности.
   *
   * @param limitId идентификатор установленного лимита; {@code null} у лимита по умолчанию, который
   *     клиент не устанавливал и который физически не хранится
   */
  record LimitWithSpent(
      UUID limitId,
      String accountFrom,
      ExpenseCategory category,
      BigDecimal limitSum,
      Currency limitCurrency,
      OffsetDateTime limitDatetime,
      BigDecimal spentUsd,
      BigDecimal remainingUsd) {}

  /**
   * Фиксирует неудачную попытку дорасчёта.
   *
   * <p>Счётчик попыток растёт всегда, а начиная с попытки, номер которой достиг {@code maxAttempts},
   * транзакция переводится в {@code FAILED}. Дальше она больше не выбирается в
   * {@link #findPending}, но остаётся в базе и досчитывается вручную: данные приёма терять нельзя,
   * а бесконечный ретрай недоступного провайдера маскирует реальную проблему.
   */
  void registerUnresolvedAttempt(UUID id, int maxAttempts);
}

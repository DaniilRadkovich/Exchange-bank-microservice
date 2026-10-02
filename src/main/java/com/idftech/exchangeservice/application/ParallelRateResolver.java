package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.domain.ExpenseTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Первая фаза расчёта: параллельное получение курсов валют для пачки транзакций (ТЗ п.1*).
 *
 * <h2>Почему курсы идут первыми</h2>
 *
 * <p>Курс добывается из внешнего API и стоит времени ожидания сети, тогда как расчёт флага — это
 * чтение и запись в своей БД под блокировкой периода. Совмещать их в одной транзакции нельзя: под
 * блокировкой {@code spend_period_lock} внешний вызов занимал бы соединение с PostgreSQL, и все
 * параллельные расчёты одного счёта выстроились бы в очередь. Поэтому сначала курсы, потом расчёт.
 *
 * <h2>Виртуальные потоки</h2>
 *
 * <p>Задачи выполняются на виртуальных потоках: получение курса — это ожидание, а не вычисления,
 * поэтому один поток на задачу обходится почти бесплатно и не ограничивает число одновременных
 * запросов. Настоящий поток ОС на каждый HTTP-запрос был бы накладным расходом.
 *
 * <h2>Дедупликация</h2>
 *
 * <p>Внешний API платный, и пачка почти всегда состоит из повторов одной пары «валюта + дата»: за
 * месяц клиент платит в одной валюте много операций. Ключ {@link RateKey} схлопывает такие повторы
 * в один запрос. Побочная выгода — снятие гонки на уникальном ограничении
 * {@code uc_exchange_rate_pair_date}: без дедупликации два потока одновременно проверяют отсутствие
 * строки, оба решают, что её нет, и оба пытаются вставить.
 */
@Component
public class ParallelRateResolver {

  private static final Logger log = LoggerFactory.getLogger(ParallelRateResolver.class);

  private final ExchangeRateService exchangeRateService;

  public ParallelRateResolver(ExchangeRateService exchangeRateService) {
    this.exchangeRateService = exchangeRateService;
  }

  /**
   * Курс перевода в USD для каждой транзакции пачки.
   *
   * <p>Транзакции с одинаковой парой «валюта + дата» получают один запрос к провайдеру. Отсутствие
   * курса не считается ошибкой: такая транзакция вернётся с пустым результатом и останется PENDING.
   *
   * <p>Метод блокирует до завершения всех запросов. Возврат до их окончания был бы гонкой: читатель
   * увидел бы пустую карту и объявил все курсы недоступными.
   *
   * @param pending транзакции, ожидающие расчёта
   * @param rateDate дата операции в часовом поясе лимита — по ней ищется курс закрытия
   * @param executor пул, на котором выполняются запросы; в проде это виртуальные потоки
   * @return курс по идентификатору транзакции; отсутствие значения означает «курс не получен»
   */
  public Map<UUID, Optional<BigDecimal>> resolveRates(
      List<ExpenseTransaction> pending,
      Function<ExpenseTransaction, LocalDate> rateDate,
      Executor executor) {

    Map<RateKey, List<UUID>> idsByKey = groupByCurrencyAndDate(pending, rateDate);
    if (idsByKey.isEmpty()) {
      return Map.of();
    }

    Map<RateKey, CompletableFuture<Optional<BigDecimal>>> requests = new HashMap<>();
    idsByKey.keySet().forEach(key -> requests.put(key, CompletableFuture.supplyAsync(
            () -> exchangeRateService.resolveUsdRate(key.currency(), key.date()), executor)));

    Map<UUID, Optional<BigDecimal>> result = new HashMap<>();
    idsByKey.forEach(
        (key, ids) -> {
          Optional<BigDecimal> rate = joinQuietly(key, requests.get(key));
          ids.forEach(id -> result.put(id, rate));
        });

    log.debug(
        "Rate resolution requested for {} transactions, {} distinct currency/date pairs",
        pending.size(),
        idsByKey.size());
    return result;
  }

  /** Группирует транзакции по паре «валюта + дата»: одна группа — один запрос к провайдеру. */
  private Map<RateKey, List<UUID>> groupByCurrencyAndDate(
      List<ExpenseTransaction> pending, Function<ExpenseTransaction, LocalDate> rateDate) {
    Map<RateKey, List<UUID>> idsByKey = new HashMap<>();
    for (ExpenseTransaction transaction : pending) {
      RateKey key = RateKey.of(transaction, rateDate.apply(transaction));
      idsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(transaction.id());
    }
    return idsByKey;
  }

  /**
   * Ждёт курс по ключу, трактуя отказ задачи как «курс недоступен».
   *
   * <p>Провал одного запроса не должен ронять всю пачку: остальные транзакции валютно независимы и
   * должны быть рассчитаны. Транзакция без курса останется PENDING и вернётся в следующий проход.
   */
  private Optional<BigDecimal> joinQuietly(
      RateKey key, CompletableFuture<Optional<BigDecimal>> request) {
    try {
      return request.join();
    } catch (RuntimeException e) {
      log.warn(
          "Rate resolution for {}/USD on {} failed: {}", key.currency(), key.date(), e.toString());
      return Optional.empty();
    }
  }

  /**
   * Ключ дедупликации: пара «валюта + дата операции».
   *
   * <p>Курс зависит только от этих двух значений, поэтому одинаковые ключи запрашивают курс один раз.
   */
  record RateKey(String currency, LocalDate date) {

    static RateKey of(ExpenseTransaction transaction, LocalDate date) {
      return new RateKey(transaction.currency().getCurrencyCode(), date);
    }
  }
}

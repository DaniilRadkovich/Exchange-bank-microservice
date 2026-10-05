package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.domain.ExpenseTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
   * курса не считается ошибкой: такая транзакция вернётся без курса и останется PENDING.
   *
   * <p>Метод блокирует до завершения всех запросов. Возврат до их окончания был бы гонкой: читатель
   * увидел бы пустую карту и объявил все курсы недоступными.
   *
   * @param pending транзакции, ожидающие расчёта
   * @param rateDate дата операции в часовом поясе лимита — по ней ищется курс закрытия
   * @param executor пул, на котором выполняются запросы; в проде это виртуальные потоки
   * @return итог получения курса по идентификатору транзакции; {@code Failed} означает сбой нашего
   *     сервиса, а не провайдера
   */
  public Map<UUID, RateResolution> resolveRates(
      List<ExpenseTransaction> pending,
      Function<ExpenseTransaction, LocalDate> rateDate,
      Executor executor) {

    Map<RateKey, List<UUID>> idsByKey = groupByCurrencyAndDate(pending, rateDate);
    if (idsByKey.isEmpty()) {
      return Map.of();
    }

    Map<RateKey, CompletableFuture<RateResolution>> requests = new HashMap<>();
    idsByKey.keySet().forEach(key -> requests.put(key, CompletableFuture.supplyAsync(
            () -> resolveRate(key), executor)));

    Map<UUID, RateResolution> result = new HashMap<>();
    idsByKey.forEach(
        (key, ids) -> {
          RateResolution resolution = join(key, requests.get(key));
          ids.forEach(id -> result.put(id, resolution));
        });

    log.debug(
        "Rate resolution requested for {} transactions, {} distinct currency/date pairs",
        pending.size(),
        idsByKey.size());
    return result;
  }

  /**
   * Курс одной пары «валюта + дата» как итог, а не как «есть или нет».
   *
   * <p>Граница между внешней и внутренней ошибкой проходит здесь: {@link ExchangeRateService} уже
   * вернул пустой результат там, где не виноват никто, кроме провайдера, и выбросил наружу всё, что
   * случилось с нашей БД.
   */
  private RateResolution resolveRate(RateKey key) {
    return exchangeRateService
        .resolveUsdRate(key.currency(), key.date())
        .map(RateResolution::resolved)
        .orElseGet(RateResolution::unavailable);
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
   * Ждёт курс по ключу, не роняя остальную пачку.
   *
   * <p>Отказ одной задачи не должен мешать валютно независимым транзакциям, поэтому он не
   * пробрасывается наружу, а становится исходом {@code Failed}. Пробрасывать его из метода нельзя:
   * одна сломанная транзакция заблокировала бы весь проход планировщика, и очередь валютно
   * независимых транзакций встала бы навсегда.
   *
   * <p>Логирования здесь нет намеренно: причина попадает в лог там, где она означает конкретное
   * действие, — {@link TransactionIntakeService} пишет сбой как {@code ERROR} и считает попытку
   * неудачным расчётом. Лог «курс недоступен» здесь означал бы, что виноват провайдер, а сломан
   * может быть наш кэш курсов.
   */
  private RateResolution join(RateKey key, CompletableFuture<RateResolution> request) {
    try {
      return request.join();
    } catch (RuntimeException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof Error fatal) {
        throw fatal;
      }
      return RateResolution.failed(
          cause instanceof RuntimeException failure
              ? failure
              : new IllegalStateException("Rate resolution for " + key.currency() + " failed", cause));
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

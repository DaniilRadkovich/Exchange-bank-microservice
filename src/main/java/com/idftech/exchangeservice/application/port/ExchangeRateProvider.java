package com.idftech.exchangeservice.application.port;

import com.idftech.exchangeservice.domain.ExchangeRate;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Порт внешнего источника биржевых курсов.
 *
 * <p>Одна реализация на провайдера. Контракт минимален: вернуть дневной курс пары
 * {@code base/USD} с ценой закрытия и предыдущего закрытия либо пустой результат, если данных
 * нет. Таймауты, повторные попытки и наблюдаемость — ответственность адаптера, а не домена.
 */
public interface ExchangeRateProvider {

  /** Дневной курс валютной пары {@code base/USD} на дату операции. */
  Optional<ExchangeRate> fetchDailyRate(String baseCurrency, LocalDate date);
}

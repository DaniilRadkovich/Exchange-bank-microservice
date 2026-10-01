package com.idftech.exchangeservice.application.port;

import com.idftech.exchangeservice.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Порт собственного кэша биржевых курсов (ТЗ п.3).
 *
 * <p>Каждый запрос к внешнему API платный и медленный, поэтому курсы сохраняются в своей БД и
 * используются преимущественно оттуда; внешний источник опрашивается только при промахе.
 */
public interface RateCache {

  /**
   * Курс пары {@code base/USD} на указанную дату: {@code close}, а если на эту дату торгов не
   * было — {@code previous_close}.
   */
  Optional<BigDecimal> findRate(String baseCurrency, LocalDate date);

  /**
   * Последний известный курс не старше указанной даты. Резервный сценарий на случай, если в БД
   * нет записи ровно за эту дату.
   */
  Optional<BigDecimal> findLatestRateNotAfter(String baseCurrency, LocalDate date);

  void save(ExchangeRate rate);
}

package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.application.port.RateCache;
import com.idftech.exchangeservice.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Адаптер порта {@link RateCache} поверх Spring Data JPA — собственный кэш курсов. */
@Repository
@Transactional
public class JpaRateCache implements RateCache {

  private static final String USD = "USD";

  private final ExchangeRateJpaRepository repository;

  public JpaRateCache(ExchangeRateJpaRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BigDecimal> findRate(String baseCurrency, LocalDate date) {
    return repository.findRate(baseCurrency, USD, date).map(this::applicableRate);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BigDecimal> findLatestRateNotAfter(String baseCurrency, LocalDate date) {
    return repository.findLatestNotAfter(baseCurrency, USD, date).stream()
        .findFirst()
        .map(this::applicableRate);
  }

  @Override
  public void save(ExchangeRate rate) {
    // Повторный запрос к внешнему API за ту же пару и дату не нужен: запись уже есть.
    if (repository.existsByBaseCurrencyAndQuoteCurrencyAndRateDate(
        rate.base().getCurrencyCode(), rate.quote().getCurrencyCode(), rate.rateDate())) {
      return;
    }
    repository.save(
        new ExchangeRateEntity(
            rate.id() != null ? rate.id() : UUID.randomUUID(),
            rate.base().getCurrencyCode(),
            rate.quote().getCurrencyCode(),
            rate.rateDate(),
            rate.close(),
            rate.previousClose()));
  }

  private BigDecimal applicableRate(ExchangeRateEntity entity) {
    BigDecimal close = entity.getCloseRate();
    if (close != null && close.signum() > 0) {
      return close;
    }
    return entity.getPreviousClose();
  }
}

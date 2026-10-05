package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.application.port.RateCache;
import com.idftech.exchangeservice.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
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
  private final PersistenceMapper mapper;

  public JpaRateCache(ExchangeRateJpaRepository repository, PersistenceMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<BigDecimal> findRate(String baseCurrency, LocalDate date) {
    return repository.findRate(baseCurrency, USD, date).map(this::applicableRate);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<ExchangeRate> findLatestRateNotAfter(String baseCurrency, LocalDate date) {
    return repository.findLatestNotAfter(baseCurrency, USD, date).stream()
        .findFirst()
        .map(this::toDomain);
  }

  @Override
  public void save(ExchangeRate rate) {
    repository.upsert(
        rate.id() != null ? rate.id() : UUID.randomUUID(),
        rate.base().getCurrencyCode(),
        rate.quote().getCurrencyCode(),
        rate.rateDate(),
        rate.close(),
        rate.previousClose());
  }

  private ExchangeRate toDomain(ExchangeRateEntity entity) {
    return mapper.toDomain(entity);
  }

  private BigDecimal applicableRate(ExchangeRateEntity entity) {
    BigDecimal close = entity.getCloseRate();
    if (close != null && close.signum() > 0) {
      return close;
    }
    return entity.getPreviousClose();
  }
}

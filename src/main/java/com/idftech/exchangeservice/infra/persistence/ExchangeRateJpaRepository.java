package com.idftech.exchangeservice.infra.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Репозиторий собственного кэша курсов. */
public interface ExchangeRateJpaRepository extends JpaRepository<ExchangeRateEntity, UUID> {

  /**
   * Курс пары {@code base/quote} на дату: цена закрытия, а при её отсутствии — предыдущее
   * закрытие (выходной или праздничный день, ТЗ п.3).
   */
  @Query(
      """
      SELECT r FROM ExchangeRateEntity r
      WHERE r.baseCurrency = :baseCurrency
        AND r.quoteCurrency = :quoteCurrency
        AND r.rateDate = :date
        AND COALESCE(r.closeRate, r.previousClose) IS NOT NULL
      """)
  Optional<ExchangeRateEntity> findRate(
      @Param("baseCurrency") String baseCurrency,
      @Param("quoteCurrency") String quoteCurrency,
      @Param("date") LocalDate date);

  /** Последний известный курс не старше даты — резервный источник, если точной записи нет. */
  @Query(
      """
      SELECT r FROM ExchangeRateEntity r
      WHERE r.baseCurrency = :baseCurrency
        AND r.quoteCurrency = :quoteCurrency
        AND r.rateDate <= :date
        AND COALESCE(r.closeRate, r.previousClose) IS NOT NULL
      ORDER BY r.rateDate DESC
      """)
  List<ExchangeRateEntity> findLatestNotAfter(
      @Param("baseCurrency") String baseCurrency,
      @Param("quoteCurrency") String quoteCurrency,
      @Param("date") LocalDate date);

  /** Проверка наличия записи для конкретной пары и даты — чтобы не перезаписывать и не платить повторно. */
  boolean existsByBaseCurrencyAndQuoteCurrencyAndRateDate(String baseCurrency, String quoteCurrency, LocalDate rateDate);
}

package com.idftech.exchangeservice.infra.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

  /**
   * Атомарная запись курса за пару и дату.
   *
   * <p>{@code ON CONFLICT}, а не {@code exists} + {@code save} из Java: две транзакции с разными
   * парами «валюта + дата» (дедупликация действует только внутри одной пачки) запрашивали бы одну и ту
   * же валюту одновременно, обе увидели бы «записи нет» и одна из них упала бы на
   * {@code uc_exchange_rate_pair_date}.
   *
   * <p>{@code COALESCE} дополняет, а не затирает: запись, сохранённая в выходной только с
   * {@code previous_close}, позже дополняется настоящим {@code close}, а не остаётся неполной навсегда.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO exchange_rate (id, base_currency, quote_currency, rate_date, close_rate, previous_close)
          VALUES (:id, :baseCurrency, :quoteCurrency, :rateDate, :closeRate, :previousClose)
          ON CONFLICT (base_currency, quote_currency, rate_date) DO UPDATE
             SET close_rate     = COALESCE(EXCLUDED.close_rate, exchange_rate.close_rate),
                 previous_close = COALESCE(EXCLUDED.previous_close, exchange_rate.previous_close)
          """,
      nativeQuery = true)
  void upsert(
      @Param("id") UUID id,
      @Param("baseCurrency") String baseCurrency,
      @Param("quoteCurrency") String quoteCurrency,
      @Param("rateDate") LocalDate rateDate,
      @Param("closeRate") BigDecimal closeRate,
      @Param("previousClose") BigDecimal previousClose);
}

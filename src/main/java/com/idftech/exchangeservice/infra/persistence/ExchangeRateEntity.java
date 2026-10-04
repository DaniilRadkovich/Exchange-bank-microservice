package com.idftech.exchangeservice.infra.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Персистентное представление дневного биржевого курса — собственный кэш курсов (ТЗ п.3). */
@Entity
@Table(name = "exchange_rate")
@Getter
@NoArgsConstructor
public class ExchangeRateEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "base_currency", nullable = false, length = 3, columnDefinition = "bpchar(3)")
  // char(3), а не varchar: код валюты всегда ровно три знака, и CHAR не дополняет его
  // пробелами при чтении. Тип задан явно, иначе Hibernate ожидает varchar и validate падает.
  private String baseCurrency;

  @Column(name = "quote_currency", nullable = false, length = 3, columnDefinition = "bpchar(3)")
  // char(3), а не varchar: код валюты всегда ровно три знака, и CHAR не дополняет его
  // пробелами при чтении. Тип задан явно, иначе Hibernate ожидает varchar и validate падает.
  private String quoteCurrency;

  @Column(name = "rate_date", nullable = false)
  private LocalDate rateDate;

  @Column(name = "close_rate", precision = 19, scale = 10)
  private BigDecimal closeRate;

  @Column(name = "previous_close", precision = 19, scale = 10)
  private BigDecimal previousClose;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  public ExchangeRateEntity(
      UUID id,
      String baseCurrency,
      String quoteCurrency,
      LocalDate rateDate,
      BigDecimal closeRate,
      BigDecimal previousClose) {
    this.id = id;
    this.baseCurrency = baseCurrency;
    this.quoteCurrency = quoteCurrency;
    this.rateDate = rateDate;
    this.closeRate = closeRate;
    this.previousClose = previousClose;
  }
}

package com.idftech.exchangeservice.application.exception;

/**
 * Данные корректны по формату, но неприемлемы по смыслу: {@code 422 Unprocessable Content}.
 *
 * <p>Отличается от ошибок валидации Bean Validation ({@code 400}), которые относятся к синтаксису и
 * типам. Например, несуществующий код валюты ISO 4217 формально подходит под {@code [A-Za-z]{3}},
 * но не является валютой — это смысловая ошибка.
 */
public class UnprocessableEntityException extends RuntimeException {

  private final String reason;

  public UnprocessableEntityException(String reason, String detail) {
    super(detail);
    this.reason = reason;
  }

  public String getReason() {
    return reason;
  }
}

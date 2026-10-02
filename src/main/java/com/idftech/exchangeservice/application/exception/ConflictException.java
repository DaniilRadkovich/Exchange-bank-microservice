package com.idftech.exchangeservice.application.exception;

/**
 * Запись уже существует в том состоянии, в котором её пытаются создать: {@code 409 Conflict}.
 *
 * <p>Отличается от {@link UnprocessableEntityException}, где неприемлемо само значение: здесь значение
 * осмысленно, но конфликтует с уже сохранёнными данными. Пример — второй лимит на тот же момент
 * установки: сумма любая, но пара «счёт + категория + момент» уже занята.
 */
public class ConflictException extends RuntimeException {

  private final String reason;

  public ConflictException(String reason, String detail) {
    super(detail);
    this.reason = reason;
  }

  public String getReason() {
    return reason;
  }
}
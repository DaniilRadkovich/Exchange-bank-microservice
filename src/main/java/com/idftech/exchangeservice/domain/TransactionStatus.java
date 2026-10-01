package com.idftech.exchangeservice.domain;

/**
 * Статус расчётной обработки транзакции.
 *
 * <p>POST /transactions возвращает 202 Accepted и статус {@link #PENDING}: транзакция принята и
 * сохранена немедленно, а перевод в USD и расчёт {@code limit_exceeded} выполняются асинхронно.
 * Клиент узнаёт об исходе обработки по {@code GET /api/v1/transactions/{id}}.
 */
public enum TransactionStatus {
  /** Принята, ожидает перевода в USD и расчёта лимита. */
  PENDING,
  /** Курс применён, флаг limit_exceeded рассчитан. */
  RATE_RESOLVED,
  /** Внешнее API курсов недоступно и резервного значения нет — требуется ручная обработка. */
  FAILED
}

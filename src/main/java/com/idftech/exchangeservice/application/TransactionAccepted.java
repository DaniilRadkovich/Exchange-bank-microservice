package com.idftech.exchangeservice.application;

import java.util.UUID;

/**
 * Операция принята и ждёт расчёта: строка уже зафиксирована, курс и флаг {@code limit_exceeded} ещё не
 * посчитаны.
 *
 * <p>Событие публикуется внутри транзакции {@link TransactionIntakeService#accept}, а обрабатывается
 * после её фиксации ({@code AFTER_COMMIT}). Порядок обязателен: до коммита строки в базе нет, и
 * досчётчик, взявший пачку сразу после публикации, её бы не увидел — операция ждала бы планировщика.
 *
 * @param transactionId идентификатор принятой операции
 */
public record TransactionAccepted(UUID transactionId) {}

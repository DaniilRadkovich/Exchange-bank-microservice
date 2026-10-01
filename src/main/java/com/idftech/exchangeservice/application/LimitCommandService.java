package com.idftech.exchangeservice.application;

import com.idftech.exchangeservice.application.port.LimitStore;
import com.idftech.exchangeservice.domain.ExpenseCategory;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.infra.config.LimitProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Установка нового лимита (ТЗ п.5).
 *
 * <p>Дата установки всегда берётся из бина {@code Clock}, поэтому клиент не может «отмотать» лимит в
 * прошлое или в будущее — это закрывает требование ТЗ без дополнительной валидации входных данных.
 *
 * <p>Обновление лимита не поддерживается: вместо {@code PUT} есть только {@code POST}, создающий новую
 * запись. Прежние лимиты остаются в истории, что и требуется для расчёта флагов задним числом.
 */
@Service
public class LimitCommandService {

  private static final Logger log = LoggerFactory.getLogger(LimitCommandService.class);

  private final LimitStore limitStore;
  private final LimitProperties limitProperties;
  private final Clock clock;

  public LimitCommandService(LimitStore limitStore, LimitProperties limitProperties, Clock clock) {
    this.limitStore = limitStore;
    this.limitProperties = limitProperties;
    this.clock = clock;
  }

  /**
   * Создаёт новый месячный лимит для пары «счёт + категория».
   *
   * <p>Несколько лимитов в пределах одного месяца допустимы — это прямо следует из сценария ТЗ, где
   * лимит 1000 USD от 01.01 сменяется лимитом 2000 USD от 10.01. Запрещено не обновлять лимит, а не
   * устанавливать новый, поэтому вместо изменения записи создаётся новая, а старая остаётся в
   * истории и участвует в расчёте флагов задним числом.
   *
   * <p>Точность суммы проверяется на границе API: {@code @Digits} в DTO отклоняет третьи знаки как
   * ошибку формата ({@code 400}). Дублировать ту же проверку здесь означало бы две реализации одного
   * правила, поэтому сервис доверяет вызывающему коду.
   */
  @Transactional
  public ExpenseLimit createLimit(String accountFrom, ExpenseCategory category, BigDecimal limitSum) {
    OffsetDateTime now = OffsetDateTime.now(clock.withZone(limitProperties.zoneId()));

    ExpenseLimit limit = ExpenseLimit.create(UUID.randomUUID(), accountFrom, category, limitSum, now);
    ExpenseLimit saved = limitStore.save(limit);

    log.info(
        "Limit {} USD set for account {} category {} at {}", limitSum, accountFrom, category.code(), now);
    return saved;
  }
}

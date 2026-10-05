package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.domain.BudgetPeriod;
import com.idftech.exchangeservice.domain.ExchangeRate;
import com.idftech.exchangeservice.domain.ExpenseLimit;
import com.idftech.exchangeservice.domain.ExpenseTransaction;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/**
 * Преобразования JPA-сущностей в доменные типы.
 *
 * <p>Подавляющая часть полей совпадает по имени и типу, и перечислять их руками — значит поддерживать
 * один и тот же список дважды: новое поле в домене молча не попало бы в преобразование, и ошибка
 * вылезла бы на {@code null} прямо в расчёте лимита. Оставшиеся несовпадения описаны явно: код валюты
 * вместо {@link Currency}, {@link Instant} вместо {@link OffsetDateTime} и {@code closeRate} вместо
 * {@code close}.
 *
 * <p>Обратное направление здесь не генерируется и делается вручную: сущности обязаны иметь конструктор
 * без аргументов (JPA), а вместе с ним MapStruct перестаёт видеть параметризованный конструктор и
 * требует сеттеров. Единственное место, где домен превращается в сущность, — запись лимита, там
 * конструктор вызывается явно и его видно.
 *
 * <p>Имена полей расходятся в двух местах — {@code expenseCategory} против {@code category}, — поэтому
 * они описаны явно; остальное совпадает.
 *
 * <p>Генератор берёт канонический конструктор записи, поэтому в домене не появляется ни одной аннотации
 * и он остаётся свободным от фреймворков — это проверяет {@code LayeringTest}. Всё, что нельзя
 * выразить именем поля, вынесено в методы ниже и остаётся читаемым.
 *
 * <p>Зона в преобразовании времени не настраивается: время лимитов, операций и курсов живёт в
 * {@link BudgetPeriod#LIMIT_TIMEZONE}, и второе место, где его можно было бы указать, разъехалось бы с
 * расчётом молча.
 */
/*
 * ReportingPolicy.ERROR — обязателен, а не украшение. Без него пропущенное поле компилируется и
 * молча становится null: сущность называет его expenseCategory, запись — category, и забытый @Mapping
 * дал бы null в категории расхода, то есть ошибку в самом расчёте лимита, а не в сборке. На
 * ручном преобразовании такая ошибка обнаруживалась бы только тестом на конкретное поле.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PersistenceMapper {

  // withLimitExceeded — не колонка, а доменный помощник «копия с новым флагом». MapStruct видит в нём
  // флюентный сеттер и ждёт значения из источника; параметр при этом примитивный, а в сущности поле
  // Boolean, поэтому «пропуск» пришлось бы объявить явно, а не замаскировать настройкой политики.
  @Mapping(target = "withLimitExceeded", ignore = true)
  @Mapping(target = "category", source = "expenseCategory")
  @Mapping(target = "currency", source = "currencyCode", qualifiedByName = "currencyOf")
  @Mapping(target = "occurredAt", source = "occurredAt", qualifiedByName = "atLimitOffset")
  ExpenseTransaction toDomain(ExpenseTransactionEntity entity);

  @Mapping(target = "category", source = "expenseCategory")
  @Mapping(target = "currency", source = "limitCurrency", qualifiedByName = "currencyOf")
  @Mapping(target = "limitDatetime", source = "limitDatetime", qualifiedByName = "atLimitOffset")
  ExpenseLimit toDomain(ExpenseLimitEntity entity);

  @Mapping(target = "base", source = "baseCurrency", qualifiedByName = "currencyOf")
  @Mapping(target = "quote", source = "quoteCurrency", qualifiedByName = "currencyOf")
  @Mapping(target = "close", source = "closeRate")
  ExchangeRate toDomain(ExchangeRateEntity entity);

  @Named("currencyOf")
  default Currency currencyOf(String currencyCode) {
    return currencyCode == null ? null : Currency.getInstance(currencyCode);
  }

  @Named("atLimitOffset")
  default OffsetDateTime atLimitOffset(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }
}
package com.idftech.exchangeservice.infra.persistence;

import com.idftech.exchangeservice.domain.ExpenseCategory;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Хранение категории расхода в том же виде, в каком она приходит в API клиента.
 *
 * <p>ТЗ задаёт категории как {@code product} и {@code service}, и CHECK-ограничение в схеме
 * перечисляет именно эти значения. Сохранение {@code enum.name()} в верхнем регистре нарушало бы
 * ограничение, поэтому enum преобразуется в свой код, а {@link ExpenseCategory#fromCode(String)}
 * терпим к любому регистру при чтении из нативных SQL-запросов.
 */
@Converter(autoApply = true)
public class ExpenseCategoryConverter implements AttributeConverter<ExpenseCategory, String> {

  @Override
  public String convertToDatabaseColumn(ExpenseCategory attribute) {
    return attribute == null ? null : attribute.code();
  }

  @Override
  public ExpenseCategory convertToEntityAttribute(String dbData) {
    return dbData == null ? null : ExpenseCategory.fromCode(dbData);
  }
}
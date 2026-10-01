package com.idftech.exchangeservice.domain;

/** Категория расхода. Месячный лимит хранится раздельно для каждой категории. */
public enum ExpenseCategory {
  PRODUCT("product"),
  SERVICE("service");

  private final String code;

  ExpenseCategory(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  /** Разрешает код из входного JSON (product / service) в enum. */
  public static ExpenseCategory fromCode(String code) {
    for (ExpenseCategory category : values()) {
      if (category.code.equalsIgnoreCase(code)) {
        return category;
      }
    }
    throw new IllegalArgumentException("Unknown expense category: " + code);
  }
}

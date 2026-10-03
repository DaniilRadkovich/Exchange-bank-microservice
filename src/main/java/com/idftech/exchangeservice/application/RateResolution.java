package com.idftech.exchangeservice.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Итог получения курса для одной транзакции: курс есть, курса нет или получение сломалось.
 *
 * <p>Три исхода вместо {@code Optional<BigDecimal>} потому, что «курса нет» и «курс не удалось
 * получить из-за нашей ошибки» — разные события с разными последствиями. И то и другое оставляет
 * транзакцию в {@code PENDING}, но первое ждёт следующего прохода планировщика, а второе требует
 * внимания: упала запись в нашу же БД, а не внешний API.
 *
 * <p>Граница проходит по {@link ExchangeRateService}: провал провайдера возвращается пустым
 * результатом, а сбой записи курса в кэш выбрасывается наружу. На границе пакетной дорасчетки
 * ({@link ParallelRateResolver}) это различие терялось: отказ задачи превращался в пустой курс, и
 * нарушение CHECK-ограничения нашей схемы выглядело в логе как «курс недоступен».
 */
public sealed interface RateResolution {

  /** Курс получен и применим к транзакции. */
  record Resolved(BigDecimal rate) implements RateResolution {
    public Resolved {
      Objects.requireNonNull(rate);
    }
  }

  /** Курса нет: провайдер не отдал цену либо ответ оказался непригодным. Внешняя причина. */
  record Unavailable() implements RateResolution {
    public static final Unavailable INSTANCE = new Unavailable();
  }

  /** Получение курса сломалось изнутри: наша БД или наш код, а не биржа. */
  record Failed(RuntimeException cause) implements RateResolution {
    public Failed {
      Objects.requireNonNull(cause);
    }
  }

  /** Курс получен. */
  static RateResolution resolved(BigDecimal rate) {
    return new Resolved(rate);
  }

  /** Курс недоступен по вине провайдера. */
  static RateResolution unavailable() {
    return Unavailable.INSTANCE;
  }

  /** Получение курса сломалось по вине сервиса. */
  static RateResolution failed(RuntimeException cause) {
    return new Failed(cause);
  }
}
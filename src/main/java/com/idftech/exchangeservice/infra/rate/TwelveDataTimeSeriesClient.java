package com.idftech.exchangeservice.infra.rate;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/**
 * Дневной ряд курсов внешнего API (Twelve Data {@code /time_series}) — HTTP Interface.
 *
 * <p>Контракт объявлен интерфейсом, а не собран вручную из {@code RestClient} по частям: адрес и состав
 * параметров запроса видны целиком в одном месте, а добавление нового провайдера становится отдельным
 * интерфейсом и отдельным бином, а не правкой URI в середине класса. Ключ доступа не параметр метода, а
 * заголовок по умолчанию клиента: иначе он передавался бы из каждого вызова и мог бы утечь в лог.
 *
 * <p>Таймауты и повторы не задаются здесь: HTTP Interface работает поверх того же {@code RestClient}, на
 * котором построена фабрика, поэтому наследует их оттуда. Дублировать таймауты в двух местах —
 * значит потом забыть обновить в одном из них.
 */
public interface TwelveDataTimeSeriesClient {

  /** Значение параметра {@code interval}: дневной ряд, как требует ТЗ п.3. */
  String INTERVAL_DAILY = "1day";

  /** Порядок значений по возрастанию даты: разбор окна полагается на хронологический порядок. */
  String ORDER_ASC = "asc";

  /**
   * Дневной ряд по паре за окно дат включительно.
   *
   * @param symbol валютная пара, например {@code KZT/USD}
   * @param startDateInclusive первая дата окна включительно
   * @param endDateInclusive последняя дата окна включительно
   * @param outputSize сколько наблюдений просить у источника
   */
  @GetExchange("/time_series")
  TimeSeriesResponse timeSeries(
      @RequestParam("symbol") String symbol,
      @RequestParam("interval") String interval,
      @RequestParam("start_date") String startDateInclusive,
      @RequestParam("end_date") String endDateInclusive,
      @RequestParam("previous_close") boolean previousClose,
      @RequestParam("order") String order,
      @RequestParam("outputsize") int outputSize);
}
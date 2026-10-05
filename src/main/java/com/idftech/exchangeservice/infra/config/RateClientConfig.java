package com.idftech.exchangeservice.infra.config;

import com.idftech.exchangeservice.application.config.RateProviderProperties;
import com.idftech.exchangeservice.infra.rate.RetryingCaller;
import com.idftech.exchangeservice.infra.rate.TwelveDataRateProvider;
import com.idftech.exchangeservice.infra.rate.TwelveDataTimeSeriesClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Конфигурация HTTP-клиента внешнего источника курсов.
 *
 * <p>Таймауты обязательны: без них недоступный внешний API держит поток виртуального потока
 * бесконечно и подписывает систему на исчерпание ограничений. Значения берутся из
 * {@code exchange.rates.*} и переопределяются переменными окружения.
 */
@Configuration
public class RateClientConfig {

  @Bean
  public HttpClient rateProviderHttpClient(RateProviderProperties properties) {
    return HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  @Bean
  public RestClient rateProviderRestClient(
      HttpClient rateProviderHttpClient, RateProviderProperties properties) {
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(rateProviderHttpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .build();
  }

  /**
   * HTTP Interface внешнего API курсов.
   *
   * <p>Клиент-интерфейс строится поверх того же {@link RestClient}, что и раньше: базовый адрес,
   * фабрика запросов с таймаутами установки соединения и чтения приходят оттуда. Ключ добавляется
   * заголовком по умолчанию, а не параметром метода, поэтому он не может случайно попасть в лог и не
   * дублируется в каждом вызове.
   *
   * <p>Объявлять интерфейс бином нужно здесь же: {@code @EnableHttpServiceClient} не включён, потому
   * что провайдеров может быть несколько и каждый переиспользует один и тот же HTTP-клиент.
   */
  @Bean
  public TwelveDataTimeSeriesClient twelveDataTimeSeriesClient(
      RestClient rateProviderRestClient, RateProviderProperties properties) {
    RestClient authorized =
        rateProviderRestClient
            .mutate()
            .defaultHeader("Authorization", "apikey " + properties.apiKey())
            .build();
    return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(authorized))
        .build()
        .createClient(TwelveDataTimeSeriesClient.class);
  }

  /**
   * Провайдер курсов по умолчанию.
   *
   * <p>Условие по {@code exchange.rates.provider} — не украшение: без него свойство в конфигурации
   * ничего не значило бы, и подмена провайдера молча игнорировалась. С ним добавление второго
   * провайдера сводится к ещё одному бину с {@code havingValue} другого значения — потребители
   * ({@code ExchangeRateService}, {@code ParallelRateResolver}) ничего не меняют.
   *
   * <p>{@code matchIfMissing} оставляет работоспособной конфигурацию, где свойство не задано вовсе.
   * Если же в {@code provider} попадёт незнакомое значение, бинов {@code ExchangeRateProvider} не
   * останется и контекст не поднимется — с сообщением о неточной конфигурации, а не с
   * {@code NoUniqueBeanDefinitionException} или, что хуже, с молчаливым использованием провайдера,
   * которого клиент не выбирал.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "exchange.rates",
      name = "provider",
      havingValue = "twelvedata",
      matchIfMissing = true)
  public TwelveDataRateProvider twelveDataRateProvider(
      TwelveDataTimeSeriesClient timeSeriesClient,
      RateProviderProperties properties,
      RetryingCaller retryingCaller,
      MeterRegistry meterRegistry) {
    return new TwelveDataRateProvider(timeSeriesClient, properties, retryingCaller, meterRegistry);
  }
}

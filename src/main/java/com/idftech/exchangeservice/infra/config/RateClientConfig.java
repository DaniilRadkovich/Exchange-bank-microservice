package com.idftech.exchangeservice.infra.config;

import com.idftech.exchangeservice.infra.rate.RetryingCaller;
import io.micrometer.core.instrument.MeterRegistry;
import com.idftech.exchangeservice.infra.rate.TwelveDataRateProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

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
   * Провайдер курсов по умолчанию.
   *
   * <p>Регистрируется как бин типа {@link com.idftech.exchangeservice.application.port.ExchangeRateProvider}:
   * чтобы добавить второго провайдера, достаточно объявить ещё один бин и {@code @Primary}, либо
   * развести их по условию {@code @ConditionalOnProperty} — потребители ничего не меняют.
   */
  @Bean
  public TwelveDataRateProvider twelveDataRateProvider(
      RestClient rateProviderRestClient,
      RateProviderProperties properties,
      RetryingCaller retryingCaller,
      MeterRegistry meterRegistry) {
    return new TwelveDataRateProvider(
        rateProviderRestClient, properties, retryingCaller, meterRegistry);
  }
}

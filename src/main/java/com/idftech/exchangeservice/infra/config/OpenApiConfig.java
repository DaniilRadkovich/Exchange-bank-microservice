package com.idftech.exchangeservice.infra.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Описание REST API в формате OpenAPI 3 (ТЗ, требование 2).
 *
 * <p>Swagger UI доступен по адресу {@code /swagger-ui.html}, спецификация — {@code /v3/api-docs}.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI exchangeServiceOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Exchange Service API")
                .version("1.0.0")
                .description(
                    """
                        Микросервис контроля месячных лимитов расходов.

                        Границы календарного месяца определяются в часовом поясе UTC.
                        Логика флага limit_exceeded: сумма операций категории за месяц нарастающим
                        итогом сравнивается с лимитом, действовавшим на момент операции; превышением
                        считается строгое «больше», поэтому остаток ровно 0 превышением не является.
                        """)
                .contact(new Contact().name("Junior Java разработчик, тестовое задание"))
                .license(new License().name("MIT")));
  }
}

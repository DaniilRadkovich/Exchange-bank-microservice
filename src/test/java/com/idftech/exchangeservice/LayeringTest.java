package com.idftech.exchangeservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Направление зависимостей между слоями, проверенное по исходникам.
 *
 * <p>Правило из {@code AGENTS.md} («зависимости направлены внутрь: {@code api → application → domain}») не
 * исполняется компилятором: {@code application} спокойно импортирует {@code infra}, и всё собирается,
 * и все тесты зелёные. Ошибка всплывает поздно и дорого — например, когда тест доменной логики
 * вдруг требует контекст Spring, потому что настройку по умолчанию подкинули из {@code infra.config}.
 *
 * <p>Проверка намеренно примитивная: читаем исходники и ищем запрещённые импорты. Ни ArchUnit, ни
 * компиляторные проверки тут не нужны — правило всего три строки, а цена его нарушения уже была
 * уплачена.
 */
class LayeringTest {

  private static final Path MAIN = Path.of("src", "main", "java", "com", "idftech", "exchangeservice");

  @Test
  @DisplayName("Прикладной слой не знает про инфраструктуру")
  void applicationDoesNotDependOnInfra() throws IOException {
    // Настройки расчёта переехали в application.config именно поэтому: иначе LimitCalculator,
    // ExchangeRateService, SettlementApplier и TransactionIntakeService импортировали бы
    // infra.config, и направление зависимостей было бы развёрнуто.
    assertThat(importsIn("application")).doesNotContain("com.idftech.exchangeservice.infra");
  }

  @Test
  @DisplayName("Домен не знает ни про Spring, ни про JPA, ни про прикладной слой")
  void domainStaysFrameworkFree() throws IOException {
    List<String> imports = importsIn("domain");

    assertThat(imports)
        .noneMatch(
            name ->
                name.startsWith("org.springframework")
                    || name.startsWith("jakarta.")
                    || name.startsWith("com.idftech.exchangeservice"));
  }

  @Test
  @DisplayName("Прикладной слой не знает ни про HTTP, ни про JPA")
  void applicationDoesNotDependOnTransportOrPersistence() throws IOException {
    assertThat(importsIn("application"))
        .noneMatch(
            name ->
                name.startsWith("jakarta.")
                    || name.startsWith("org.springframework.web")
                    || name.startsWith("com.idftech.exchangeservice.infra.persistence"));
  }

  private static List<String> importsIn(String layer) throws IOException {
    try (Stream<Path> sources = Files.walk(MAIN.resolve(layer))) {
      return sources
          .filter(path -> path.toString().endsWith(".java"))
          .flatMap(
              path -> {
                try {
                  return Files.lines(path);
                } catch (IOException e) {
                  throw new IllegalStateException("Не удалось прочитать исходник " + path, e);
                }
              })
          .filter(line -> line.startsWith("import "))
          .map(line -> line.substring("import ".length()).replace("static ", "").trim())
          .toList();
    }
  }
}
# Сборка: зависимости скачиваются отдельным слоем, чтобы правка исходников не пересобирала их заново.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# Запуск: только JRE и собранный jar. Базовый образ Alpine — компактнее, но в нём musl,
# а нативных библиотек в classpath нет, поэтому собранный jar переносится без изменений.
FROM eclipse-temurin:21-jre

WORKDIR /app

# Пользователь без прав: контейнер не должен работать от root.
RUN useradd --system --create-home --uid 10001 exchange
USER exchange

COPY --from=build /build/target/exchangeservice-*.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=5 \
  CMD ["sh", "-c", "wget -qO- http://localhost:8080/actuator/health || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
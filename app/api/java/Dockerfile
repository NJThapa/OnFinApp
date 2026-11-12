FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# The OpenTelemetry Java agent instruments Spring MVC, JDBC and logging without code changes.
ADD --chmod=644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.33.1/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
COPY --from=build /src/target/api.jar app.jar
# Telemetry is off unless the environment turns it on: set OTEL_SDK_DISABLED=false and
# OTEL_EXPORTER_OTLP_ENDPOINT (see deployment/local/docker-compose.yml).
ENV OTEL_SDK_DISABLED=true
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "app.jar"]

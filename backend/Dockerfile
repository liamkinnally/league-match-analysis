# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw -B -ntp -DskipTests dependency:go-offline

COPY backend/src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 application \
    && useradd --system --uid 10001 --gid application --home-dir /app application

WORKDIR /app
COPY --from=build --chown=application:application /workspace/backend/target/backend-0.0.1-SNAPSHOT.jar application.jar

ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
USER application

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
  CMD curl --fail --silent --show-error "http://127.0.0.1:${PORT:-8080}/actuator/health" >/dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/application.jar"]

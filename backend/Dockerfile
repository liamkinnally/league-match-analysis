# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS build

WORKDIR /workspace/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw -B -ntp -DskipTests dependency:go-offline

COPY backend/src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy@sha256:1eac8b8fee11ca7d6725a48bc5d41992ed1561504ebae25490d2d062847e8269 AS runtime

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

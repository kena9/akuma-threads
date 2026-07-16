# ── Build stage ─────────────────────────────────────────────────────
# Runs the full test suite; the image cannot be built from failing code.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q verify

# ── Runtime stage ───────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --uid 1001 spring
USER spring
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Profile and secrets come from the environment at run time:
#   docker run -e SPRING_PROFILES_ACTIVE=prod -e DB_PASSWORD=... image
#
# No HEALTHCHECK on purpose: this JRE image ships without curl/wget, and
# the orchestrator (ECS, App Runner, EB Docker) should own health checks.
# Point yours at /actuator/health/readiness.
ENTRYPOINT ["java", "-jar", "app.jar"]

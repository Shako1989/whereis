# ---- Build stage ----
FROM gradle:8.14-jdk21-alpine AS build
WORKDIR /workspace

# Dependency layer: cached until the build files change.
COPY settings.gradle build.gradle lombok.config ./
RUN gradle --no-daemon dependencies -q > /dev/null 2>&1 || true

COPY src src
RUN gradle --no-daemon bootJar -x test -q

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
# curl is only here so the container HEALTHCHECK / compose healthcheck can hit
# the actuator readiness probe; the jammy base image does not ship it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

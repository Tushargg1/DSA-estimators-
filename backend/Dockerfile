# syntax=docker/dockerfile:1

# =============================================================================
# DSA Progress Tracker — backend image (task 12.1)
# Multi-stage build: (1) build the fat jar with the Maven wrapper, (2) run it on
# a slim JRE.
#
# Java version decision
# ----------------------
# The spec's intent is Java 21. Spring Boot 3.4 fully supports Java 21, so BOTH
# stages use Eclipse Temurin 21 images (21-jdk to build, 21-jre-alpine to run).
#
# The pom currently targets Java 17 (<java.version>17</java.version>) because
# only a JDK 17 is installed on the dev host. That is intentional and safe here:
#   * `--release 17` produces Java 17 bytecode, which compiles cleanly under the
#     JDK 21 in the builder stage, and
#   * Java 17 bytecode runs without modification on the Java 21 runtime.
# So the deployed image runs on Java 21 while the source stays compatible with
# the local JDK 17. Bump <java.version> to 21 in pom.xml once a JDK 21 is the
# standard build environment — no Dockerfile change is required.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 — build
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy only the wrapper + pom first so Docker can cache the dependency layer.
# This layer is only invalidated when the wrapper or pom.xml changes, not on
# every source edit.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./

# Normalize line endings for the wrapper (the repo may be checked out on Windows)
# and make it executable, then warm the local Maven repository offline.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && ./mvnw -B -q dependency:go-offline

# Now copy the source and build the jar. Tests are skipped in the image build
# (they run in CI via `mvnw clean verify`); the image only needs the artifact.
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests \
    && cp target/*.jar app.jar

# -----------------------------------------------------------------------------
# Stage 2 — runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as a non-root user (defense in depth — a compromised process has no root).
RUN addgroup -S app && adduser -S app -G app
USER app

# Copy just the built artifact from the build stage.
COPY --from=build --chown=app:app /workspace/app.jar app.jar

# Spring Boot serves on 8080 (server.port default). Documented for tooling; does
# not actually publish the port — that is done at `docker run -p`.
EXPOSE 8080

# DB_URL / DB_USERNAME / DB_PASSWORD / CORS_ALLOWED_ORIGINS /
# WEBSOCKET_ALLOWED_ORIGINS / SERVER_PORT are supplied at runtime via env vars
# (see application.yml + .env.example).
ENTRYPOINT ["java", "-jar", "app.jar"]

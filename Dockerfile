# syntax=docker/dockerfile:1

# Build the Spring Boot backend from the monorepo root.
# Keep this in sync with backend/Dockerfile; source paths include backend/
# because Render uses the repository root as its Docker build context.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy the Maven wrapper and project descriptor first for dependency caching.
COPY backend/mvnw ./
COPY backend/.mvn/ .mvn/
COPY backend/pom.xml ./

# Normalize Windows line endings, enable the wrapper, and cache dependencies.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && ./mvnw -B -q dependency:go-offline

# Build only after dependencies are cached. Tests run separately in verification.
COPY backend/src/ src/
RUN ./mvnw -B -q clean package -DskipTests \
    && cp target/*.jar app.jar

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as a non-root user.
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build --chown=app:app /workspace/app.jar app.jar

# Spring Boot defaults to 8080, while Render's injected PORT is honored by
# application.yml at runtime. EXPOSE documents the local/default container port.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

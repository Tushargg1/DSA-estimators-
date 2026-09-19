# syntax=docker/dockerfile:1

# =============================================================================
# DSA Progress Tracker — backend image (built from monorepo root)
# Multi-stage build with Playwright Chromium for JS-rendered career site scraping.
# Render uses the repository root as its Docker build context.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 — build
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy the Maven wrapper and project descriptor first for dependency caching.
COPY backend/mvnw ./
COPY backend/.mvn/ .mvn/
COPY backend/pom.xml ./

# Normalize Windows line endings, enable the wrapper, and cache dependencies.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && ./mvnw -B -q dependency:go-offline

# Build only after dependencies are cached.
COPY backend/src/ src/
RUN ./mvnw -B -q clean package -DskipTests \
    && cp target/*.jar app.jar

# -----------------------------------------------------------------------------
# Stage 2 — runtime (Debian-based JRE + Playwright Chromium)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Install Playwright's Chromium system dependencies.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
    libdbus-1-3 libexpat1 libxcb1 libxkbcommon0 libx11-6 libxcomposite1 \
    libxdamage1 libxext6 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 \
    libpangocairo-1.0-0 libcairo2 libasound2 libatspi2.0-0 libwayland-client0 \
    fonts-liberation fonts-noto-color-emoji \
    wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
RUN groupadd -r app && useradd -r -g app -d /app app

COPY --from=build --chown=app:app /workspace/app.jar app.jar

# Install Playwright Chromium browser.
ENV PLAYWRIGHT_BROWSERS_PATH=/app/.playwright-browsers
RUN mkdir -p /app/.playwright-browsers \
    && chown -R app:app /app/.playwright-browsers

USER app

# Download Chromium via Playwright's embedded CLI.
RUN java -cp app.jar -Dloader.main=com.microsoft.playwright.CLI \
    org.springframework.boot.loader.launch.PropertiesLauncher install chromium \
    2>/dev/null || \
    java -cp app.jar -Dloader.main=com.microsoft.playwright.CLI \
    org.springframework.boot.loader.PropertiesLauncher install chromium \
    2>/dev/null || true

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

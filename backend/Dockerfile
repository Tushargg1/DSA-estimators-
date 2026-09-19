# syntax=docker/dockerfile:1

# =============================================================================
# DSA Progress Tracker — backend image
# Multi-stage build: (1) build the fat jar, (2) run it on a JRE with Playwright
# Chromium pre-installed for scraping JS-rendered career sites.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 — build
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy only the wrapper + pom first so Docker can cache the dependency layer.
COPY mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./

# Normalize line endings for the wrapper (repo may be checked out on Windows)
# and make it executable, then warm the local Maven repository offline.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw \
    && ./mvnw -B -q dependency:go-offline

# Now copy the source and build the jar.
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests \
    && cp target/*.jar app.jar

# -----------------------------------------------------------------------------
# Stage 2 — runtime (with Playwright Chromium)
# -----------------------------------------------------------------------------
# Use Debian-based JRE (not Alpine) because Playwright needs glibc + browser deps
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Install Playwright's Chromium system dependencies.
# These are the libraries Chromium needs to run headlessly on Debian/Ubuntu.
RUN apt-get update && apt-get install -y --no-install-recommends \
    # Chromium runtime dependencies
    libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
    libdbus-1-3 libexpat1 libxcb1 libxkbcommon0 libx11-6 libxcomposite1 \
    libxdamage1 libxext6 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 \
    libpangocairo-1.0-0 libcairo2 libasound2 libatspi2.0-0 libwayland-client0 \
    # Font rendering
    fonts-liberation fonts-noto-color-emoji \
    # Misc
    wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user (defense in depth).
RUN groupadd -r app && useradd -r -g app -d /app app

# Copy just the built artifact from the build stage.
COPY --from=build --chown=app:app /workspace/app.jar app.jar

# Install Playwright browsers as the app user.
# The PLAYWRIGHT_BROWSERS_PATH env var tells Playwright where to store browsers.
ENV PLAYWRIGHT_BROWSERS_PATH=/app/.playwright-browsers
RUN mkdir -p /app/.playwright-browsers \
    && chown -R app:app /app/.playwright-browsers

USER app

# Download Chromium for Playwright Java.
# This runs the Playwright CLI embedded in the Java dependency to install Chromium.
RUN java -cp app.jar -Dloader.main=com.microsoft.playwright.CLI \
    org.springframework.boot.loader.launch.PropertiesLauncher install chromium \
    2>/dev/null || \
    # Fallback: use the playwright driver directly
    java -cp app.jar -Dloader.main=com.microsoft.playwright.CLI \
    org.springframework.boot.loader.PropertiesLauncher install chromium \
    2>/dev/null || true

# Spring Boot defaults to 8080; application.yml also honors Render's PORT.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

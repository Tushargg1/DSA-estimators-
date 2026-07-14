package com.dsatracker.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the REST API (task 12.4).
 *
 * <p>Allowed origins are env-driven so the same build works locally and in
 * deployment. The list is read from the {@code cors.allowed-origins} property
 * (bound to the {@code CORS_ALLOWED_ORIGINS} env var via Spring's relaxed
 * binding), defaulting to the Vite dev server ({@code http://localhost:5173})
 * so local development needs no extra config.
 *
 * <p><b>Security:</b> the localhost default is for local development only.
 * Production MUST set {@code CORS_ALLOWED_ORIGINS} to the explicit frontend
 * origin(s) — never a wildcard — since credentials are allowed. See
 * DEPLOYMENT.md.
 *
 * <p>Applied to {@code /api/**} only; the WebSocket handshake origins are
 * configured separately in {@link WebSocketConfig}.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public CorsConfig(
            @Value("${cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

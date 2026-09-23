package com.cryptosignals.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cross-origin access for the web UI.
 *
 * <p>The UI is served separately (by Vite in development, by nginx in the Docker setup), so the
 * browser treats API calls as cross-origin. Allowed origins are configuration rather than a
 * wildcard: this API exposes a system's full analysis state, and a deployment should decide who may
 * read it rather than inheriting "anyone" as a default.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebConfig(@Value("${crypto-signals.api.allowed-origins:http://localhost:5173,http://localhost:3000}")
                     String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}

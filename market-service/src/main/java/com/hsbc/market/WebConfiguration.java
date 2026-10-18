package com.hsbc.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
    private final String portalOrigin;

    public WebConfiguration(@Value("${portal.origin}") String portalOrigin) {
        this.portalOrigin = portalOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(portalOrigin)
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "X-Correlation-ID")
                .allowCredentials(false);
    }
}

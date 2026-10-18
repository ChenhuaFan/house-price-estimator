package com.hsbc.market.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final MarketProperties properties;

  public WebConfiguration(MarketProperties properties) {
    this.properties = properties;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(properties.portalOrigin())
        .allowedMethods("GET", "POST", "DELETE")
        .allowedHeaders("Content-Type", "X-Correlation-ID", "Idempotency-Key")
        .exposedHeaders(
            "X-Correlation-ID",
            "Location",
            "Retry-After",
            "Content-Disposition",
            "Content-Digest",
            "ETag")
        .allowCredentials(false);
  }
}

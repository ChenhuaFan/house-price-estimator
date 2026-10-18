package com.hsbc.market.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Checks the downstream model service readiness endpoint. */
@Component("modelServiceHealthIndicator")
public final class ModelServiceHealthIndicator implements HealthIndicator {
  private final RestClient client;

  public ModelServiceHealthIndicator(RestClient modelRestClient) {
    this.client = modelRestClient;
  }

  @Override
  public Health health() {
    try {
      HttpStatusCode status =
          client.get().uri("/health/ready").retrieve().toBodilessEntity().getStatusCode();
      return status.is2xxSuccessful()
          ? Health.up().build()
          : Health.down().withDetail("status", status.value()).build();
    } catch (Exception error) {
      return Health.down().withDetail("reason", "model service is unavailable").build();
    }
  }
}

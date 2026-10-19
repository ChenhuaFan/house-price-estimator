package com.hsbc.market.config;

import com.hsbc.market.service.MarketHealthService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Combines business-data-read and export-state-write probes for database readiness. */
@Component("marketDatabaseHealthIndicator")
public class MarketDatabaseHealthIndicator implements HealthIndicator {
  private final MarketHealthService marketHealthService;

  public MarketDatabaseHealthIndicator(MarketHealthService marketHealthService) {
    this.marketHealthService = marketHealthService;
  }

  @Override
  public Health health() {
    try {
      if (!marketHealthService.businessDataReadable()) {
        return Health.down().withDetail("business", "live data is unreadable").build();
      }
      if (!marketHealthService.operationsWritable()) {
        return Health.down().withDetail("market_ops", "runtime role is not writable").build();
      }
      return Health.up()
          .withDetail("business", "readable")
          .withDetail("market_ops", "writable")
          .build();
    } catch (Exception error) {
      return Health.down().withDetail("reason", "market database checks failed").build();
    }
  }
}

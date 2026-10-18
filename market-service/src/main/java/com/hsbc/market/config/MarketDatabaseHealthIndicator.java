package com.hsbc.market.config;

import com.hsbc.market.service.MarketHealthService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("marketDatabaseHealthIndicator")
public class MarketDatabaseHealthIndicator implements HealthIndicator {
  private final MarketHealthService marketHealthService;

  public MarketDatabaseHealthIndicator(MarketHealthService marketHealthService) {
    this.marketHealthService = marketHealthService;
  }

  @Override
  public Health health() {
    try {
      if (!marketHealthService.catalogReadable()) {
        return Health.down().withDetail("catalog", "no active dataset").build();
      }
      if (!marketHealthService.operationsWritable()) {
        return Health.down().withDetail("market_ops", "runtime role is not writable").build();
      }
      return Health.up()
          .withDetail("catalog", "readable")
          .withDetail("market_ops", "writable")
          .build();
    } catch (Exception error) {
      return Health.down().withDetail("reason", "market database checks failed").build();
    }
  }
}

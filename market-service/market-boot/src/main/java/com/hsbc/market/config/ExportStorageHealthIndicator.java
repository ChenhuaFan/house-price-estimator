package com.hsbc.market.config;

import com.hsbc.market.application.port.out.ExportStorage;
import java.nio.file.Files;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("exportStorageHealthIndicator")
public final class ExportStorageHealthIndicator implements HealthIndicator {
  private final ExportStorage storage;

  public ExportStorageHealthIndicator(ExportStorage storage) {
    this.storage = storage;
  }

  @Override
  public Health health() {
    try {
      var probe = storage.path("health/probe").getParent();
      if (probe == null || !Files.isWritable(probe.getParent())) {
        return Health.down().withDetail("reason", "export storage is not writable").build();
      }
      return Health.up().build();
    } catch (Exception error) {
      return Health.down().withDetail("reason", "export storage is unavailable").build();
    }
  }
}

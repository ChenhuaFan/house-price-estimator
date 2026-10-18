package com.hsbc.market.config;

import com.hsbc.market.export.storage.LocalExportStorage;
import java.nio.file.Files;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports whether the configured export directory is available for writes. */
@Component("exportStorageHealthIndicator")
public final class ExportStorageHealthIndicator implements HealthIndicator {
  private final LocalExportStorage storage;

  public ExportStorageHealthIndicator(LocalExportStorage storage) {
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

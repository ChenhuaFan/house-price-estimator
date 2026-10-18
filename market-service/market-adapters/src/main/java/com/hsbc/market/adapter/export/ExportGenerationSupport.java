package com.hsbc.market.adapter.export;

import com.hsbc.market.application.port.out.ExportJobPort;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

final class ExportGenerationSupport {
  private final ExportJobPort jobs;
  private final UUID exportId;
  private final Instant deadline;

  ExportGenerationSupport(ExportJobPort jobs, UUID exportId, Duration maximumRuntime) {
    this.jobs = jobs;
    this.exportId = exportId;
    this.deadline = Instant.now().plus(maximumRuntime);
  }

  void check() {
    if (Instant.now().isAfter(deadline)) {
      throw new IllegalStateException("export exceeded the configured runtime limit");
    }
    if (jobs.cancellationRequested(exportId)) throw new ExportCancelledException();
  }
}

package com.hsbc.market.export;

import com.hsbc.market.dao.ExportJobDao;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Enforces cancellation and runtime limits while an artifact is being generated. */
final class ExportGenerationSupport {
  private final ExportJobDao jobs;
  private final UUID exportId;
  private final Instant deadline;

  ExportGenerationSupport(ExportJobDao jobs, UUID exportId, Duration maximumRuntime) {
    this.jobs = jobs;
    this.exportId = exportId;
    this.deadline = Instant.now().plus(maximumRuntime);
  }

  /** Fails fast when the job was cancelled or exceeded its runtime budget. */
  void check() {
    if (Instant.now().isAfter(deadline)) {
      throw new IllegalStateException("export exceeded the configured runtime limit");
    }
    if (jobs.cancellationRequested(exportId)) {
      throw new ExportCancelledException();
    }
  }
}

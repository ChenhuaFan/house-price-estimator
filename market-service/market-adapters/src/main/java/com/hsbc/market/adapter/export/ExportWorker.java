package com.hsbc.market.adapter.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.application.port.out.ExportJobPort;
import com.hsbc.market.application.port.out.ExportStorage;
import com.hsbc.market.domain.ExportDomain.Artifact;
import com.hsbc.market.domain.ExportDomain.Format;
import com.hsbc.market.domain.ExportDomain.Job;
import com.hsbc.market.domain.ExportDomain.RequestSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

public final class ExportWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(ExportWorker.class);
  private final String workerId = UUID.randomUUID().toString();
  private final ExportJobPort jobs;
  private final ExportStorage storage;
  private final CsvExportGenerator csv;
  private final PdfExportGenerator pdf;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final MeterRegistry registry;
  private final Duration lease;
  private final TransactionOperations generationTransaction;

  public ExportWorker(
      ExportJobPort jobs,
      ExportStorage storage,
      CsvExportGenerator csv,
      PdfExportGenerator pdf,
      ObjectMapper objectMapper,
      Clock clock,
      MeterRegistry registry,
      Duration lease,
      TransactionOperations generationTransaction) {
    this.jobs = jobs;
    this.storage = storage;
    this.csv = csv;
    this.pdf = pdf;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.registry = registry;
    this.lease = lease;
    this.generationTransaction = generationTransaction;
  }

  public boolean runOnce() {
    Job job = jobs.claimNext(workerId, clock.instant().plus(lease)).orElse(null);
    if (job == null) return false;
    registry.counter("market.export.jobs", "status", "running").increment();
    Timer.builder("market.export.queue.age")
        .register(registry)
        .record(Duration.between(job.createdAt(), clock.instant()));
    long started = System.nanoTime();
    String outcome = "failed";
    Artifact artifact = null;
    try {
      RequestSnapshot snapshot =
          objectMapper.readValue(job.requestPayload(), RequestSnapshot.class);
      artifact = generationTransaction.execute(status -> generate(job, snapshot));
      if (artifact == null)
        throw new IllegalStateException("export generation returned no artifact");
      if (jobs.cancellationRequested(job.exportId())) {
        storage.delete(artifact.contentKey());
        jobs.markCancelled(job.exportId(), job.stateRevision(), clock.instant());
        outcome = "cancelled";
      } else {
        jobs.succeed(job.exportId(), job.stateRevision(), artifact, clock.instant());
        outcome = "succeeded";
        registry
            .summary("market.export.rows", "format", job.format().name().toLowerCase())
            .record(artifact.rowCount());
        registry
            .summary("market.export.bytes", "format", job.format().name().toLowerCase())
            .record(artifact.contentLength());
      }
    } catch (ExportCancelledException error) {
      jobs.markCancelled(job.exportId(), job.stateRevision(), clock.instant());
      outcome = "cancelled";
    } catch (Exception error) {
      LOGGER.error(
          "Export generation failed for export_id={} with {}: {}",
          job.exportId(),
          error.getClass().getSimpleName(),
          error.getMessage());
      if (artifact != null) {
        try {
          storage.delete(artifact.contentKey());
        } catch (Exception ignored) {
        }
      }
      jobs.fail(
          job.exportId(),
          job.stateRevision(),
          "EXPORT_GENERATION_FAILED",
          "Export generation failed.",
          clock.instant());
    } finally {
      registry.counter("market.export.jobs", "status", outcome).increment();
      Timer.builder("market.export.generation.duration")
          .tags("format", job.format().name().toLowerCase(), "outcome", outcome)
          .register(registry)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
    return true;
  }

  public int recoverExpiredLeases() {
    return jobs.recoverExpiredLeases(clock.instant());
  }

  public int cleanupExpired() {
    var expired = jobs.expireBefore(clock.instant(), 100);
    expired.forEach(
        job -> {
          if (job.contentKey() != null) {
            try {
              storage.delete(job.contentKey());
            } catch (Exception ignored) {
            }
          }
        });
    return expired.size();
  }

  private Artifact generate(Job job, RequestSnapshot snapshot) {
    try {
      return job.format() == Format.CSV ? csv.generate(job, snapshot) : pdf.generate(job, snapshot);
    } catch (Exception error) {
      throw new ExportGenerationFailure(error);
    }
  }

  private static final class ExportGenerationFailure extends RuntimeException {
    private ExportGenerationFailure(Throwable cause) {
      super(cause);
    }
  }
}

package com.hsbc.market.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.market.config.MarketProperties;
import com.hsbc.market.dao.ExportJobDao;
import com.hsbc.market.dao.MarketReadDao;
import com.hsbc.market.export.CsvExportGenerator;
import com.hsbc.market.export.ExportCancelledException;
import com.hsbc.market.export.PdfExportGenerator;
import com.hsbc.market.export.storage.LocalExportStorage;
import com.hsbc.market.model.domain.ExportDomain.Artifact;
import com.hsbc.market.model.domain.ExportDomain.Format;
import com.hsbc.market.model.domain.ExportDomain.Job;
import com.hsbc.market.model.domain.ExportDomain.RequestSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** Claims queued jobs and drives their bounded generation lifecycle. */
@Service
public class ExportWorkerService {
  private static final Logger LOGGER = LoggerFactory.getLogger(ExportWorkerService.class);
  private final String workerId = UUID.randomUUID().toString();
  private final ExportJobDao jobs;
  private final LocalExportStorage storage;
  private final MarketReadDao marketReadDao;
  private final CsvExportGenerator csv;
  private final PdfExportGenerator pdf;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final MeterRegistry registry;
  private final Duration lease;
  private final TransactionOperations stateTransaction;
  private final TransactionOperations generationTransaction;

  public ExportWorkerService(
      ExportJobDao jobs,
      MarketReadDao marketReadDao,
      LocalExportStorage storage,
      CsvExportGenerator csv,
      PdfExportGenerator pdf,
      ObjectMapper objectMapper,
      Clock clock,
      MeterRegistry registry,
      @Qualifier("queryTransactionManager") PlatformTransactionManager queryTransactionManager,
      @Qualifier("exportTransactionManager") PlatformTransactionManager exportTransactionManager,
      MarketProperties properties) {
    this.jobs = jobs;
    this.marketReadDao = marketReadDao;
    this.storage = storage;
    this.csv = csv;
    this.pdf = pdf;
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.registry = registry;
    this.lease = properties.export().lease();
    TransactionTemplate state = new TransactionTemplate(queryTransactionManager);
    state.setTimeout(Math.toIntExact(properties.database().queryTimeout().toSeconds()));
    this.stateTransaction = state;
    TransactionTemplate generation = new TransactionTemplate(exportTransactionManager);
    generation.setReadOnly(true);
    generation.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    generation.setTimeout(Math.toIntExact(properties.database().exportTimeout().toSeconds()));
    this.generationTransaction = generation;
  }

  /** Processes at most one queued export and reports whether work was claimed. */
  public boolean runOnce() {
    Job job =
        stateTransaction.execute(
            status -> jobs.claimNext(workerId, clock.instant().plus(lease)).orElse(null));
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
      if (Boolean.TRUE.equals(
          stateTransaction.execute(status -> jobs.cancellationRequested(job.exportId())))) {
        storage.delete(artifact.contentKey());
        stateTransaction.executeWithoutResult(
            status -> jobs.markCancelled(job.exportId(), job.stateRevision(), clock.instant()));
        outcome = "cancelled";
      } else {
        Artifact completedArtifact = artifact;
        stateTransaction.executeWithoutResult(
            status ->
                jobs.succeed(
                    job.exportId(), job.stateRevision(), completedArtifact, clock.instant()));
        outcome = "succeeded";
        registry
            .summary("market.export.rows", "format", job.format().name().toLowerCase())
            .record(artifact.rowCount());
        registry
            .summary("market.export.bytes", "format", job.format().name().toLowerCase())
            .record(artifact.contentLength());
      }
    } catch (ExportCancelledException error) {
      stateTransaction.executeWithoutResult(
          status -> jobs.markCancelled(job.exportId(), job.stateRevision(), clock.instant()));
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
      stateTransaction.executeWithoutResult(
          status ->
              jobs.fail(
                  job.exportId(),
                  job.stateRevision(),
                  "EXPORT_GENERATION_FAILED",
                  "Export generation failed.",
                  clock.instant()));
    } finally {
      registry.counter("market.export.jobs", "status", outcome).increment();
      Timer.builder("market.export.generation.duration")
          .tags("format", job.format().name().toLowerCase(), "outcome", outcome)
          .register(registry)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    return true;
  }

  /** Requeues jobs abandoned after a worker lease expired. */
  public int recoverExpiredLeases() {
    Integer recovered =
        stateTransaction.execute(status -> jobs.recoverExpiredLeases(clock.instant()));
    return recovered == null ? 0 : recovered;
  }

  /** Expires old jobs and removes their stored artifacts. */
  public int cleanupExpired() {
    var expired = stateTransaction.execute(status -> jobs.expireBefore(clock.instant(), 100));
    if (expired == null) return 0;
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
      long currentRevision = marketReadDao.currentExportDataRevision();
      if (currentRevision != job.sourceRevision()
          || currentRevision != snapshot.selection().dataRevision()) {
        throw new IllegalStateException(
            "business data changed after this export was queued; recreate the export");
      }
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

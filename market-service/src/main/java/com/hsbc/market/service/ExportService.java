package com.hsbc.market.service;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hsbc.market.config.MarketProperties;
import com.hsbc.market.dao.ExportJobDao;
import com.hsbc.market.exception.MarketException;
import com.hsbc.market.export.storage.LocalExportStorage;
import com.hsbc.market.model.domain.ExportDomain.Content;
import com.hsbc.market.model.domain.ExportDomain.CreateCommand;
import com.hsbc.market.model.domain.ExportDomain.Format;
import com.hsbc.market.model.domain.ExportDomain.Job;
import com.hsbc.market.model.domain.ExportDomain.PdfOptions;
import com.hsbc.market.model.domain.ExportDomain.RequestSnapshot;
import com.hsbc.market.model.domain.ExportDomain.Status;
import com.hsbc.market.model.domain.MarketDomain.Metric;
import com.hsbc.market.model.domain.MarketDomain.MetricField;
import com.hsbc.market.model.domain.MarketDomain.MetricMethod;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates, creates, cancels, and resolves durable export jobs. */
@Service
public class ExportService {
  private final ExportJobDao jobs;
  private final LocalExportStorage storage;
  private final MarketQueryService queryService;
  private final ObjectMapper canonicalMapper;
  private final Clock clock;
  private final MeterRegistry registry;
  private final Duration retention;
  private final int totalCapacity;

  public ExportService(
      ExportJobDao jobs,
      LocalExportStorage storage,
      MarketQueryService queryService,
      Clock clock,
      MeterRegistry registry,
      MarketProperties properties) {
    this.jobs = jobs;
    this.storage = storage;
    this.queryService = queryService;
    this.canonicalMapper =
        JsonMapper.builder()
            .findAndAddModules()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
    this.clock = clock;
    this.registry = registry;
    this.retention = properties.export().retention();
    this.totalCapacity = properties.export().queueCapacity() + properties.export().workers();
  }

  /** Creates an idempotent job pinned to a server-owned live-data revision. */
  @Transactional(transactionManager = "queryTransactionManager")
  public Job create(CreateCommand command) {
    validate(command);

    var resolved = queryService.resolve(command.selection());
    PdfOptions pdf = command.format() == Format.PDF ? defaults(command.pdfOptions()) : null;
    RequestSnapshot snapshot = new RequestSnapshot(command.format(), resolved, pdf);
    String payload = serialize(snapshot);
    String requestHash = QueryFingerprint.digest(payload).substring("sha256:".length());

    Job existing = jobs.findByIdempotencyKey(command.idempotencyKey()).orElse(null);
    if (existing != null) {
      if (!existing.requestSha256().equals(requestHash)) {
        throw MarketException.conflict(
            "IDEMPOTENCY_KEY_REUSED", "idempotency key was already used for another request");
      }
      return existing;
    }

    if (jobs.activeCount() >= totalCapacity) {
      throw new MarketException(
          MarketException.Kind.CAPACITY, "CAPACITY_EXCEEDED", "export queue is full");
    }

    Instant now = clock.instant();
    Job job =
        new Job(
            UUID.randomUUID(),
            command.idempotencyKey(),
            requestHash,
            payload,
            resolved.dataRevision(),
            resolved.queryFingerprint(),
            command.format(),
            Status.QUEUED,
            0,
            false,
            null,
            null,
            now,
            null,
            null,
            now.plus(retention),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    Job insertedOrExisting = jobs.insert(job);

    if (!insertedOrExisting.requestSha256().equals(requestHash)) {
      throw MarketException.conflict(
          "IDEMPOTENCY_KEY_REUSED", "idempotency key was already used for another request");
    }
    if (insertedOrExisting.exportId().equals(job.exportId())) {
      registry.counter("market.export.jobs", "status", "queued").increment();
    }

    return insertedOrExisting;
  }

  /** Returns one export job or a stable not-found error. */
  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 3)
  public Job get(UUID exportId) {
    return jobs.find(exportId).orElseThrow(() -> MarketException.notFound("export was not found"));
  }

  /** Requests cancellation and removes any completed artifact. */
  @Transactional(transactionManager = "queryTransactionManager")
  public void cancel(UUID exportId) {
    Job job = get(exportId);

    if (job.contentKey() != null) {
      try {
        storage.delete(job.contentKey());
      } catch (Exception error) {
        throw new IllegalStateException("cannot delete completed export content", error);
      }
    }

    if (jobs.cancel(exportId)) {
      registry.counter("market.export.jobs", "status", "cancelled").increment();
    }
  }

  /** Resolves downloadable content only for a completed, unexpired job. */
  @Transactional(transactionManager = "queryTransactionManager", readOnly = true, timeout = 3)
  public Content locateContent(UUID exportId) {
    Job job = get(exportId);

    if (job.expiresAt().isBefore(clock.instant()) || job.status() == Status.EXPIRED) {
      throw new MarketException(
          MarketException.Kind.GONE, "EXPORT_EXPIRED", "export content has expired");
    }
    if (job.status() != Status.SUCCEEDED) {
      throw MarketException.conflict(
          "EXPORT_NOT_READY", "export content is not available in its current state");
    }
    if (job.contentKey() == null || job.contentLength() == null || job.contentSha256() == null) {
      throw new IllegalStateException("completed export metadata is incomplete");
    }

    try {
      if (!Files.isRegularFile(storage.path(job.contentKey()))
          || Files.size(storage.path(job.contentKey())) != job.contentLength()) {
        throw new IllegalStateException("completed export content is unavailable");
      }
    } catch (java.io.IOException error) {
      throw new IllegalStateException("cannot inspect export content", error);
    }
    String filename =
        job.format() == Format.CSV
            ? "property-market-analysis.csv"
            : "property-market-analysis.pdf";

    return new Content(
        job.exportId(),
        job.contentKey(),
        job.contentType(),
        filename,
        job.contentLength(),
        job.contentSha256(),
        job.expiresAt());
  }

  private void validate(CreateCommand command) {
    if (command == null
        || command.format() == null
        || command.selection() == null
        || command.idempotencyKey() == null) {
      throw MarketException.validation(
          "VALIDATION_ERROR", "format, selection, and idempotency key are required");
    }
    if (command.format() == Format.CSV && command.pdfOptions() != null) {
      throw MarketException.validation(
          "VALIDATION_ERROR", "pdf_options is forbidden for CSV exports");
    }
    if (command.format() == Format.PDF
        && command.pdfOptions() != null
        && (command.pdfOptions().detailLimit() < 0 || command.pdfOptions().detailLimit() > 5000)) {
      throw MarketException.validation(
          "VALIDATION_ERROR", "PDF detail limit must be between 0 and 5000");
    }
  }

  private PdfOptions defaults(PdfOptions options) {
    if (options != null) return options;
    return new PdfOptions(
        "market-analysis-v1",
        true,
        500,
        List.of(
            new Metric("property_count", MetricMethod.COUNT, MetricField.ALL),
            new Metric("average_price", MetricMethod.AVG, MetricField.PRICE),
            new Metric("minimum_price", MetricMethod.MIN, MetricField.PRICE),
            new Metric("maximum_price", MetricMethod.MAX, MetricField.PRICE)),
        null);
  }

  private String serialize(RequestSnapshot snapshot) {
    try {
      return canonicalMapper.writeValueAsString(snapshot);
    } catch (Exception error) {
      throw new IllegalStateException("cannot serialize normalized export request", error);
    }
  }
}

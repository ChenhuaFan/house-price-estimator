package com.hsbc.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hsbc.market.config.MarketProperties;
import com.hsbc.market.dao.ExportJobDao;
import com.hsbc.market.exception.MarketException;
import com.hsbc.market.export.storage.LocalExportStorage;
import com.hsbc.market.model.domain.ExportDomain.CreateCommand;
import com.hsbc.market.model.domain.ExportDomain.Format;
import com.hsbc.market.model.domain.ExportDomain.Job;
import com.hsbc.market.model.domain.ExportDomain.PdfOptions;
import com.hsbc.market.model.domain.ExportDomain.Status;
import com.hsbc.market.model.domain.MarketDomain;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class ExportServiceTest {
  private static final UUID VERSION = UUID.fromString("00000000-0000-4000-8000-000000000001");
  private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");

  @TempDir Path directory;
  private ExportJobDao jobs;
  private LocalExportStorage storage;
  private MarketQueryService queryService;
  private ExportService service;
  private MarketDomain.Selection selection;

  @BeforeEach
  void setUp() {
    jobs = mock(ExportJobDao.class);
    storage = mock(LocalExportStorage.class);
    queryService = mock(MarketQueryService.class);
    selection = new MarketDomain.Selection(VERSION, MarketDomain.Filters.empty(), null);
    when(queryService.resolve(any()))
        .thenReturn(
            new MarketDomain.ResolvedSelection(
                new MarketDomain.DatasetVersion(VERSION, "a".repeat(64)),
                MarketDomain.Filters.empty(),
                new MarketDomain.Sort(
                    MarketDomain.SortField.SOURCE_ID, MarketDomain.SortDirection.ASC),
                "sha256:" + "b".repeat(64)));
    when(jobs.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(jobs.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
    service =
        new ExportService(
            jobs,
            storage,
            queryService,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new SimpleMeterRegistry(),
            properties(directory));
  }

  @Test
  void createsCsvAndDefaultedPdfJobsAndRejectsCapacity() {
    Job csv = service.create(new CreateCommand(Format.CSV, selection, null, UUID.randomUUID()));
    assertThat(csv.status()).isEqualTo(Status.QUEUED);
    assertThat(csv.requestPayload()).contains("\"format\":\"CSV\"");

    Job pdf = service.create(new CreateCommand(Format.PDF, selection, null, UUID.randomUUID()));
    assertThat(pdf.requestPayload())
        .contains("market-analysis-v1", "property_count", "average_price");

    when(jobs.activeCount()).thenReturn(22L);
    assertThatThrownBy(
            () -> service.create(new CreateCommand(Format.CSV, selection, null, UUID.randomUUID())))
        .isInstanceOf(MarketException.class)
        .extracting(error -> ((MarketException) error).code())
        .isEqualTo("CAPACITY_EXCEEDED");
  }

  @Test
  void validatesEveryExportCommandConstraint() {
    PdfOptions options = new PdfOptions("market-analysis-v1", true, 10, List.of(), null);
    List<CreateCommand> invalid =
        java.util.Arrays.asList(
            null,
            new CreateCommand(null, selection, null, UUID.randomUUID()),
            new CreateCommand(Format.CSV, null, null, UUID.randomUUID()),
            new CreateCommand(Format.CSV, selection, null, null),
            new CreateCommand(Format.CSV, selection, options, UUID.randomUUID()),
            new CreateCommand(
                Format.PDF,
                selection,
                new PdfOptions("market-analysis-v1", true, -1, List.of(), null),
                UUID.randomUUID()),
            new CreateCommand(
                Format.PDF,
                selection,
                new PdfOptions("market-analysis-v1", true, 5001, List.of(), null),
                UUID.randomUUID()));
    invalid.forEach(
        command ->
            assertThatThrownBy(() -> service.create(command)).isInstanceOf(MarketException.class));
  }

  @Test
  void handlesIdempotencyConflictsAndExistingRequests() {
    UUID key = UUID.randomUUID();
    Job created = service.create(new CreateCommand(Format.CSV, selection, null, key));
    when(jobs.findByIdempotencyKey(key)).thenReturn(Optional.of(created));
    assertThat(service.create(new CreateCommand(Format.CSV, selection, null, key)))
        .isEqualTo(created);

    Job conflicting = copy(created, Status.QUEUED, null, null, null, NOW.plusSeconds(60));
    conflicting =
        new Job(
            conflicting.exportId(),
            conflicting.idempotencyKey(),
            "f".repeat(64),
            conflicting.requestPayload(),
            conflicting.datasetVersionId(),
            conflicting.queryFingerprint(),
            conflicting.format(),
            conflicting.status(),
            conflicting.stateRevision(),
            conflicting.cancelRequested(),
            conflicting.leaseOwner(),
            conflicting.leaseExpiresAt(),
            conflicting.createdAt(),
            conflicting.startedAt(),
            conflicting.completedAt(),
            conflicting.expiresAt(),
            conflicting.rowCount(),
            conflicting.detailRowCount(),
            conflicting.detailsTruncated(),
            conflicting.contentKey(),
            conflicting.contentType(),
            conflicting.contentLength(),
            conflicting.contentSha256(),
            conflicting.failureCode());
    when(jobs.findByIdempotencyKey(key)).thenReturn(Optional.of(conflicting));
    assertThatThrownBy(() -> service.create(new CreateCommand(Format.CSV, selection, null, key)))
        .isInstanceOf(MarketException.class)
        .extracting(error -> ((MarketException) error).code())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void locatesOnlyCompleteUnexpiredContent() throws Exception {
    UUID id = UUID.randomUUID();
    Path file = directory.resolve("content.csv");
    Files.writeString(file, "1234");
    when(storage.path("content.csv")).thenReturn(file);
    Job succeeded =
        job(id, Status.SUCCEEDED, "content.csv", 4L, "d".repeat(64), NOW.plusSeconds(60));
    when(jobs.find(id)).thenReturn(Optional.of(succeeded));
    assertThat(service.locateContent(id).filename()).isEqualTo("property-market-analysis.csv");

    when(jobs.find(id))
        .thenReturn(Optional.of(job(id, Status.QUEUED, null, null, null, NOW.plusSeconds(60))));
    assertThatThrownBy(() -> service.locateContent(id)).isInstanceOf(MarketException.class);
    when(jobs.find(id))
        .thenReturn(Optional.of(job(id, Status.EXPIRED, null, null, null, NOW.plusSeconds(60))));
    assertThatThrownBy(() -> service.locateContent(id)).isInstanceOf(MarketException.class);
    when(jobs.find(id))
        .thenReturn(Optional.of(job(id, Status.SUCCEEDED, null, 4L, null, NOW.minusSeconds(1))));
    assertThatThrownBy(() -> service.locateContent(id)).isInstanceOf(MarketException.class);
    when(jobs.find(id))
        .thenReturn(
            Optional.of(job(id, Status.SUCCEEDED, null, 4L, "d".repeat(64), NOW.plusSeconds(60))));
    assertThatThrownBy(() -> service.locateContent(id)).isInstanceOf(IllegalStateException.class);

    Files.writeString(file, "wrong-size");
    when(jobs.find(id)).thenReturn(Optional.of(succeeded));
    assertThatThrownBy(() -> service.locateContent(id)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void getAndCancelUseStableErrorsAndRemoveCompletedContent() throws Exception {
    UUID id = UUID.randomUUID();
    when(jobs.find(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(id)).isInstanceOf(MarketException.class);

    Job succeeded =
        job(id, Status.SUCCEEDED, "content.csv", 4L, "d".repeat(64), NOW.plusSeconds(60));
    when(jobs.find(id)).thenReturn(Optional.of(succeeded));
    when(jobs.cancel(id)).thenReturn(true);
    service.cancel(id);
    verify(storage).delete("content.csv");
    verify(jobs).cancel(id);

    when(storage.path("unused")).thenThrow(new IllegalStateException());
    org.mockito.Mockito.doThrow(new IOException("denied")).when(storage).delete("content.csv");
    assertThatThrownBy(() -> service.cancel(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("delete completed");
  }

  private static Job job(
      UUID id, Status status, String key, Long length, String sha, Instant expiresAt) {
    return new Job(
        id,
        UUID.randomUUID(),
        "a".repeat(64),
        "{}",
        VERSION,
        "sha256:" + "b".repeat(64),
        Format.CSV,
        status,
        0,
        false,
        null,
        null,
        NOW,
        null,
        null,
        expiresAt,
        null,
        null,
        null,
        key,
        "text/csv;charset=UTF-8",
        length,
        sha,
        null);
  }

  private static Job copy(
      Job source, Status status, String key, Long length, String sha, Instant expiresAt) {
    return new Job(
        source.exportId(),
        source.idempotencyKey(),
        source.requestSha256(),
        source.requestPayload(),
        source.datasetVersionId(),
        source.queryFingerprint(),
        source.format(),
        status,
        source.stateRevision(),
        source.cancelRequested(),
        source.leaseOwner(),
        source.leaseExpiresAt(),
        source.createdAt(),
        source.startedAt(),
        source.completedAt(),
        expiresAt,
        source.rowCount(),
        source.detailRowCount(),
        source.detailsTruncated(),
        key,
        source.contentType(),
        length,
        sha,
        source.failureCode());
  }

  private static MarketProperties properties(Path root) {
    return new MarketProperties(
        new MarketProperties.Database(
            "jdbc:postgresql://localhost/test",
            "user",
            "password",
            8,
            2,
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            Duration.ofSeconds(120)),
        new MarketProperties.ModelService(
            "http://localhost", Duration.ofSeconds(1), Duration.ofSeconds(3), 8),
        new MarketProperties.Export(
            root,
            2,
            20,
            Duration.ofHours(24),
            DataSize.ofMegabytes(512),
            Duration.ofSeconds(120),
            1_000_000,
            Duration.ofMinutes(3)),
        new MarketProperties.Cursor("a-test-secret-that-is-longer-than-thirty-two-characters"),
        "http://localhost:3000");
  }
}

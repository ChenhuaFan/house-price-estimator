package com.hsbc.market.model.domain;

import com.hsbc.market.model.domain.MarketDomain.Metric;
import com.hsbc.market.model.domain.MarketDomain.ResolvedSelection;
import com.hsbc.market.model.domain.MarketDomain.Selection;
import com.hsbc.market.model.domain.MarketDomain.WhatIfResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Namespace for immutable export commands, jobs, snapshots, and artifacts. */
public final class ExportDomain {
  private ExportDomain() {}

  public enum Format {
    CSV,
    PDF
  }

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED
  }

  public record PdfOptions(
      String template,
      boolean includeDetails,
      int detailLimit,
      List<Metric> metrics,
      WhatIfResult whatIf) {}

  public record CreateCommand(
      Format format, Selection selection, PdfOptions pdfOptions, UUID idempotencyKey) {}

  public record RequestSnapshot(
      Format format, ResolvedSelection selection, PdfOptions pdfOptions) {}

  public record Job(
      UUID exportId,
      UUID idempotencyKey,
      String requestSha256,
      String requestPayload,
      long sourceRevision,
      String queryFingerprint,
      Format format,
      Status status,
      long stateRevision,
      boolean cancelRequested,
      String leaseOwner,
      Instant leaseExpiresAt,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt,
      Instant expiresAt,
      Long rowCount,
      Long detailRowCount,
      Boolean detailsTruncated,
      String contentKey,
      String contentType,
      Long contentLength,
      String contentSha256,
      String failureCode) {}

  public record Artifact(
      String contentKey,
      String contentType,
      String filename,
      long contentLength,
      String contentSha256,
      long rowCount,
      long detailRowCount,
      boolean detailsTruncated) {}

  public record Content(
      UUID exportId,
      String contentKey,
      String contentType,
      String filename,
      long contentLength,
      String contentSha256,
      Instant expiresAt) {}
}

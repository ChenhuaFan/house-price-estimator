package com.hsbc.market.model.persistence;

import java.time.Instant;
import java.util.UUID;

public record ExportJobRecord(
    UUID exportId,
    UUID idempotencyKey,
    String requestSha256,
    String requestPayload,
    UUID datasetVersionId,
    String queryFingerprint,
    String format,
    String status,
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

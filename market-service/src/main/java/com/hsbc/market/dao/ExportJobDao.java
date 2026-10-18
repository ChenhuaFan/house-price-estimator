package com.hsbc.market.dao;

import com.hsbc.market.mapper.query.ExportJobMapper;
import com.hsbc.market.model.domain.ExportDomain.Artifact;
import com.hsbc.market.model.domain.ExportDomain.Format;
import com.hsbc.market.model.domain.ExportDomain.Job;
import com.hsbc.market.model.domain.ExportDomain.Status;
import com.hsbc.market.model.persistence.ExportJobRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Persists export job state transitions through the MyBatis mapper. */
@Repository
public class ExportJobDao {
  private final ExportJobMapper mapper;

  public ExportJobDao(ExportJobMapper mapper) {
    this.mapper = mapper;
  }

  public boolean operationsWritable() {
    return mapper.operationsWritable();
  }

  public Optional<Job> findByIdempotencyKey(UUID key) {
    return Optional.ofNullable(mapper.findByIdempotencyKey(key)).map(ExportJobDao::toDomain);
  }

  public Optional<Job> find(UUID exportId) {
    return Optional.ofNullable(mapper.find(exportId)).map(ExportJobDao::toDomain);
  }

  public Job insert(Job job) {
    mapper.insert(toRecord(job));
    return findByIdempotencyKey(job.idempotencyKey()).orElseThrow();
  }

  public long activeCount() {
    return mapper.activeCount();
  }

  public Optional<Job> claimNext(String workerId, Instant leaseExpiresAt) {
    return Optional.ofNullable(mapper.claimNext(workerId, leaseExpiresAt))
        .map(ExportJobDao::toDomain);
  }

  public boolean cancel(UUID exportId) {
    return mapper.cancel(exportId) > 0;
  }

  public boolean cancellationRequested(UUID exportId) {
    return Boolean.TRUE.equals(mapper.cancellationRequested(exportId));
  }

  public void succeed(UUID exportId, long revision, Artifact artifact, Instant completedAt) {
    int updated =
        mapper.succeed(
            exportId,
            revision,
            completedAt,
            artifact.rowCount(),
            artifact.detailRowCount(),
            artifact.detailsTruncated(),
            artifact.contentKey(),
            artifact.contentType(),
            artifact.contentLength(),
            artifact.contentSha256());
    if (updated != 1) throw new IllegalStateException("export success transition lost its lease");
  }

  public void fail(UUID exportId, long revision, String code, String summary, Instant completedAt) {
    mapper.fail(exportId, revision, code, summary, completedAt);
  }

  public void markCancelled(UUID exportId, long revision, Instant completedAt) {
    mapper.markCancelled(exportId, revision, completedAt);
  }

  public int recoverExpiredLeases(Instant now) {
    return mapper.recoverExpiredLeases(now);
  }

  public List<Job> expireBefore(Instant now, int limit) {
    List<ExportJobRecord> expired = mapper.expired(now, limit);
    expired.forEach(record -> mapper.markExpired(record.exportId()));
    return expired.stream().map(ExportJobDao::toDomain).toList();
  }

  private static Job toDomain(ExportJobRecord row) {
    return new Job(
        row.exportId(),
        row.idempotencyKey(),
        row.requestSha256(),
        row.requestPayload(),
        row.datasetVersionId(),
        row.queryFingerprint(),
        Format.valueOf(row.format()),
        Status.valueOf(row.status()),
        row.stateRevision(),
        row.cancelRequested(),
        row.leaseOwner(),
        row.leaseExpiresAt(),
        row.createdAt(),
        row.startedAt(),
        row.completedAt(),
        row.expiresAt(),
        row.rowCount(),
        row.detailRowCount(),
        row.detailsTruncated(),
        row.contentKey(),
        row.contentType(),
        row.contentLength(),
        row.contentSha256(),
        row.failureCode());
  }

  private static ExportJobRecord toRecord(Job job) {
    return new ExportJobRecord(
        job.exportId(),
        job.idempotencyKey(),
        job.requestSha256(),
        job.requestPayload(),
        job.datasetVersionId(),
        job.queryFingerprint(),
        job.format().name(),
        job.status().name(),
        job.stateRevision(),
        job.cancelRequested(),
        job.leaseOwner(),
        job.leaseExpiresAt(),
        job.createdAt(),
        job.startedAt(),
        job.completedAt(),
        job.expiresAt(),
        job.rowCount(),
        job.detailRowCount(),
        job.detailsTruncated(),
        job.contentKey(),
        job.contentType(),
        job.contentLength(),
        job.contentSha256(),
        job.failureCode());
  }
}

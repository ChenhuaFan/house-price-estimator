package com.hsbc.market.mapper.query;

import com.hsbc.market.model.persistence.ExportJobRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** MyBatis operations for durable export job transitions. */
@Mapper
public interface ExportJobMapper {
  boolean operationsWritable();

  ExportJobRecord find(@Param("id") UUID id);

  ExportJobRecord findByIdempotencyKey(@Param("key") UUID key);

  int insert(ExportJobRecord job);

  long activeCount();

  ExportJobRecord claimNext(
      @Param("workerId") String workerId, @Param("leaseExpiresAt") Instant leaseExpiresAt);

  int cancel(@Param("id") UUID id);

  Boolean cancellationRequested(@Param("id") UUID id);

  int succeed(
      @Param("exportId") UUID exportId,
      @Param("revision") long revision,
      @Param("completedAt") Instant completedAt,
      @Param("rowCount") long rowCount,
      @Param("detailRowCount") long detailRowCount,
      @Param("detailsTruncated") boolean detailsTruncated,
      @Param("contentKey") String contentKey,
      @Param("contentType") String contentType,
      @Param("contentLength") long contentLength,
      @Param("contentSha256") String contentSha256);

  int fail(
      @Param("exportId") UUID exportId,
      @Param("revision") long revision,
      @Param("code") String code,
      @Param("summary") String summary,
      @Param("completedAt") Instant completedAt);

  int markCancelled(
      @Param("exportId") UUID exportId,
      @Param("revision") long revision,
      @Param("completedAt") Instant completedAt);

  int recoverExpiredLeases(@Param("now") Instant now);

  List<ExportJobRecord> expired(@Param("now") Instant now, @Param("limit") int limit);

  int markExpired(@Param("id") UUID id);
}

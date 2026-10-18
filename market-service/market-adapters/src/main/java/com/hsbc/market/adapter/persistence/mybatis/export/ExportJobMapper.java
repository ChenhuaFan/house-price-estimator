package com.hsbc.market.adapter.persistence.mybatis.export;

import com.hsbc.market.adapter.persistence.mybatis.record.ExportJobRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExportJobMapper {
  String COLUMNS =
      """
            export_id, idempotency_key, request_sha256, request_payload::text AS request_payload,
            dataset_version_id, query_fingerprint, format, status, state_revision,
            cancel_requested, lease_owner, lease_expires_at, created_at, started_at,
            completed_at, expires_at, row_count, detail_row_count, details_truncated,
            content_key, content_type, content_length, content_sha256, failure_code
            """;
  String RETURNING_COLUMNS =
      """
            job.export_id, job.idempotency_key, job.request_sha256,
            job.request_payload::text AS request_payload, job.dataset_version_id,
            job.query_fingerprint, job.format, job.status, job.state_revision,
            job.cancel_requested, job.lease_owner, job.lease_expires_at, job.created_at,
            job.started_at, job.completed_at, job.expires_at, job.row_count,
            job.detail_row_count, job.details_truncated, job.content_key,
            job.content_type, job.content_length, job.content_sha256, job.failure_code
            """;

  @Select("SELECT " + COLUMNS + " FROM market_ops.export_jobs WHERE export_id = #{id}")
  ExportJobRecord find(UUID id);

  @Select("SELECT " + COLUMNS + " FROM market_ops.export_jobs WHERE idempotency_key = #{key}")
  ExportJobRecord findByIdempotencyKey(UUID key);

  @Insert(
      """
            INSERT INTO market_ops.export_jobs (
                export_id, idempotency_key, request_sha256, request_payload,
                dataset_version_id, query_fingerprint, format, status,
                created_at, expires_at
            ) VALUES (
                #{exportId}, #{idempotencyKey}, #{requestSha256}, CAST(#{requestPayload} AS jsonb),
                #{datasetVersionId}, #{queryFingerprint}, #{format}, #{status},
                #{createdAt}, #{expiresAt}
            )
            ON CONFLICT (idempotency_key) DO NOTHING
            """)
  int insert(ExportJobRecord job);

  @Select("SELECT count(*) FROM market_ops.export_jobs WHERE status IN ('QUEUED', 'RUNNING')")
  long activeCount();

  @Select(
      """
            WITH candidate AS (
                SELECT export_id
                FROM market_ops.export_jobs
                WHERE status = 'QUEUED' AND cancel_requested = false
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE market_ops.export_jobs job
               SET status = 'RUNNING', state_revision = state_revision + 1,
                   lease_owner = #{workerId}, lease_expires_at = #{leaseExpiresAt},
                   started_at = coalesce(started_at, now())
              FROM candidate
             WHERE job.export_id = candidate.export_id
            RETURNING """
          + " "
          + RETURNING_COLUMNS)
  ExportJobRecord claimNext(
      @Param("workerId") String workerId, @Param("leaseExpiresAt") Instant leaseExpiresAt);

  @Update(
      """
            UPDATE market_ops.export_jobs
               SET cancel_requested = true,
                   status = 'CANCELLED',
                   state_revision = state_revision + 1,
                   completed_at = coalesce(completed_at, now()),
                   lease_owner = null,
                   lease_expires_at = null,
                   content_key = CASE WHEN status = 'SUCCEEDED' THEN null ELSE content_key END,
                   content_type = CASE WHEN status = 'SUCCEEDED' THEN null ELSE content_type END,
                   content_length = CASE WHEN status = 'SUCCEEDED' THEN null ELSE content_length END,
                   content_sha256 = CASE WHEN status = 'SUCCEEDED' THEN null ELSE content_sha256 END
             WHERE export_id = #{id} AND status NOT IN ('CANCELLED', 'EXPIRED')
            """)
  int cancel(UUID id);

  @Select("SELECT cancel_requested FROM market_ops.export_jobs WHERE export_id = #{id}")
  Boolean cancellationRequested(UUID id);

  @Update(
      """
            UPDATE market_ops.export_jobs
               SET status = 'SUCCEEDED', state_revision = state_revision + 1,
                   completed_at = #{completedAt}, lease_owner = null, lease_expires_at = null,
                   row_count = #{rowCount}, detail_row_count = #{detailRowCount},
                   details_truncated = #{detailsTruncated}, content_key = #{contentKey},
                   content_type = #{contentType}, content_length = #{contentLength},
                   content_sha256 = #{contentSha256}
             WHERE export_id = #{exportId} AND state_revision = #{revision}
               AND status = 'RUNNING' AND cancel_requested = false
            """)
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

  @Update(
      """
            UPDATE market_ops.export_jobs
               SET status = 'FAILED', state_revision = state_revision + 1,
                   completed_at = #{completedAt}, lease_owner = null, lease_expires_at = null,
                   failure_code = #{code}, failure_summary = left(#{summary}, 512)
             WHERE export_id = #{exportId} AND state_revision = #{revision} AND status = 'RUNNING'
            """)
  int fail(
      @Param("exportId") UUID exportId,
      @Param("revision") long revision,
      @Param("code") String code,
      @Param("summary") String summary,
      @Param("completedAt") Instant completedAt);

  @Update(
      """
            UPDATE market_ops.export_jobs
               SET status = 'CANCELLED', state_revision = state_revision + 1,
                   completed_at = #{completedAt}, lease_owner = null, lease_expires_at = null
             WHERE export_id = #{exportId} AND state_revision = #{revision} AND status = 'RUNNING'
            """)
  int markCancelled(
      @Param("exportId") UUID exportId,
      @Param("revision") long revision,
      @Param("completedAt") Instant completedAt);

  @Update(
      """
            UPDATE market_ops.export_jobs
               SET status = CASE WHEN cancel_requested THEN 'CANCELLED' ELSE 'QUEUED' END,
                   state_revision = state_revision + 1,
                   completed_at = CASE WHEN cancel_requested THEN coalesce(completed_at, #{now}) ELSE completed_at END,
                   lease_owner = null, lease_expires_at = null
             WHERE status = 'RUNNING' AND lease_expires_at < #{now}
            """)
  int recoverExpiredLeases(Instant now);

  @Select(
      """
            SELECT """
          + COLUMNS
          + """
              FROM market_ops.export_jobs
             WHERE expires_at <= #{now} AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
             ORDER BY expires_at
             LIMIT #{limit}
            """)
  List<ExportJobRecord> expired(@Param("now") Instant now, @Param("limit") int limit);

  @Update(
      """
            UPDATE market_ops.export_jobs
               SET status = 'EXPIRED', state_revision = state_revision + 1,
                   content_key = null, content_type = null, content_length = null, content_sha256 = null
             WHERE export_id = #{id} AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
            """)
  int markExpired(UUID id);
}

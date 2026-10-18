CREATE SCHEMA market_ops AUTHORIZATION db_migration;
REVOKE ALL ON SCHEMA market_ops FROM PUBLIC;

SET ROLE db_migration;

CREATE TABLE market_ops.export_jobs (
    export_id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL UNIQUE,
    request_sha256 char(64) NOT NULL
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    request_payload jsonb NOT NULL,
    dataset_version_id uuid NOT NULL
        REFERENCES catalog.dataset_versions(dataset_version_id),
    query_fingerprint varchar(71) NOT NULL
        CHECK (query_fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    format varchar(3) NOT NULL CHECK (format IN ('CSV', 'PDF')),
    status varchar(16) NOT NULL CHECK (status IN (
        'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED'
    )),
    state_revision bigint NOT NULL DEFAULT 0 CHECK (state_revision >= 0),
    cancel_requested boolean NOT NULL DEFAULT false,
    lease_owner varchar(128),
    lease_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    expires_at timestamptz NOT NULL,
    row_count bigint CHECK (row_count >= 0),
    detail_row_count bigint CHECK (detail_row_count >= 0),
    details_truncated boolean,
    content_key text,
    content_type varchar(64),
    content_length bigint CHECK (content_length >= 0),
    content_sha256 char(64)
        CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'),
    failure_code varchar(64),
    failure_summary varchar(512),
    CONSTRAINT export_job_content_key_relative CHECK (
        content_key IS NULL OR (
            content_key !~ '^/'
            AND content_key !~ '(^|/)\.\.(/|$)'
            AND content_key !~ '\\'
        )
    ),
    CONSTRAINT export_job_lease_consistent CHECK (
        (status = 'RUNNING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'RUNNING')
    )
);

CREATE INDEX export_jobs_claim_idx
    ON market_ops.export_jobs (status, created_at)
    WHERE status = 'QUEUED';
CREATE INDEX export_jobs_expiry_idx
    ON market_ops.export_jobs (expires_at)
    WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED');

REVOKE ALL ON ALL TABLES IN SCHEMA market_ops FROM PUBLIC;
GRANT USAGE ON SCHEMA market_ops TO market_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON market_ops.export_jobs TO market_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA market_ops REVOKE ALL ON TABLES FROM PUBLIC;

RESET ROLE;

SET ROLE db_migration;

CREATE DOMAIN ml_registry.root_relative_path AS text
CHECK (
    VALUE ~ '^[A-Za-z0-9][A-Za-z0-9._/-]*$'
    AND VALUE !~ '(^|/)\.\.(/|$)'
    AND VALUE !~ '(^|/)\.(/|$)'
    AND VALUE !~ '//'
);

CREATE TABLE ml_registry.training_runs (
    training_run_id uuid PRIMARY KEY,
    dataset_version_id uuid NOT NULL
        REFERENCES catalog.dataset_versions (dataset_version_id) ON DELETE RESTRICT,
    git_commit_sha varchar(64) NOT NULL CHECK (git_commit_sha ~ '^[0-9a-f]{7,64}$'),
    run_stage varchar(16) NOT NULL CHECK (run_stage IN (
        'EXPORTING', 'SPLITTING', 'TRAINING', 'EVALUATING', 'REGISTERING', 'COMPLETE'
    )),
    run_status varchar(16) NOT NULL CHECK (run_status IN (
        'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED'
    )),
    snapshot_path ml_registry.root_relative_path,
    snapshot_sha256 char(64) CHECK (snapshot_sha256 IS NULL OR snapshot_sha256 ~ '^[0-9a-f]{64}$'),
    snapshot_metadata_path ml_registry.root_relative_path,
    snapshot_metadata_sha256 char(64) CHECK (
        snapshot_metadata_sha256 IS NULL OR snapshot_metadata_sha256 ~ '^[0-9a-f]{64}$'
    ),
    split_manifest_path ml_registry.root_relative_path,
    split_manifest_sha256 char(64) CHECK (
        split_manifest_sha256 IS NULL OR split_manifest_sha256 ~ '^[0-9a-f]{64}$'
    ),
    split_seed integer NOT NULL DEFAULT 42 CHECK (split_seed = 42),
    train_ratio numeric(5,4) NOT NULL DEFAULT 0.8000 CHECK (train_ratio = 0.8000),
    state_revision bigint NOT NULL DEFAULT 0 CHECK (state_revision >= 0),
    total_rows integer CHECK (total_rows IS NULL OR total_rows > 1),
    training_rows integer CHECK (training_rows IS NULL OR training_rows > 0),
    evaluation_rows integer CHECK (evaluation_rows IS NULL OR evaluation_rows > 0),
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    error_summary text,
    CONSTRAINT training_run_snapshot_group CHECK (
        (snapshot_path IS NULL AND snapshot_sha256 IS NULL
            AND snapshot_metadata_path IS NULL AND snapshot_metadata_sha256 IS NULL)
        OR
        (snapshot_path IS NOT NULL AND snapshot_sha256 IS NOT NULL
            AND snapshot_metadata_path IS NOT NULL AND snapshot_metadata_sha256 IS NOT NULL)
    ),
    CONSTRAINT training_run_split_group CHECK (
        (split_manifest_path IS NULL AND split_manifest_sha256 IS NULL
            AND total_rows IS NULL AND training_rows IS NULL AND evaluation_rows IS NULL)
        OR
        (split_manifest_path IS NOT NULL AND split_manifest_sha256 IS NOT NULL
            AND total_rows IS NOT NULL AND training_rows IS NOT NULL AND evaluation_rows IS NOT NULL)
    ),
    CONSTRAINT training_run_row_count_matches CHECK (
        total_rows IS NULL OR total_rows = training_rows + evaluation_rows
    ),
    CONSTRAINT training_run_terminal_state CHECK (
        (run_status = 'RUNNING' AND run_stage <> 'COMPLETE'
            AND completed_at IS NULL AND error_summary IS NULL)
        OR
        (run_status IN ('SUCCEEDED', 'REJECTED') AND run_stage = 'COMPLETE'
            AND completed_at IS NOT NULL AND error_summary IS NULL
            AND snapshot_path IS NOT NULL AND split_manifest_path IS NOT NULL)
        OR
        (run_status = 'FAILED' AND run_stage <> 'COMPLETE'
            AND completed_at IS NOT NULL AND length(trim(coalesce(error_summary, ''))) > 0)
    )
);

CREATE INDEX training_runs_dataset_time_idx
    ON ml_registry.training_runs (dataset_version_id, started_at DESC);

CREATE TABLE ml_registry.training_run_metrics (
    training_run_id uuid PRIMARY KEY
        REFERENCES ml_registry.training_runs (training_run_id) ON DELETE RESTRICT,
    metrics_path ml_registry.root_relative_path NOT NULL,
    metrics_sha256 char(64) NOT NULL CHECK (metrics_sha256 ~ '^[0-9a-f]{64}$'),
    mae numeric(30,12) NOT NULL CHECK (mae >= 0),
    rmse numeric(30,12) NOT NULL CHECK (rmse >= 0),
    r2 numeric(30,12) NOT NULL CHECK (r2 <= 1),
    mape numeric(30,12) NOT NULL CHECK (mape >= 0),
    baseline_mae numeric(30,12) NOT NULL CHECK (baseline_mae >= 0),
    baseline_rmse numeric(30,12) NOT NULL CHECK (baseline_rmse >= 0),
    baseline_mape numeric(30,12) NOT NULL CHECK (baseline_mape >= 0),
    acceptance_policy_version varchar(64) NOT NULL
        CHECK (acceptance_policy_version = 'housing-linear-v1'),
    acceptance_passed boolean NOT NULL,
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT metrics_acceptance_matches_policy CHECK (
        acceptance_passed = (
            mae <= 15000 AND rmse <= 20000 AND r2 >= 0.9000 AND mape <= 7.5000
            AND mae < baseline_mae AND rmse < baseline_rmse AND mape < baseline_mape
        )
    )
);

CREATE TABLE ml_registry.model_versions (
    model_version varchar(128) PRIMARY KEY CHECK (
        model_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'
    ),
    training_run_id uuid NOT NULL UNIQUE
        REFERENCES ml_registry.training_runs (training_run_id) ON DELETE RESTRICT,
    model_type varchar(128) NOT NULL CHECK (length(model_type) > 0),
    artifact_path ml_registry.root_relative_path NOT NULL,
    artifact_sha256 char(64) NOT NULL CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
    metadata_path ml_registry.root_relative_path NOT NULL,
    metadata_sha256 char(64) NOT NULL CHECK (metadata_sha256 ~ '^[0-9a-f]{64}$'),
    lifecycle_status varchar(16) NOT NULL CHECK (
        lifecycle_status IN ('CANDIDATE', 'ACTIVE', 'RETIRED')
    ),
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    retired_at timestamptz,
    CONSTRAINT model_lifecycle_timestamps CHECK (
        (lifecycle_status = 'CANDIDATE' AND activated_at IS NULL AND retired_at IS NULL)
        OR
        (lifecycle_status = 'ACTIVE' AND activated_at IS NOT NULL AND retired_at IS NULL)
        OR
        (lifecycle_status = 'RETIRED' AND activated_at IS NOT NULL
            AND retired_at IS NOT NULL AND retired_at >= activated_at)
    )
);

CREATE UNIQUE INDEX model_versions_one_active_idx
    ON ml_registry.model_versions ((1)) WHERE lifecycle_status = 'ACTIVE';

CREATE FUNCTION ml_registry.start_run(
    p_training_run_id uuid,
    p_dataset_version_id uuid,
    p_git_commit_sha varchar
) RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ml_registry, catalog
AS $$
BEGIN
    INSERT INTO ml_registry.training_runs (
        training_run_id, dataset_version_id, git_commit_sha, run_stage, run_status
    ) VALUES (
        p_training_run_id, p_dataset_version_id, p_git_commit_sha, 'EXPORTING', 'RUNNING'
    );
    RETURN 0;
END;
$$;

CREATE FUNCTION ml_registry.advance_run(
    p_training_run_id uuid,
    p_expected_revision bigint,
    p_next_stage varchar,
    p_details jsonb DEFAULT '{}'::jsonb
) RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ml_registry
AS $$
DECLARE
    v_current_stage varchar;
    v_revision bigint;
BEGIN
    SELECT run_stage, state_revision INTO v_current_stage, v_revision
      FROM ml_registry.training_runs
     WHERE training_run_id = p_training_run_id AND run_status = 'RUNNING'
     FOR UPDATE;
    IF NOT FOUND OR v_revision <> p_expected_revision THEN
        RAISE EXCEPTION 'training run revision conflict';
    END IF;
    IF NOT ((v_current_stage = 'EXPORTING' AND p_next_stage = 'SPLITTING')
        OR (v_current_stage = 'SPLITTING' AND p_next_stage = 'TRAINING')
        OR (v_current_stage = 'TRAINING' AND p_next_stage = 'EVALUATING')) THEN
        RAISE EXCEPTION 'invalid stage transition from % to %', v_current_stage, p_next_stage;
    END IF;

    UPDATE ml_registry.training_runs SET
        run_stage = p_next_stage,
        snapshot_path = COALESCE(p_details->>'snapshot_path', snapshot_path),
        snapshot_sha256 = COALESCE(p_details->>'snapshot_sha256', snapshot_sha256),
        snapshot_metadata_path = COALESCE(p_details->>'snapshot_metadata_path', snapshot_metadata_path),
        snapshot_metadata_sha256 = COALESCE(
            p_details->>'snapshot_metadata_sha256', snapshot_metadata_sha256
        ),
        split_manifest_path = COALESCE(p_details->>'split_manifest_path', split_manifest_path),
        split_manifest_sha256 = COALESCE(
            p_details->>'split_manifest_sha256', split_manifest_sha256
        ),
        total_rows = COALESCE((p_details->>'total_rows')::integer, total_rows),
        training_rows = COALESCE((p_details->>'training_rows')::integer, training_rows),
        evaluation_rows = COALESCE((p_details->>'evaluation_rows')::integer, evaluation_rows),
        state_revision = state_revision + 1
    WHERE training_run_id = p_training_run_id
    RETURNING state_revision INTO v_revision;
    RETURN v_revision;
END;
$$;

CREATE FUNCTION ml_registry.fail_run(
    p_training_run_id uuid,
    p_expected_revision bigint,
    p_error_summary text
) RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ml_registry
AS $$
DECLARE v_revision bigint;
BEGIN
    UPDATE ml_registry.training_runs SET
        run_status = 'FAILED',
        completed_at = clock_timestamp(),
        error_summary = left(trim(p_error_summary), 1000),
        state_revision = state_revision + 1
    WHERE training_run_id = p_training_run_id
      AND state_revision = p_expected_revision
      AND run_status = 'RUNNING'
    RETURNING state_revision INTO v_revision;
    IF NOT FOUND THEN RAISE EXCEPTION 'training run revision conflict'; END IF;
    RETURN v_revision;
END;
$$;

CREATE FUNCTION ml_registry.record_evaluation(
    p_training_run_id uuid,
    p_expected_revision bigint,
    p_metrics_path ml_registry.root_relative_path,
    p_metrics_sha256 char(64),
    p_mae_text text,
    p_rmse_text text,
    p_r2_text text,
    p_mape_text text,
    p_baseline_mae_text text,
    p_baseline_rmse_text text,
    p_baseline_mape_text text
) RETURNS TABLE(state_revision bigint, acceptance_passed boolean)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ml_registry
AS $$
DECLARE
    v_mae numeric(30,12) := p_mae_text::numeric(30,12);
    v_rmse numeric(30,12) := p_rmse_text::numeric(30,12);
    v_r2 numeric(30,12) := p_r2_text::numeric(30,12);
    v_mape numeric(30,12) := p_mape_text::numeric(30,12);
    v_baseline_mae numeric(30,12) := p_baseline_mae_text::numeric(30,12);
    v_baseline_rmse numeric(30,12) := p_baseline_rmse_text::numeric(30,12);
    v_baseline_mape numeric(30,12) := p_baseline_mape_text::numeric(30,12);
    v_passed boolean;
BEGIN
    IF p_mae_text !~ '^-?[0-9]+\.[0-9]{12}$'
        OR p_rmse_text !~ '^-?[0-9]+\.[0-9]{12}$'
        OR p_r2_text !~ '^-?[0-9]+\.[0-9]{12}$'
        OR p_mape_text !~ '^-?[0-9]+\.[0-9]{12}$'
        OR p_baseline_mae_text !~ '^-?[0-9]+\.[0-9]{12}$'
        OR p_baseline_rmse_text !~ '^-?[0-9]+\.[0-9]{12}$'
        OR p_baseline_mape_text !~ '^-?[0-9]+\.[0-9]{12}$' THEN
        RAISE EXCEPTION 'metrics must use exactly 12 fractional digits';
    END IF;
    PERFORM 1 FROM ml_registry.training_runs r
     WHERE r.training_run_id = p_training_run_id
       AND r.state_revision = p_expected_revision
       AND r.run_status = 'RUNNING'
       AND r.run_stage = 'EVALUATING'
     FOR UPDATE OF r;
    IF NOT FOUND THEN RAISE EXCEPTION 'training run revision conflict'; END IF;

    v_passed := v_mae <= 15000 AND v_rmse <= 20000 AND v_r2 >= 0.9000
        AND v_mape <= 7.5000 AND v_mae < v_baseline_mae
        AND v_rmse < v_baseline_rmse AND v_mape < v_baseline_mape;

    INSERT INTO ml_registry.training_run_metrics (
        training_run_id, metrics_path, metrics_sha256, mae, rmse, r2, mape,
        baseline_mae, baseline_rmse, baseline_mape,
        acceptance_policy_version, acceptance_passed
    ) VALUES (
        p_training_run_id, p_metrics_path, p_metrics_sha256, v_mae, v_rmse, v_r2, v_mape,
        v_baseline_mae, v_baseline_rmse, v_baseline_mape, 'housing-linear-v1', v_passed
    );

    UPDATE ml_registry.training_runs SET
        run_stage = CASE WHEN v_passed THEN 'REGISTERING' ELSE 'COMPLETE' END,
        run_status = CASE WHEN v_passed THEN 'RUNNING' ELSE 'REJECTED' END,
        completed_at = CASE WHEN v_passed THEN NULL ELSE clock_timestamp() END,
        state_revision = ml_registry.training_runs.state_revision + 1
    WHERE training_run_id = p_training_run_id
    RETURNING ml_registry.training_runs.state_revision INTO state_revision;
    acceptance_passed := v_passed;
    RETURN NEXT;
END;
$$;

CREATE FUNCTION ml_registry.register_candidate(
    p_training_run_id uuid,
    p_expected_revision bigint,
    p_model_version varchar,
    p_model_type varchar,
    p_artifact_path ml_registry.root_relative_path,
    p_artifact_sha256 char(64),
    p_metadata_path ml_registry.root_relative_path,
    p_metadata_sha256 char(64)
) RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ml_registry
AS $$
DECLARE v_revision bigint;
BEGIN
    PERFORM 1
      FROM ml_registry.training_runs r
      JOIN ml_registry.training_run_metrics m USING (training_run_id)
     WHERE r.training_run_id = p_training_run_id
       AND r.state_revision = p_expected_revision
       AND r.run_status = 'RUNNING'
       AND r.run_stage = 'REGISTERING'
       AND m.acceptance_passed
     FOR UPDATE OF r;
    IF NOT FOUND THEN RAISE EXCEPTION 'run is not eligible for registration'; END IF;

    INSERT INTO ml_registry.model_versions (
        model_version, training_run_id, model_type, artifact_path, artifact_sha256,
        metadata_path, metadata_sha256, lifecycle_status
    ) VALUES (
        p_model_version, p_training_run_id, p_model_type, p_artifact_path,
        p_artifact_sha256, p_metadata_path, p_metadata_sha256, 'CANDIDATE'
    );
    UPDATE ml_registry.training_runs SET
        run_stage = 'COMPLETE', run_status = 'SUCCEEDED', completed_at = clock_timestamp(),
        state_revision = ml_registry.training_runs.state_revision + 1
    WHERE training_run_id = p_training_run_id
    RETURNING state_revision INTO v_revision;
    RETURN v_revision;
END;
$$;

CREATE FUNCTION ml_registry.promote_model(p_model_version varchar) RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ml_registry
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(82423601);
    PERFORM 1 FROM ml_registry.model_versions
     WHERE model_version = p_model_version AND lifecycle_status = 'CANDIDATE'
     FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'model is not a candidate'; END IF;

    UPDATE ml_registry.model_versions SET
        lifecycle_status = 'RETIRED', retired_at = clock_timestamp()
    WHERE lifecycle_status = 'ACTIVE';
    UPDATE ml_registry.model_versions SET
        lifecycle_status = 'ACTIVE', activated_at = clock_timestamp()
    WHERE model_version = p_model_version;
END;
$$;

REVOKE ALL ON ALL TABLES IN SCHEMA ml_registry FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA ml_registry FROM PUBLIC;
GRANT USAGE ON SCHEMA ml_registry TO training_runtime, model_runtime, estimator_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA ml_registry TO training_runtime, model_runtime;
GRANT EXECUTE ON FUNCTION ml_registry.start_run(uuid, uuid, varchar) TO training_runtime;
GRANT EXECUTE ON FUNCTION ml_registry.advance_run(uuid, bigint, varchar, jsonb) TO training_runtime;
GRANT EXECUTE ON FUNCTION ml_registry.fail_run(uuid, bigint, text) TO training_runtime;
GRANT EXECUTE ON FUNCTION ml_registry.record_evaluation(
    uuid, bigint, ml_registry.root_relative_path, char, text, text, text, text, text, text, text
) TO training_runtime;
GRANT EXECUTE ON FUNCTION ml_registry.register_candidate(
    uuid, bigint, varchar, varchar, ml_registry.root_relative_path, char,
    ml_registry.root_relative_path, char
) TO training_runtime;
GRANT EXECUTE ON FUNCTION ml_registry.promote_model(varchar) TO training_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA ml_registry REVOKE ALL ON TABLES FROM PUBLIC;

RESET ROLE;

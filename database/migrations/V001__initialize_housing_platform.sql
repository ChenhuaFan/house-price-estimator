REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE ROLE db_migration NOLOGIN;
GRANT db_migration TO CURRENT_USER;
GRANT CREATE ON DATABASE ${database_name} TO db_migration;

CREATE ROLE training_runtime LOGIN PASSWORD '${training_password}'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE model_runtime LOGIN PASSWORD '${model_password}'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE estimator_runtime LOGIN PASSWORD '${estimator_password}'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE market_runtime LOGIN PASSWORD '${market_password}'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;

GRANT CONNECT ON DATABASE ${database_name}
    TO training_runtime, model_runtime, estimator_runtime, market_runtime;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

SET ROLE db_migration;

CREATE SCHEMA business AUTHORIZATION db_migration;
CREATE SCHEMA catalog AUTHORIZATION db_migration;
CREATE SCHEMA ml_registry AUTHORIZATION db_migration;
CREATE SCHEMA estimator AUTHORIZATION db_migration;
CREATE SCHEMA market_ops AUTHORIZATION db_migration;

REVOKE ALL ON SCHEMA business, catalog, ml_registry, estimator, market_ops FROM PUBLIC;

-- Mutable source of truth for market queries and statistics.
CREATE TABLE business.housing_records (
    source_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    square_footage bigint NOT NULL CHECK (square_footage >= 1),
    bedrooms integer NOT NULL CHECK (bedrooms >= 0),
    bathrooms numeric(12,1) NOT NULL
        CHECK (bathrooms >= 0.5 AND mod(bathrooms * 2, 1) = 0),
    year_built integer NOT NULL CHECK (year_built BETWEEN 1 AND 9999),
    lot_size bigint NOT NULL CHECK (lot_size >= 0),
    distance_to_city_center numeric(15,3) NOT NULL
        CHECK (distance_to_city_center >= 0),
    school_rating numeric(15,3) NOT NULL,
    price numeric(18,2) NOT NULL CHECK (price > 0),
    row_revision bigint NOT NULL DEFAULT 1 CHECK (row_revision >= 1),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX business_housing_records_price_idx
    ON business.housing_records (price, source_id);
CREATE INDEX business_housing_records_square_footage_idx
    ON business.housing_records (square_footage, source_id);
CREATE INDEX business_housing_records_bedrooms_idx
    ON business.housing_records (bedrooms, source_id);
CREATE INDEX business_housing_records_bathrooms_idx
    ON business.housing_records (bathrooms, source_id);
CREATE INDEX business_housing_records_year_built_idx
    ON business.housing_records (year_built, source_id);
CREATE INDEX business_housing_records_lot_size_idx
    ON business.housing_records (lot_size, source_id);
CREATE INDEX business_housing_records_distance_idx
    ON business.housing_records (distance_to_city_center, source_id);
CREATE INDEX business_housing_records_school_rating_idx
    ON business.housing_records (school_rating, source_id);

-- Seed the live store first. Every immutable dataset, including the initial one,
-- is created from this business source of truth.
INSERT INTO business.housing_records (
    source_id, square_footage, bedrooms, bathrooms, year_built, lot_size,
    distance_to_city_center, school_rating, price
) OVERRIDING SYSTEM VALUE VALUES
(1, 1250, 2, 1.0, 1985, 5200, 3.200, 7.100, 185000.00),
(2, 1850, 3, 2.0, 1998, 7500, 5.600, 8.200, 265000.00),
(3, 1420, 3, 2.0, 1992, 6800, 2.800, 6.900, 210000.00),
(4, 2100, 4, 2.5, 2005, 9200, 7.300, 8.500, 345000.00),
(5, 1700, 3, 2.0, 2001, 7100, 4.100, 7.800, 275000.00),
(6, 980, 2, 1.0, 1978, 4500, 2.500, 6.500, 165000.00),
(7, 2400, 4, 3.0, 2010, 10500, 8.200, 9.000, 410000.00),
(8, 1600, 3, 1.5, 1995, 6700, 3.800, 7.200, 225000.00),
(9, 2200, 4, 2.5, 2008, 9800, 6.900, 8.700, 375000.00),
(10, 1350, 3, 1.0, 1987, 5800, 3.000, 7.000, 195000.00),
(11, 1950, 3, 2.0, 2003, 8100, 5.200, 8.100, 285000.00),
(12, 1100, 2, 1.0, 1982, 4800, 2.100, 6.800, 175000.00),
(13, 2350, 4, 3.0, 2012, 10200, 7.800, 9.100, 400000.00),
(14, 1550, 3, 1.5, 1994, 6500, 3.600, 7.300, 220000.00),
(15, 2050, 4, 2.5, 2006, 9000, 6.700, 8.600, 355000.00),
(16, 1300, 2, 1.0, 1986, 5500, 2.900, 7.200, 190000.00),
(17, 1800, 3, 2.0, 1999, 7300, 4.900, 8.000, 260000.00),
(18, 1150, 2, 1.0, 1980, 4600, 2.300, 6.700, 170000.00),
(19, 2300, 4, 3.0, 2011, 10000, 7.500, 9.000, 395000.00),
(20, 1500, 3, 1.5, 1993, 6400, 3.500, 7.400, 215000.00),
(21, 1650, 3, 2.0, 1997, 7000, 4.000, 7.700, 240000.00),
(22, 2150, 4, 2.5, 2007, 9500, 7.000, 8.800, 365000.00),
(23, 1200, 2, 1.0, 1984, 5000, 2.600, 6.900, 180000.00),
(24, 1900, 3, 2.0, 2002, 7800, 5.000, 8.300, 280000.00),
(25, 1400, 3, 1.5, 1990, 6000, 3.200, 7.100, 205000.00),
(26, 2250, 4, 3.0, 2009, 9900, 7.200, 8.900, 385000.00),
(27, 1750, 3, 2.0, 2000, 7200, 4.500, 7.900, 255000.00),
(28, 1050, 2, 1.0, 1979, 4400, 2.200, 6.600, 160000.00),
(29, 2050, 4, 2.5, 2004, 8800, 6.500, 8.400, 335000.00),
(30, 1450, 3, 1.5, 1991, 6200, 3.400, 7.500, 215000.00),
(31, 1330, 2, 1.0, 1988, 5600, 3.100, 7.000, 195000.00),
(32, 1870, 3, 2.0, 2000, 7600, 5.000, 8.100, 270000.00),
(33, 1380, 3, 1.5, 1992, 6100, 3.200, 7.300, 205000.00),
(34, 2120, 4, 2.5, 2006, 9300, 7.100, 8.600, 350000.00),
(35, 1680, 3, 2.0, 1998, 7050, 4.200, 7.600, 250000.00),
(36, 1010, 2, 1.0, 1980, 4550, 2.400, 6.700, 170000.00),
(37, 2380, 4, 3.0, 2011, 10300, 7.900, 9.000, 405000.00),
(38, 1580, 3, 1.5, 1996, 6650, 3.700, 7.200, 230000.00),
(39, 2180, 4, 2.5, 2007, 9700, 6.800, 8.700, 370000.00),
(40, 1270, 2, 1.0, 1985, 5300, 2.800, 7.000, 190000.00),
(41, 1930, 3, 2.0, 2002, 8000, 5.300, 8.200, 290000.00),
(42, 1120, 2, 1.0, 1983, 4850, 2.200, 6.900, 175000.00),
(43, 2320, 4, 3.0, 2010, 10100, 7.700, 9.000, 390000.00),
(44, 1520, 3, 1.5, 1995, 6350, 3.500, 7.400, 225000.00),
(45, 2070, 4, 2.5, 2005, 9100, 6.600, 8.500, 345000.00),
(46, 1290, 2, 1.0, 1986, 5400, 3.000, 7.100, 195000.00),
(47, 1820, 3, 2.0, 1999, 7400, 4.800, 8.000, 265000.00),
(48, 1130, 2, 1.0, 1981, 4700, 2.300, 6.800, 170000.00),
(49, 2280, 4, 3.0, 2009, 9950, 7.400, 8.900, 385000.00),
(50, 1480, 3, 1.5, 1992, 6300, 3.400, 7.500, 220000.00);

SELECT setval(
    pg_get_serial_sequence('business.housing_records', 'source_id'),
    (SELECT max(source_id) FROM business.housing_records),
    true
);

CREATE TABLE business.data_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    data_revision bigint NOT NULL CHECK (data_revision >= 1),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO business.data_state (singleton, data_revision) VALUES (true, 1);

CREATE FUNCTION business.touch_housing_record()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, business
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.row_revision := 1;
        NEW.created_at := clock_timestamp();
    ELSE
        NEW.source_id := OLD.source_id;
        NEW.created_at := OLD.created_at;
        NEW.row_revision := OLD.row_revision + 1;
    END IF;
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION business.bump_data_revision()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, business
AS $$
BEGIN
    UPDATE business.data_state
       SET data_revision = data_revision + 1,
           updated_at = clock_timestamp()
     WHERE singleton;
    RETURN NULL;
END;
$$;

CREATE TRIGGER business_housing_records_touch
BEFORE INSERT OR UPDATE ON business.housing_records
FOR EACH ROW EXECUTE FUNCTION business.touch_housing_record();

CREATE TRIGGER business_housing_records_revision
AFTER INSERT OR UPDATE OR DELETE ON business.housing_records
FOR EACH STATEMENT EXECUTE FUNCTION business.bump_data_revision();

-- Immutable training datasets. One dataset can be referenced by any number of
-- training runs and therefore by any number of registered model versions.
CREATE TABLE catalog.dataset_versions (
    dataset_version_id uuid PRIMARY KEY,
    version_name varchar(128) NOT NULL UNIQUE,
    source_kind varchar(32) NOT NULL DEFAULT 'BUSINESS_SNAPSHOT'
        CHECK (source_kind = 'BUSINESS_SNAPSHOT'),
    source_revision bigint NOT NULL CHECK (source_revision >= 1),
    source_filename text NOT NULL CHECK (length(source_filename) > 0),
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    row_count integer NOT NULL CHECK (row_count > 1),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    activated_at timestamptz,
    is_active boolean NOT NULL DEFAULT false,
    CONSTRAINT catalog_activation_time_required CHECK (
        (is_active AND activated_at IS NOT NULL) OR NOT is_active
    )
);

CREATE UNIQUE INDEX catalog_one_active_dataset_idx
    ON catalog.dataset_versions ((1)) WHERE is_active;

CREATE TABLE catalog.dataset_records (
    dataset_version_id uuid NOT NULL
        REFERENCES catalog.dataset_versions (dataset_version_id) ON DELETE RESTRICT,
    source_id bigint NOT NULL CHECK (source_id >= 1),
    square_footage bigint NOT NULL CHECK (square_footage >= 1),
    bedrooms integer NOT NULL CHECK (bedrooms >= 0),
    bathrooms numeric(12,1) NOT NULL CHECK (bathrooms >= 0.5 AND mod(bathrooms * 2, 1) = 0),
    year_built integer NOT NULL CHECK (year_built BETWEEN 1 AND 9999),
    lot_size bigint NOT NULL CHECK (lot_size >= 0),
    distance_to_city_center numeric(15,3) NOT NULL CHECK (distance_to_city_center >= 0),
    school_rating numeric(15,3) NOT NULL,
    price numeric(18,2) NOT NULL CHECK (price > 0),
    PRIMARY KEY (dataset_version_id, source_id)
);

CREATE INDEX dataset_records_price_idx
    ON catalog.dataset_records (dataset_version_id, price, source_id);
CREATE INDEX dataset_records_square_footage_idx
    ON catalog.dataset_records (dataset_version_id, square_footage, source_id);
CREATE INDEX dataset_records_bedrooms_idx
    ON catalog.dataset_records (dataset_version_id, bedrooms, source_id);
CREATE INDEX dataset_records_bathrooms_idx
    ON catalog.dataset_records (dataset_version_id, bathrooms, source_id);
CREATE INDEX dataset_records_year_built_idx
    ON catalog.dataset_records (dataset_version_id, year_built, source_id);
CREATE INDEX dataset_records_lot_size_idx
    ON catalog.dataset_records (dataset_version_id, lot_size, source_id);
CREATE INDEX dataset_records_distance_idx
    ON catalog.dataset_records (dataset_version_id, distance_to_city_center, source_id);
CREATE INDEX dataset_records_school_rating_idx
    ON catalog.dataset_records (dataset_version_id, school_rating, source_id);

CREATE FUNCTION catalog.snapshot_business_data(
    p_version_name text,
    p_activate boolean DEFAULT true,
    p_dataset_version_id uuid DEFAULT NULL
)
RETURNS uuid
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, catalog, business, public
AS $$
DECLARE
    v_dataset_version_id uuid := coalesce(p_dataset_version_id, public.gen_random_uuid());
    v_source_revision bigint;
    v_row_count integer;
    v_content_sha256 text;
BEGIN
    IF p_version_name IS NULL
       OR length(btrim(p_version_name)) < 1
       OR length(p_version_name) > 128 THEN
        RAISE EXCEPTION 'dataset version name must contain 1 to 128 characters'
            USING ERRCODE = '22023';
    END IF;

    -- SHARE prevents live INSERT/UPDATE/DELETE until this transaction commits,
    -- so revision, digest, metadata, and copied rows describe one exact state.
    LOCK TABLE business.housing_records IN SHARE MODE;

    SELECT data_revision INTO STRICT v_source_revision
      FROM business.data_state
     WHERE singleton;

    SELECT count(*) INTO v_row_count FROM business.housing_records;
    IF v_row_count < 2 THEN
        RAISE EXCEPTION 'at least two business records are required to create a training dataset'
            USING ERRCODE = '22023';
    END IF;

    SELECT encode(public.digest(
        convert_to(
            'source_id,square_footage,bedrooms,bathrooms,year_built,lot_size,distance_to_city_center,school_rating,price' || E'\n' ||
            string_agg(
                source_id::text || ',' || square_footage::text || ',' || bedrooms::text || ',' ||
                to_char(bathrooms, 'FM999999999990.0') || ',' || year_built::text || ',' || lot_size::text || ',' ||
                to_char(distance_to_city_center, 'FM999999999990.000') || ',' ||
                to_char(school_rating, 'FM999999999990.000') || ',' ||
                to_char(price, 'FM9999999999999990.00'),
                E'\n' ORDER BY source_id
            ) || E'\n',
            'UTF8'
        ),
        'sha256'
    ), 'hex') INTO v_content_sha256
      FROM business.housing_records;

    IF p_activate THEN
        UPDATE catalog.dataset_versions SET is_active = false WHERE is_active;
    END IF;

    INSERT INTO catalog.dataset_versions (
        dataset_version_id, version_name, source_revision, source_filename,
        source_sha256, content_sha256, row_count, activated_at, is_active
    ) VALUES (
        v_dataset_version_id, btrim(p_version_name), v_source_revision,
        'business.housing_records', v_content_sha256, v_content_sha256, v_row_count,
        CASE WHEN p_activate THEN clock_timestamp() END, p_activate
    );

    INSERT INTO catalog.dataset_records (
        dataset_version_id, source_id, square_footage, bedrooms, bathrooms,
        year_built, lot_size, distance_to_city_center, school_rating, price
    )
    SELECT v_dataset_version_id, source_id, square_footage, bedrooms, bathrooms,
           year_built, lot_size, distance_to_city_center, school_rating, price
      FROM business.housing_records
     ORDER BY source_id;

    RETURN v_dataset_version_id;
END;
$$;

SELECT catalog.snapshot_business_data(
    'house-price-v1',
    true,
    '00000000-0000-4000-8000-000000000001'
);

REVOKE ALL ON FUNCTION business.touch_housing_record() FROM PUBLIC;
REVOKE ALL ON FUNCTION business.bump_data_revision() FROM PUBLIC;
REVOKE ALL ON FUNCTION catalog.snapshot_business_data(text, boolean, uuid) FROM PUBLIC;

GRANT USAGE ON SCHEMA business TO market_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON business.housing_records TO market_runtime;
GRANT SELECT ON business.data_state TO market_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA business TO market_runtime;

GRANT USAGE ON SCHEMA catalog TO training_runtime;
GRANT SELECT ON catalog.dataset_versions, catalog.dataset_records TO training_runtime;
GRANT EXECUTE ON FUNCTION catalog.snapshot_business_data(text, boolean, uuid)
    TO training_runtime;

ALTER DEFAULT PRIVILEGES IN SCHEMA business REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA business REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES IN SCHEMA catalog REVOKE ALL ON TABLES FROM PUBLIC;

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

CREATE TABLE estimator.clients (
    client_id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT clients_last_seen_after_creation CHECK (last_seen_at >= created_at)
);

CREATE TABLE estimator.estimates (
    estimate_id uuid PRIMARY KEY,
    client_id uuid NOT NULL REFERENCES estimator.clients (client_id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    square_footage bigint NOT NULL CHECK (square_footage >= 1),
    bedrooms integer NOT NULL CHECK (bedrooms >= 0),
    bathrooms numeric(12,1) NOT NULL CHECK (bathrooms >= 0.5 AND mod(bathrooms * 2, 1) = 0),
    year_built integer NOT NULL CHECK (year_built BETWEEN 1 AND 9999),
    lot_size bigint NOT NULL CHECK (lot_size >= 0),
    distance_to_city_center numeric(15,3) NOT NULL CHECK (distance_to_city_center >= 0),
    school_rating numeric(15,3) NOT NULL,
    predicted_price numeric(18,2) NOT NULL CHECK (predicted_price >= 0),
    currency_code char(3) NOT NULL DEFAULT 'USD' CHECK (currency_code = 'USD'),
    model_version varchar(128) NOT NULL
        REFERENCES ml_registry.model_versions (model_version) ON DELETE RESTRICT,
    correlation_id uuid NOT NULL,
    CONSTRAINT estimates_client_request_unique UNIQUE (client_id, request_id)
);

CREATE INDEX estimates_history_cursor_idx
    ON estimator.estimates (client_id, created_at DESC, estimate_id DESC);

GRANT USAGE ON SCHEMA estimator TO estimator_runtime;
GRANT SELECT, INSERT ON estimator.clients TO estimator_runtime;
GRANT UPDATE (last_seen_at) ON estimator.clients TO estimator_runtime;
GRANT SELECT, INSERT, DELETE ON estimator.estimates TO estimator_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA estimator REVOKE ALL ON TABLES FROM PUBLIC;

CREATE TABLE market_ops.export_jobs (
    export_id uuid PRIMARY KEY,
    idempotency_key uuid NOT NULL UNIQUE,
    request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    request_payload jsonb NOT NULL,
    source_revision bigint NOT NULL CHECK (source_revision >= 1),
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
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
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
        OR status <> 'RUNNING'
    )
);

CREATE INDEX export_jobs_claim_idx
    ON market_ops.export_jobs (status, created_at)
    WHERE status = 'QUEUED';
CREATE INDEX export_jobs_expiry_idx
    ON market_ops.export_jobs (expires_at)
    WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED');

GRANT USAGE ON SCHEMA market_ops TO market_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON market_ops.export_jobs TO market_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA market_ops REVOKE ALL ON TABLES FROM PUBLIC;

RESET ROLE;

REVOKE CREATE ON DATABASE ${database_name} FROM db_migration;

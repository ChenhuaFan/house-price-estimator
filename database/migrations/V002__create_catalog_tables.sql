SET ROLE db_migration;

CREATE TABLE catalog.dataset_versions (
    dataset_version_id uuid PRIMARY KEY,
    version_name varchar(128) NOT NULL UNIQUE,
    source_filename text NOT NULL CHECK (length(source_filename) > 0),
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    content_sha256 char(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    row_count integer NOT NULL CHECK (row_count > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    activated_at timestamptz,
    is_active boolean NOT NULL DEFAULT false,
    CONSTRAINT catalog_activation_time_required CHECK (
        (is_active AND activated_at IS NOT NULL) OR NOT is_active
    )
);

CREATE UNIQUE INDEX catalog_one_active_dataset_idx
    ON catalog.dataset_versions ((1)) WHERE is_active;

CREATE TABLE catalog.housing_records (
    dataset_version_id uuid NOT NULL
        REFERENCES catalog.dataset_versions (dataset_version_id) ON DELETE RESTRICT,
    source_id bigint NOT NULL CHECK (source_id >= 1),
    square_footage bigint NOT NULL CHECK (square_footage >= 1),
    bedrooms integer NOT NULL CHECK (bedrooms >= 0),
    bathrooms numeric(12,1) NOT NULL CHECK (bathrooms >= 0.5 AND mod(bathrooms * 2, 1) = 0),
    year_built integer NOT NULL CHECK (year_built >= 1),
    lot_size bigint NOT NULL CHECK (lot_size >= 0),
    distance_to_city_center numeric(15,3) NOT NULL CHECK (distance_to_city_center >= 0),
    school_rating numeric(15,3) NOT NULL,
    price numeric(18,2) NOT NULL CHECK (price > 0),
    PRIMARY KEY (dataset_version_id, source_id)
);

CREATE INDEX housing_records_price_idx
    ON catalog.housing_records (dataset_version_id, price, source_id);
CREATE INDEX housing_records_square_footage_idx
    ON catalog.housing_records (dataset_version_id, square_footage, source_id);
CREATE INDEX housing_records_bedrooms_idx
    ON catalog.housing_records (dataset_version_id, bedrooms, source_id);
CREATE INDEX housing_records_bathrooms_idx
    ON catalog.housing_records (dataset_version_id, bathrooms, source_id);
CREATE INDEX housing_records_year_built_idx
    ON catalog.housing_records (dataset_version_id, year_built, source_id);
CREATE INDEX housing_records_lot_size_idx
    ON catalog.housing_records (dataset_version_id, lot_size, source_id);
CREATE INDEX housing_records_distance_idx
    ON catalog.housing_records (dataset_version_id, distance_to_city_center, source_id);
CREATE INDEX housing_records_school_rating_idx
    ON catalog.housing_records (dataset_version_id, school_rating, source_id);

GRANT USAGE ON SCHEMA catalog TO training_runtime, market_runtime;
GRANT SELECT ON catalog.dataset_versions, catalog.housing_records
    TO training_runtime, market_runtime;

ALTER DEFAULT PRIVILEGES IN SCHEMA catalog REVOKE ALL ON TABLES FROM PUBLIC;

RESET ROLE;

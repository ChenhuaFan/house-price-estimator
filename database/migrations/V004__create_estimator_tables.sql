SET ROLE db_migration;

CREATE TABLE estimator.clients (
    client_id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT clients_last_seen_after_creation CHECK (last_seen_at >= created_at)
);

CREATE TABLE estimator.estimates (
    estimate_id uuid PRIMARY KEY,
    client_id uuid NOT NULL REFERENCES estimator.clients (client_id) ON DELETE CASCADE,
    request_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    square_footage bigint NOT NULL CHECK (square_footage >= 1),
    bedrooms integer NOT NULL CHECK (bedrooms >= 0),
    bathrooms numeric(12,1) NOT NULL CHECK (bathrooms >= 0.5 AND mod(bathrooms * 2, 1) = 0),
    year_built integer NOT NULL CHECK (year_built >= 1),
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

CREATE TABLE estimator.estimate_warnings (
    estimate_id uuid NOT NULL
        REFERENCES estimator.estimates (estimate_id) ON DELETE CASCADE,
    warning_order smallint NOT NULL CHECK (warning_order >= 1),
    field_name varchar(64) NOT NULL CHECK (field_name IN (
        'square_footage', 'bedrooms', 'bathrooms', 'year_built', 'lot_size',
        'distance_to_city_center', 'school_rating'
    )),
    submitted_value numeric(20,6) NOT NULL,
    advisory_min numeric(20,6),
    advisory_max numeric(20,6),
    reason text NOT NULL CHECK (length(reason) > 0),
    PRIMARY KEY (estimate_id, warning_order),
    CONSTRAINT estimate_warning_range_order CHECK (
        advisory_min IS NULL OR advisory_max IS NULL OR advisory_min <= advisory_max
    )
);

GRANT USAGE ON SCHEMA estimator TO estimator_runtime;
GRANT SELECT, INSERT ON estimator.clients TO estimator_runtime;
GRANT UPDATE (last_seen_at) ON estimator.clients TO estimator_runtime;
GRANT SELECT, INSERT, DELETE ON estimator.estimates TO estimator_runtime;
GRANT SELECT, INSERT ON estimator.estimate_warnings TO estimator_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA estimator REVOKE ALL ON TABLES FROM PUBLIC;

RESET ROLE;

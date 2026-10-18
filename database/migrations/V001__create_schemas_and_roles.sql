REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE ROLE db_migration NOLOGIN;
GRANT db_migration TO CURRENT_USER;
GRANT CREATE ON DATABASE ${database_name} TO db_migration;

CREATE ROLE training_runtime LOGIN PASSWORD '${training_password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE model_runtime LOGIN PASSWORD '${model_password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE estimator_runtime LOGIN PASSWORD '${estimator_password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE market_runtime LOGIN PASSWORD '${market_password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;

GRANT CONNECT ON DATABASE ${database_name}
    TO training_runtime, model_runtime, estimator_runtime, market_runtime;

SET ROLE db_migration;
CREATE SCHEMA catalog AUTHORIZATION db_migration;
CREATE SCHEMA ml_registry AUTHORIZATION db_migration;
CREATE SCHEMA estimator AUTHORIZATION db_migration;
RESET ROLE;

REVOKE CREATE ON DATABASE ${database_name} FROM db_migration;
REVOKE ALL ON SCHEMA catalog, ml_registry, estimator FROM PUBLIC;

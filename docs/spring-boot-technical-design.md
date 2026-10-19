# Spring Boot Market Service Technical Design

## 1. Purpose

`market-service` is one Java 21 / Spring Boot 3.4 application for the Property Market Analysis
experience. It exposes live property reads, aggregate statistics, chart data, model-backed what-if
estimates, and asynchronous CSV/PDF exports. It is intentionally one deployable service with a
strict internal layering model.

The service contract is generated from
[`openapi/market-service-v2.yaml`](openapi/market-service-v2.yaml). Swagger UI is available at
`/swagger-ui/index.html`; Compose publishes it to `127.0.0.1:8080` by default.

## 2. Data Boundary

Market business APIs read `business.housing_records`, the current mutable source of truth. They do
not accept, resolve, or return a training `dataset_version_id`. The immutable `catalog` schema is
owned by the training/model lineage flow and is intentionally inaccessible to `market_runtime`.

The live store exposes a singleton `business.data_state.data_revision`. A database statement-level
trigger increments it after each successful insert, update, or delete statement. This revision:

- appears in property/statistics/export responses;
- participates in query fingerprints and Caffeine keys;
- binds signed cursors to the business state that created them; and
- is recorded as `market_ops.export_jobs.source_revision`.

It is not an immutable dataset and cannot be used to retrieve old row state.

## 3. Packages and Dependency Direction

```text
com.hsbc.market
├── controller   generated-contract implementations and HTTP mapping
├── service      workflows, validation, transactions, cache/cursor policy
├── dao          repository boundary used by services
├── mapper       MyBatis interfaces split by query/export pool
├── model
│   ├── domain       normalized business types
│   ├── query        safe SQL parameter objects
│   ├── persistence  database records
│   └── v1           compatibility DTOs
├── export       CSV/PDF generation and local artifact storage
├── client       model-service HTTP client
├── error        structured exceptions and mappings
└── config       data sources, cache, health, scheduling, JSON
```

Allowed dependency direction:

```text
generated API -> Controller -> Service -> DAO -> Mapper -> XML SQL -> PostgreSQL
```

Controllers do not call Mappers, DAOs do not return generated HTTP models, and Mapper XML never
contains browser-provided identifiers. Architecture tests enforce the important package rules.

## 4. Database Access

Both Hikari pools use the restricted `market_runtime` login:

| Pool | Purpose | Typical budget |
| --- | --- | --- |
| `market-query` | interactive pages, statistics, export-job state | small pool, short timeout |
| `market-export` | streaming CSV/PDF data reads | independently bounded, longer timeout |

The separate pools prevent a long report from exhausting interactive connections. Application SQL
schema-qualifies every table. MyBatis binds values with parameters; sort fields, directions,
metrics, and group dimensions are closed enums mapped to reviewed SQL fragments.

The runtime role has:

- `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on `business.housing_records`;
- `SELECT` on `business.data_state`;
- identity-sequence use for new live records; and
- CRUD on `market_ops.export_jobs`.

It has no `catalog`, `ml_registry`, or `estimator` access.

## 5. Transactions and Consistency

Interactive property and statistics methods use:

```java
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 3)
```

The Service reads `data_revision` and all rows/aggregates inside that one database snapshot. This
prevents an individual response from mixing a revision with later rows. Multiple parallel HTTP
responses can still straddle a concurrent write, so the Next.js server loader compares revisions
and retries the complete page assembly when necessary.

Export creation uses a short write transaction. Worker data generation uses the export transaction
manager and read-only `REPEATABLE READ`; worker claim/completion/cancellation operations use short
write transactions and optimistic `state_revision` checks.

## 6. Property Queries and Cursors

`MarketQueryService`:

1. validates and normalizes filters, sort, and limit;
2. reads the current business revision;
3. verifies an optional signed cursor against that revision and normalized query;
4. asks `MarketReadDao` for at most `limit + 1` rows;
5. returns bounded records and an optional next cursor.

The supported filters are actual price and the seven canonical model fields. Ranges are inclusive.
SQL uses keyset predicates over the selected sort field plus ascending `source_id`; numeric offset
pagination is not supported.

The opaque cursor contains only bounded state required for traversal: API version, issued/expiry
time, `data_revision`, query fingerprint, sort, last sort value, and last `source_id`. It is signed
with the configured HMAC secret. A signature failure, query mismatch, sort mismatch, expiry, or
stale revision returns a structured client error before database paging SQL runs.

## 7. Statistics

`StatisticsService` validates a bounded list of metrics and optional group definition, then invokes
reviewed MyBatis statements. PostgreSQL performs `COUNT`, `AVG`, `MIN`, `MAX`, `SUM`, sample standard
deviation, fixed-width buckets, and allowed groupings. Monetary values remain `BigDecimal`; Java
does not aggregate the full table in memory.

Each response returns `data_revision` and the normalized query fingerprint. The same filters used
for table rows are reusable for overview, chart, CSV, and PDF requests.

## 8. What-if Integration

`POST /api/v2/market/what-if` validates exactly one property input and calls the private
`model-service /api/v1/predict` endpoint with the correlation ID. The response carries predicted
price, active model version, and advisory warnings.

The call is independent of live business queries: it neither selects market rows nor writes a
record. A model timeout, unavailable dependency, or `MODEL_OUTPUT_OUT_OF_DOMAIN` error is mapped to
a bounded structured response and does not affect property/statistics readiness.

## 9. Cache Design

Spring Cache is backed by bounded Caffeine caches with a default five-minute TTL. Cached values are
reconstructible property pages and aggregate/chart DTOs. Keys include:

- API major version;
- live `data_revision`;
- normalized filters;
- sort and pagination position or statistics definition; and
- active model version only for model-dependent results.

After a business mutation the revision changes, so old entries cannot be selected by new requests.
They age out normally; global cache clearing is unnecessary. Database state, history, export files,
and model lifecycle state are never authoritative in cache.

## 10. Export Control Plane

`market_ops.export_jobs` stores:

- UUID export and idempotency keys;
- canonical request JSON and request SHA-256;
- `source_revision` and query fingerprint;
- CSV/PDF format and lifecycle status;
- worker lease owner/expiry and optimistic state revision;
- cancellation flag;
- bounded result counts and failure summary; and
- root-relative artifact metadata, size, media type, SHA-256, and expiry.

Creation is idempotent. The same key and digest returns the original job; a different digest returns
`409`. Workers claim the oldest queued job with `FOR UPDATE SKIP LOCKED`. Before generating content,
the worker compares current `data_revision` to the recorded `source_revision`; a mismatch fails the
job so an export cannot silently describe newer rows under an older revision.

CSV generation streams rows in the requested sort. PDF generation includes filters, revision,
statistics, charts, optional what-if data, and a bounded detail table. Both write a unique sibling
temporary file, flush it, atomically move it, calculate its digest, then perform compare-and-set
completion. Old workers cannot overwrite a recovered/cancelled job.

Cancellation is idempotent. Lease recovery requeues expired running work unless cancellation was
requested. Cleanup removes content before transitioning terminal jobs to `EXPIRED`. The content
endpoint never accepts a path supplied by the client.

## 11. Errors and Correlation

Every inbound request accepts an optional UUID `X-Correlation-ID`; invalid values return
`400 INVALID_CORRELATION_ID`, and absent values receive a generated UUIDv4. The value is returned in
the response and propagated to model-service.

Errors use the OpenAPI envelope with a stable code, safe message, correlation ID, and bounded field
details. Database credentials, SQL, stack traces, absolute paths, decoded cursors, full inputs, and
downstream response bodies are never returned.

## 12. Health and Observability

Liveness reports only process health. Readiness verifies database connectivity and required
business/export privileges. A transient model-service outage does not make property/statistics
endpoints unready, although what-if calls fail cleanly.

Micrometer/logging covers request rate, latency, errors, database query duration, model-call
duration, cache hits/misses, export transitions/failures, and available export storage. Structured
fields include service, operation, correlation ID, outcome, latency, `data_revision`, query
fingerprint, export ID, and model version when relevant.

## 13. Configuration and Local Deployment

Important environment settings include database URL/user/password, model-service URL, portal
origin, cursor HMAC secret, export root, pool sizes/timeouts, export limits, worker interval, lease
duration, retention, and cache limits. Secrets come from ignored environment files or Compose
secrets.

The container is non-root, read-only except for `/tmp` and the export volume, and runs with
`no-new-privileges`. PostgreSQL and private application traffic remain on the Compose network. Host
port 8080 binds to `127.0.0.1` by default for local Swagger access.

## 14. Verification

The Maven quality gate compiles from the reviewed OpenAPI contract and runs:

- unit tests for normalization, fingerprints, signed cursors, cache keys, and service workflows;
- mapper boundary and package architecture tests;
- disposable-PostgreSQL integration tests for empty migration, permissions, live queries,
  aggregates, revisions, and frozen-dataset immutability;
- what-if client/error-isolation tests;
- export idempotency, generation, cancellation, lease recovery, download, and expiry tests; and
- JaCoCo coverage enforcement.

Contract and frontend tests additionally prove that market business operations contain no
`dataset_version_id` parameter and return `data_revision` where consistency metadata is needed.

# Java Property Market API Design

## 1. Scope

`market-service` is the Spring Boot backend for Property Market Analysis. It owns live property
queries, statistics, chart projections, signed cursor pagination, model-backed what-if estimates,
and asynchronous CSV/PDF exports. The reviewed contract is
[`openapi/market-service-v2.yaml`](openapi/market-service-v2.yaml); generated Java interfaces are
build output and are not edited manually.

The API has two deliberately separate data concerns:

- business endpoints read current mutable `business.housing_records`;
- the model service may report its frozen training `dataset_version_id` as lineage.

A training dataset UUID is never a selector for market properties, statistics, or exports.

## 2. Runtime Boundary

The application uses one deployable Maven/Spring Boot service and this internal call chain:

```text
generated OpenAPI interface
        -> Controller
        -> Service (@Transactional)
        -> DAO (@Repository)
        -> MyBatis Mapper (@Mapper)
        -> Mapper XML
        -> PostgreSQL
```

Controllers handle HTTP mapping only. Services normalize and validate business requests, define
transaction boundaries, generate cache keys/cursors, and orchestrate exports or model calls. DAOs
are the only database-facing types used by Services. Mapper XML owns SQL.

`market_runtime` can read and manage live business rows and export jobs. It has no `catalog` schema
access, so a business request cannot accidentally switch to or inspect a training dataset.

## 3. Live Data Revision

`business.data_state` contains one server-owned monotonic `data_revision`. A database trigger bumps
it once per successful insert, update, or delete statement on the live table. It is consistency
metadata, not a historical dataset ID: revision 12 does not provide a way to query old revision 12
after the table changes.

Every interactive read runs in a read-only `REPEATABLE READ` transaction. The service reads the
revision and result rows from the same MVCC snapshot and returns:

```json
{
  "data_revision": 12,
  "query_fingerprint": "sha256:..."
}
```

The revision participates in query fingerprints, cache keys, and signed cursors. A cursor from an
older revision is rejected after a live write. The Next.js server loader accepts a dashboard
assembly only when the properties response and all statistics responses share one revision.

## 4. Common Query Model

All filters are optional and combined with `AND`:

- `price`
- `square_footage`
- `bedrooms`
- `bathrooms`
- `year_built`
- `lot_size`
- `distance_to_city_center`
- `school_rating`

Ranges use inclusive `gte`/`lte` endpoints. Decimal values use canonical JSON strings where the
OpenAPI contract requires precision preservation. Unknown properties and operators are rejected.

A normalized selection contains only filters and sort:

```json
{
  "scope": {
    "filters": {
      "price": {"gte": "200000.00", "lte": "750000.00"},
      "bedrooms": {"gte": 3}
    }
  },
  "sort": [{"field": "price", "direction": "desc"}]
}
```

`source_id` is always the final ascending tie-breaker. The browser supplies no database table,
column, SQL fragment, dataset ID, or revision.

## 5. Property Listing

```http
GET /api/v2/market/properties?price_gte=200000.00&price_lte=750000.00&bedrooms_gte=3&sort=price:desc&limit=50
```

The endpoint supports a single allow-listed primary sort plus the implicit `source_id` tie-breaker.
`limit` is bounded; deep numeric offsets are unsupported. A response contains records, page
metadata, `data_revision`, and `query_fingerprint`.

Subsequent pages send only the opaque signed cursor. The cursor payload binds API version,
`data_revision`, normalized query fingerprint, sort definition, last sort value, and last
`source_id`. HMAC verification, expiry, and binding checks occur before SQL execution. Cursor
failure is a client error; the caller reloads the first page against current data.

Supported database indexes are defined directly on the live table with `source_id` as the trailing
key. PostgreSQL performs filtering, ordering, and keyset pagination; Java never loads the full table
to sort it.

## 6. Statistics and Charts

```http
POST /api/v2/market/statistics:query
Content-Type: application/json

{
  "scope": {"filters": {"bedrooms": {"gte": 3}}},
  "metrics": [
    {"id": "count", "method": "count"},
    {"id": "average_price", "method": "average", "field": "price"}
  ],
  "group_by": {"field": "bedrooms"},
  "group_order": "key_asc",
  "group_limit": 20
}
```

The service supports bounded allow-listed aggregates and grouping. SQL calculates aggregates using
PostgreSQL numeric semantics. Monetary values remain fixed-point decimals through PostgreSQL,
Java, JSON, CSV, and PDF. The response includes the live `data_revision`; it never includes or
requires a training dataset selector.

The same normalized filters feed overview metrics, grouped chart requests, table rows, and export
jobs. The frontend does not calculate whole-market statistics from a paginated page.

## 7. What-if Estimate

```http
POST /api/v2/market/what-if
```

The request is exactly one seven-feature property input. Java applies canonical validation and
calls `model-service /api/v1/predict`. A successful response preserves predicted price, model
version, and advisory warnings. Model metadata may include the model's training dataset lineage,
but that lineage does not affect which business rows appear on the market page.

If the raw model output is non-finite, negative, or cannot be represented as the canonical price
type, the model service returns `MODEL_OUTPUT_OUT_OF_DOMAIN`; Java maps it to a structured error.
Ordinary business-data endpoints continue to work if model inference is unavailable.

What-if does not insert estimate history and does not read or write business records.

## 8. Asynchronous Exports

The lifecycle is:

```text
POST /api/v2/market/exports
GET  /api/v2/market/exports/{export_id}
GET  /api/v2/market/exports/{export_id}/content
DELETE /api/v2/market/exports/{export_id}
```

Creation validates the normalized selection, reads the current live revision, and stores a durable
`QUEUED` job with the request digest, query fingerprint, and `source_revision`. `Idempotency-Key`
is a UUID; reuse with different request bytes returns `409`.

A bounded worker claims jobs using a lease and `FOR UPDATE SKIP LOCKED`. Before reading rows it
checks that the live revision still equals `source_revision`. If data changed while the job waited,
the worker fails the job so it cannot create a report that falsely claims the earlier revision; the
client recreates the export. Export reads use the separate export pool and a read-only
`REPEATABLE READ` transaction.

CSV streams the filtered, sorted live rows with canonical headers and decimals. PDF contains the
same filters, revision, counts, aggregates, charts, optional what-if data, and bounded detail rows.
Files are written to temporary siblings, flushed, atomically moved, fingerprinted, and made
downloadable only after a compare-and-set success transition. Cancellation, lease recovery, size
limits, and expiry are durable in `market_ops.export_jobs`.

## 9. Caching

Spring Cache with bounded Caffeine stores only reconstructible query results. Key material includes:

- API major version;
- live `data_revision`;
- normalized filters and sort;
- pagination position or aggregation definition; and
- model version only for model-dependent results.

A business write creates a new revision and therefore a different key. Old entries remain
unreachable and expire through the five-minute TTL or bounded-size eviction. Estimate history,
export files, registry lifecycle, and database mutations are never authoritative in cache.

## 10. Errors, Security, and Observability

All errors use the shared structured envelope and return `X-Correlation-ID`. Invalid correlation
IDs fail before business processing. Common statuses are `400` for syntax/cursor errors, `404` for
unknown exports, `409` for idempotency or stale-selection conflicts, `422` for well-formed invalid
requests, `429` for protection limits, `503` for unavailable dependencies, and `500` for unexpected
failures.

The service uses schema-qualified SQL, allow-listed identifiers, bound values, HMAC-signed cursors,
least-privilege credentials, bounded bodies/pages/groups/exports, relative storage keys, and
loopback host publication by default. Client input never becomes SQL identifiers or filesystem
paths.

Structured logs and metrics include operation, correlation ID, outcome, latency, data revision,
query fingerprint, cache result, export state, and model version where relevant. Secrets, cookies,
full request rows, decoded cursors, absolute file paths, and HTTP stack traces are excluded.

## 11. Verification

Required integration coverage includes:

- empty-database initialization and runtime grants;
- live insert/update/delete revision increments;
- frozen dataset immutability after later business writes;
- property, aggregate, and grouped results against independent SQL calculations;
- cursor tampering, expiry, query mismatch, and stale revision rejection;
- cache separation by revision;
- what-if success and model failure isolation;
- export idempotency, stale-source rejection, cancellation, lease recovery, download digest, and
  expiry; and
- proof that no market business endpoint accepts `dataset_version_id`.

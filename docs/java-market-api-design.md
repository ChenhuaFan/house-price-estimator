# Java Market Service API Design

## 1. Status and scope

This document defines the target HTTP API for the Java `market-service`. It refines the Property Market Analysis requirements into four independent capabilities:

1. paginated, filtered, and sorted access to housing records;
2. CSV and styled PDF exports based on the same record selection;
3. server-side aggregate statistics for tables, metric cards, and charts;
4. what-if price analysis through the trained model.

The source requirements describe these capabilities but do not prescribe concrete HTTP paths or schemas. The routes and payloads below are project API decisions. The persistence source of truth remains [`catalog.housing_records`](data-schema.md#72-cataloghousing_records), and the product requirements remain authoritative for user-visible behavior.

This is the implemented v2 contract. The compatibility `GET /api/v1/market/analysis` response still combines records, aggregates, and chart data for the current portal, while `/api/v2` provides the split contract and cursor pagination described here.

## 2. Design goals

- Keep record retrieval, statistics, exports, and model analysis independently evolvable.
- Apply one normalized filter definition consistently across records, statistics, charts, and exports.
- Keep filtering, sorting, aggregation, and export processing in the service and database; the browser must never calculate over the complete dataset.
- Remain responsive when a dataset version contains tens of millions of records.
- Never load a complete result set into Java heap memory.
- Preserve exact decimal values for prices and other decimal fields.
- Pin every response to one immutable dataset version.
- Expose only allow-listed fields and operations; clients cannot submit SQL expressions.

## 3. Source data and public fields

The API reads the immutable `catalog.housing_records` table through the read-only `market_runtime` role.

| API field | PostgreSQL column | Type | Filter | Sort | Aggregate |
| --- | --- | --- | --- | --- | --- |
| `source_id` | `source_id` | integer | range/exact | yes | `COUNT`, `MIN`, `MAX` |
| `square_footage` | `square_footage` | integer | range | yes | all supported methods |
| `bedrooms` | `bedrooms` | integer | range | yes | all supported methods |
| `bathrooms` | `bathrooms` | decimal | range | yes | all supported methods |
| `year_built` | `year_built` | integer | range | yes | all supported methods |
| `lot_size` | `lot_size` | integer | range | yes | all supported methods |
| `distance_to_city_center` | `distance_to_city_center` | decimal | range | yes | all supported methods |
| `school_rating` | `school_rating` | decimal | range | yes | all supported methods |
| `price` | `price` | decimal USD | range | yes | all supported methods |

`dataset_version_id` is part of the database primary key and the API scope, but it is not a user-selectable metric. If omitted, the service resolves the active version once at the start of the request. Every successful response returns the resolved version ID and its content SHA-256.

## 4. API conventions

### 4.1 Base path and media types

- Business API base path: `/api/v2/market`
- JSON media type: `application/json`
- CSV download: `text/csv; charset=UTF-8`
- PDF download: `application/pdf`
- OpenAPI version: 3.1
- JSON and query parameter names: `snake_case`

Health, readiness, metrics, and OpenAPI routes are operational endpoints and remain outside this business API design.

### 4.2 Dataset consistency

Every query is evaluated against exactly one immutable `dataset_version_id`. The service returns:

```json
{
  "dataset_version": {
    "id": "77777777-7777-7777-7777-777777777777",
    "content_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}
```

A client should reuse the returned ID for subsequent statistics and export requests. This prevents an active-version change from producing a table, chart, or export from different datasets.

### 4.3 Decimal representation

Request values may be JSON numbers or decimal query strings. Monetary values in JSON responses are fixed-point strings, for example `"325000.00"`, so JavaScript clients do not lose precision. CSV values use their canonical decimal representation.

### 4.4 Correlation and errors

Clients may send `X-Correlation-ID` as a UUID. The service returns it on every response. Errors use one envelope:

```json
{
  "error": {
    "code": "INVALID_FILTER",
    "message": "price.gte cannot exceed price.lte",
    "fields": [
      {
        "field": "filters.price",
        "message": "minimum cannot exceed maximum"
      }
    ]
  },
  "correlation_id": "0f768492-4448-40ce-a86a-c21ddfd7307f"
}
```

Common status codes are `400` for malformed syntax, `404` for an unknown dataset or export, `409` for a dataset/query mismatch, `422` for a well-formed but invalid request, `429` for service protection, `503` for an unavailable dependency, and `500` for an unexpected failure.

## 5. Shared query model

### 5.1 Market scope

A market scope identifies the immutable dataset and matching records:

```json
{
  "dataset_version_id": "77777777-7777-7777-7777-777777777777",
  "filters": {
    "price": { "gte": "200000.00", "lte": "750000.00" },
    "square_footage": { "gte": 1200, "lte": 3500 },
    "bedrooms": { "gte": 2, "lte": 5 },
    "bathrooms": { "gte": "1.5", "lte": "4.0" },
    "year_built": { "gte": 1990, "lte": 2026 },
    "lot_size": { "gte": 2000, "lte": 20000 },
    "distance_to_city_center": { "gte": "0.000", "lte": "25.000" },
    "school_rating": { "gte": "6.000", "lte": "10.000" }
  }
}
```

All filters are optional and combined with `AND`. An omitted `filters` object means all records in the resolved dataset version. Unknown fields and unknown operators are rejected rather than ignored. The only range operators in v2 are `gte` and `lte`; adding text search or arbitrary predicates is outside this contract.

### 5.2 Market selection

A market selection adds stable ordering to a scope:

```json
{
  "scope": {
    "dataset_version_id": "77777777-7777-7777-7777-777777777777",
    "filters": {
      "bedrooms": { "gte": 3 },
      "price": { "lte": "800000.00" }
    }
  },
  "sort": [
    { "field": "price", "direction": "desc" }
  ]
}
```

The public API accepts one client-selected sort field. The service always appends `source_id` in the same direction as a deterministic tie-breaker. If no sort is supplied, the effective order is `source_id ASC`.

Every response includes a `query_fingerprint`, calculated from the dataset content hash plus normalized filters and sort. It is an observability and consistency value, not an authorization token and not a replacement for the query itself.

## 6. API surface

| Capability | Method and path | Execution model |
| --- | --- | --- |
| Housing table | `GET /api/v2/market/properties` | synchronous |
| Aggregate statistics | `POST /api/v2/market/statistics/query` | synchronous, bounded |
| Create export | `POST /api/v2/market/exports` | asynchronous |
| Export status | `GET /api/v2/market/exports/{export_id}` | synchronous |
| Export content | `GET /api/v2/market/exports/{export_id}/content` | streamed download |
| Cancel export | `DELETE /api/v2/market/exports/{export_id}` | asynchronous cancellation |
| Price what-if analysis | `POST /api/v2/market/what-if` | synchronous model call |

## 7. Housing table API

### 7.1 Request

```http
GET /api/v2/market/properties?dataset_version_id=77777777-7777-7777-7777-777777777777&price_gte=200000.00&price_lte=750000.00&bedrooms_gte=3&sort=price:desc&limit=50&cursor=eyJ2IjoyLC4uLn0
```

Supported query parameters are:

| Parameter | Meaning |
| --- | --- |
| `dataset_version_id` | optional immutable dataset UUID; defaults to the active version |
| `<field>_gte` | inclusive lower bound for an allow-listed field |
| `<field>_lte` | inclusive upper bound for an allow-listed field |
| `sort` | `<field>:asc` or `<field>:desc`; defaults to `source_id:asc` |
| `limit` | number of records, default `50`, minimum `1`, maximum `200` |
| `cursor` | opaque cursor returned by the previous response |

`page` and numeric `offset` are deliberately not supported. Deep `OFFSET` pagination becomes progressively slower and can produce unstable pages. The opaque cursor contains the dataset version, query fingerprint, last sort value, and last `source_id`; the service validates all of them before executing the next keyset query.

### 7.2 Response

```json
{
  "dataset_version": {
    "id": "77777777-7777-7777-7777-777777777777",
    "content_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  },
  "query_fingerprint": "sha256:4bf01d...",
  "items": [
    {
      "source_id": 1042,
      "square_footage": 2180,
      "bedrooms": 3,
      "bathrooms": "2.5",
      "year_built": 2018,
      "lot_size": 6200,
      "distance_to_city_center": "7.250",
      "school_rating": "8.400",
      "price": "625000.00"
    }
  ],
  "page": {
    "limit": 50,
    "has_more": true,
    "next_cursor": "eyJ2IjoyLC4uLn0"
  }
}
```

The table response does not run an exact `COUNT(*)` on every page. Clients that need a matching count request `COUNT` together with other metrics through the statistics API. A UI can retain previous cursors to support a Back action; random jumps to an arbitrary deep page are intentionally unsupported.

### 7.3 Database execution

- The implementation follows `Controller -> Service -> DAO (@Repository) -> MyBatis Mapper (@Mapper) -> mapper XML -> PostgreSQL`.
- Services never call Mapper interfaces directly. DAO classes accept normalized query models and convert persistence records into Service models.
- Mapper interfaces contain method signatures only; SQL, dynamic `<if>/<choose>` fragments, and result mappings live under `src/main/resources/mapper/`.
- Use prepared, allow-listed SQL templates; never interpolate a client field directly into SQL.
- Apply `dataset_version_id` in every query.
- Use keyset predicates over `(dataset_version_id, selected_sort_field, source_id)`.
- Fetch `limit + 1` rows to determine `has_more`, then return at most `limit`.
- Select only public API columns.
- Set a bounded statement timeout and cancel the database statement when the client disconnects.
- Return records as a stream from JDBC to the response mapper; do not first materialize an unbounded collection.

The indexes defined in [`data-schema.md`](data-schema.md#73-catalog-ddl) cover each supported primary sort with `source_id` as a tie-breaker. PostgreSQL can combine appropriate indexes for multiple filters; production query plans must be verified against a representative large dataset.

## 8. Aggregate statistics API

### 8.1 Why this is separate

Statistics are calculated by PostgreSQL over the complete matching scope. They are not derived from the current table page and are never computed in the browser. One request may ask for several metrics so PostgreSQL can calculate them in one scan instead of receiving one request per card or chart.

### 8.2 Supported methods

| Method | Result | Valid field |
| --- | --- | --- |
| `COUNT` | number of matching rows or non-null values | `*` or an allow-listed field |
| `SUM` | arithmetic total | any numeric public field except `source_id` |
| `AVG` | arithmetic mean | any numeric public field except `source_id` |
| `MIN` | minimum value | any public field |
| `MAX` | maximum value | any public field |
| `STDDEV_POP` | population standard deviation | any numeric public field except `source_id` |

`STDDEV_POP` is chosen instead of an ambiguous `STDDEV` alias so the statistical definition is stable. Empty-scope behavior is: `COUNT` returns `0`; all other methods return `null`.

### 8.3 Ungrouped request

```http
POST /api/v2/market/statistics/query
Content-Type: application/json
```

```json
{
  "scope": {
    "dataset_version_id": "77777777-7777-7777-7777-777777777777",
    "filters": {
      "year_built": { "gte": 2000 },
      "school_rating": { "gte": "7.000" }
    }
  },
  "metrics": [
    { "id": "property_count", "method": "COUNT", "field": "*" },
    { "id": "average_price", "method": "AVG", "field": "price" },
    { "id": "total_price", "method": "SUM", "field": "price" },
    { "id": "minimum_price", "method": "MIN", "field": "price" },
    { "id": "maximum_price", "method": "MAX", "field": "price" },
    { "id": "price_stddev", "method": "STDDEV_POP", "field": "price" }
  ]
}
```

```json
{
  "dataset_version": {
    "id": "77777777-7777-7777-7777-777777777777",
    "content_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  },
  "query_fingerprint": "sha256:695cdc...",
  "groups": [
    {
      "key": {},
      "values": {
        "property_count": 24150832,
        "average_price": "482731.38",
        "total_price": "11657972003884.16",
        "minimum_price": "45000.00",
        "maximum_price": "9200000.00",
        "price_stddev": "218530.42"
      }
    }
  ]
}
```

Metric IDs must be unique within the request and become keys in `values`. A request may contain between 1 and 20 metrics.

### 8.4 Grouped statistics for charts and tables

One optional `group_by` definition supports chart series and summary tables:

```json
{
  "scope": {
    "filters": {
      "price": { "lte": "1000000.00" }
    }
  },
  "metrics": [
    { "id": "count", "method": "COUNT", "field": "*" },
    { "id": "average_price", "method": "AVG", "field": "price" }
  ],
  "group_by": {
    "field": "square_footage",
    "bucket": {
      "method": "FIXED_WIDTH",
      "width": "500"
    }
  },
  "order": { "by": "group_key", "direction": "asc" },
  "limit": 100
}
```

Rules:

- Low-cardinality fields such as `bedrooms` and `bathrooms` may be grouped by exact value.
- High-cardinality numeric fields require `FIXED_WIDTH` buckets. `source_id` cannot be grouped.
- Grouped responses contain at most 200 groups. The request must narrow the scope or increase bucket width if the result would exceed the limit.
- `order.by` may be `group_key` or one requested metric ID.
- Grouping, bucketing, aggregation, ordering, and limiting execute in one SQL statement.

The response uses the same `groups` shape. A bucket key includes explicit bounds so the frontend does not need to reconstruct them:

```json
{
  "key": {
    "field": "square_footage",
    "gte": "1500",
    "lt": "2000",
    "label": "1500-1999"
  },
  "values": {
    "count": 580124,
    "average_price": "412521.79"
  }
}
```

### 8.5 Aggregation performance

- Compile all requested metrics into one allow-listed SQL projection.
- Cache successful bounded results by dataset content hash, normalized scope, metrics, grouping, and ordering.
- Use a short TTL and bounded Caffeine cache; cache contents are reconstructible and never authoritative.
- Add materialized summary tables only for demonstrated hot dimensions and dataset versions. They are an optimization, not a second source of truth.
- Keep exact and approximate results as separate future operations. V2 returns exact results and must not label estimates as exact values.
- Record query latency, rows scanned, cache outcome, timeouts, and rejected high-cardinality groupings in service metrics.

## 9. Data export API

### 9.1 Export semantics

An export uses the same market selection as the table but excludes table pagination. CSV contains all matching records in the effective sort order. PDF is a styled analytical report based on the same scope and includes:

- report title and generation time in UTC;
- resolved dataset version and content hash;
- active filters and effective sort;
- exact matching count and requested summary statistics;
- configured charts;
- optional what-if result;
- a bounded detail table with repeating headers and page numbers.

CSV is the complete machine-readable export. A PDF containing millions of detail rows is not usable, so PDF details are explicitly bounded. If matching rows exceed `detail_limit`, the report states `Showing first N of M records`; it never silently claims to be complete. The full result remains available as CSV.

### 9.2 Create an export

```http
POST /api/v2/market/exports
Content-Type: application/json
Idempotency-Key: 6a08356c-df98-4cf5-a5a0-aadb406bb7c0
```

```json
{
  "format": "PDF",
  "selection": {
    "scope": {
      "dataset_version_id": "77777777-7777-7777-7777-777777777777",
      "filters": {
        "price": { "gte": "200000.00", "lte": "750000.00" },
        "bedrooms": { "gte": 3 }
      }
    },
    "sort": [
      { "field": "price", "direction": "desc" }
    ]
  },
  "pdf_options": {
    "template": "market-analysis-v1",
    "include_details": true,
    "detail_limit": 1000,
    "metrics": [
      { "id": "property_count", "method": "COUNT", "field": "*" },
      { "id": "average_price", "method": "AVG", "field": "price" },
      { "id": "minimum_price", "method": "MIN", "field": "price" },
      { "id": "maximum_price", "method": "MAX", "field": "price" },
      { "id": "price_stddev", "method": "STDDEV_POP", "field": "price" }
    ],
    "what_if": null
  }
}
```

`format` is `CSV` or `PDF`. `pdf_options` is forbidden for CSV. `detail_limit` defaults to `500` and has a configured hard maximum of `5000`.

The service returns `202 Accepted`:

```http
Location: /api/v2/market/exports/e8cad6bb-31c2-4c8a-a7ef-e30670560f4a
Retry-After: 2
```

```json
{
  "export_id": "e8cad6bb-31c2-4c8a-a7ef-e30670560f4a",
  "status": "QUEUED",
  "format": "PDF",
  "query_fingerprint": "sha256:4bf01d...",
  "created_at": "2026-10-18T12:00:00Z",
  "expires_at": "2026-10-19T12:00:00Z"
}
```

The idempotency key prevents a browser retry from creating duplicate work. Reuse with a different canonical request returns `409 IDEMPOTENCY_KEY_REUSED`.

### 9.3 Status and download

```http
GET /api/v2/market/exports/{export_id}
```

Status is one of `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, or `EXPIRED`. A successful status contains a download path, size, and SHA-256:

```json
{
  "export_id": "e8cad6bb-31c2-4c8a-a7ef-e30670560f4a",
  "status": "SUCCEEDED",
  "format": "PDF",
  "row_count": 125460,
  "detail_row_count": 1000,
  "details_truncated": true,
  "content_length": 483020,
  "content_sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
  "download_url": "/api/v2/market/exports/e8cad6bb-31c2-4c8a-a7ef-e30670560f4a/content",
  "expires_at": "2026-10-19T12:00:00Z"
}
```

```http
GET /api/v2/market/exports/{export_id}/content
```

The content endpoint streams the file and supplies `Content-Type`, `Content-Length`, `Content-Disposition`, `Digest`, and cache-control headers. It never reads the complete file into Java heap memory.

`DELETE /api/v2/market/exports/{export_id}` atomically marks queued or running work as cancelled, invalidates any active worker lease revision, and removes completed temporary content. An active worker observes the cancellation flag and removes any uncommitted artifact. Cancellation is idempotent.

### 9.4 Export implementation requirements

- Persist export job metadata so polling remains valid across a service restart.
- Generate into bounded temporary or object storage and expire content after 24 hours by default.
- Stream PostgreSQL rows using a configured JDBC fetch size.
- Prefer PostgreSQL `COPY TO STDOUT` or an equivalent streaming path for large CSV files.
- Render PDF incrementally from a versioned server-side template; do not render browser HTML or accept client templates.
- Enforce per-job row, byte, runtime, and concurrency limits.
- Limit concurrent exports separately from interactive database requests.
- Resolve one immutable dataset version before queueing so later execution cannot switch datasets.
- Do not place complete filter expressions, credentials, or file paths in logs.

### 9.5 Required architecture follow-up

Asynchronous exports use the following implemented persistence boundary:

- keep `catalog` read-only and never store export state beside housing records;
- add a narrowly scoped `market_ops.export_jobs` table, or an equivalent durable job store, for request fingerprints, state transitions, result metadata, expiry, and idempotency keys;
- grant the market runtime only the exact job-store operations it requires while retaining read-only access to `catalog`;
- store generated content in bounded shared temporary storage or object storage, not in PostgreSQL and not only on one process's local disk if multiple service replicas are possible;
- define cleanup and recovery behavior for jobs left in `QUEUED` or `RUNNING` after a process restart.

These changes require a reviewed update to `data-schema.md`, `architecture.md`, migrations, and deployment configuration. They are not silently implied changes to the existing database role.

## 10. What-if price analysis API

The what-if operation remains a small synchronous request. It validates the seven model fields, calls `model-service`, and returns the predicted price. It does not scan or update `catalog.housing_records`.

```http
POST /api/v2/market/what-if
Content-Type: application/json
```

```json
{
  "square_footage": 2180,
  "bedrooms": 3,
  "bathrooms": "2.5",
  "year_built": 2018,
  "lot_size": 6200,
  "distance_to_city_center": "7.250",
  "school_rating": "8.400"
}
```

```json
{
  "inputs": {
    "square_footage": 2180,
    "bedrooms": 3,
    "bathrooms": "2.5",
    "year_built": 2018,
    "lot_size": 6200,
    "distance_to_city_center": "7.250",
    "school_rating": "8.400"
  },
  "prediction": {
    "predicted_price": "611250.34",
    "currency": "USD",
    "model_version": "house-linear-v1",
    "warnings": []
  }
}
```

Model validation errors retain their stable downstream error code. A timeout or unavailable model returns `503 MODEL_SERVICE_UNAVAILABLE`; ordinary table and statistics queries remain available when the model service is down.

## 11. Scalability and protection requirements

### 11.1 Database access

- Use the existing composite indexes beginning with `dataset_version_id` and ending with `source_id` for keyset access.
- Add indexes only from observed query plans; do not create every possible filter combination.
- Use `EXPLAIN (ANALYZE, BUFFERS)` against representative high-volume data before accepting a query pattern.
- Use a read-only transaction and bounded PostgreSQL statement timeout for interactive requests.
- Use separate connection-pool and concurrency budgets for interactive queries and exports.
- Cancel work when the HTTP client disconnects or an export is cancelled.

### 11.2 API limits

| Limit | Initial target |
| --- | --- |
| Table page size | default `50`, maximum `200` |
| Metrics per statistics request | maximum `20` |
| Group rows | maximum `200` |
| PDF detail rows | default `500`, maximum `5000` |
| What-if inputs | exactly one property |
| Export retention | 24 hours |

Limits are configuration values with the listed values as the contract maxima unless a new API version changes them.

### 11.3 Caching

- Cache bounded statistics and hot first-page queries, not unbounded result sets or generated files.
- Include dataset content SHA-256, normalized scope, sort, aggregation definition, and API version in each cache key.
- Never serve a cached response for a different dataset version.
- Export files use their own expiry lifecycle and are not stored in Caffeine.

### 11.4 Compression and transport

- Enable HTTP compression for JSON responses when the client supports it.
- CSV may be delivered with content encoding negotiated by the client, but the downloaded media type remains CSV.
- PDF is already compressed and should not receive redundant transport compression unless measured beneficial.
- Apply proxy and service timeouts appropriate to interactive queries; export generation is decoupled through job polling.

## 12. V1 migration mapping

| Current v1 operation | V2 replacement | Migration note |
| --- | --- | --- |
| `GET /api/v1/market/analysis` | `GET /api/v2/market/properties` plus `POST /api/v2/market/statistics/query` | records and aggregates become independent; chart data uses grouped statistics |
| `GET /api/v1/market/exports/data.csv` | `POST /api/v2/market/exports` with `format=CSV` | query moves to a typed body and execution becomes asynchronous |
| `POST /api/v1/market/exports/report.pdf` | `POST /api/v2/market/exports` with `format=PDF` | styled PDF becomes a versioned template and bounded report job |
| `POST /api/v1/market/what-if` | `POST /api/v2/market/what-if` | behavior remains the same; JSON naming and error schemas are normalized |

The portal should migrate as one consumer release. V1 must remain available during a compatibility window if any other consumer exists. The reviewed v2 OpenAPI document must be committed before implementation is treated as complete.

## 13. Verification and acceptance

The implementation is complete only when automated tests demonstrate:

- table, statistics, CSV, and PDF use equivalent normalized filters and the same dataset version;
- keyset pagination has no duplicates or gaps for every supported sort field, including equal sort values;
- range validation, field allow-lists, and cursor/query mismatch handling reject invalid requests;
- all requested statistics match independent SQL fixtures, including empty scopes and `STDDEV_POP`;
- multiple statistics are produced with one database query;
- CSV preserves the complete filtered sort order without unbounded heap growth;
- PDF uses the versioned styled template, declares bounded details, and renders repeated headers and page numbers correctly;
- export retries with one idempotency key do not duplicate jobs;
- model-service failure affects what-if only, not records or statistics;
- load tests over a representative multi-million-row fixture meet agreed latency and memory budgets;
- the runtime OpenAPI 3.1 document matches the reviewed contract snapshot.

# Integration Test Plan

## 1. Purpose and authority

This plan verifies the boundaries between the model, training workspace, PostgreSQL, estimator backend, Java market backend, Next.js portal, and container runtime. The primary authority is [`Interview Tasks Fullstack.pdf`](../requirements/Interview%20Tasks%20Fullstack.pdf). The numbered `REQ-*` acceptance details come from [`requirements.md`](requirements.md); technical and delivery rules come from [`constraints.md`](constraints.md), [`architecture.md`](architecture.md), and [`data-schema.md`](data-schema.md). The PDF wins if any supplementary project decision conflicts with it.

End-user flows and usability are defined in [`e2e-test-plan.md`](e2e-test-plan.md). This plan concentrates on data integrity, service contracts, failure handling, model lineage, framework use, caching, security boundaries, and reproducible delivery.

## 2. Test principles

- Integration tests use production serializers, validation, migrations, schemas, and HTTP boundaries. Reimplementing business rules in the test is not an acceptable oracle.
- PostgreSQL tests use a disposable PostgreSQL 17 container and never a shared developer database.
- Happy-path cross-service tests use the real services. Contract-aware stubs may be used to force timeouts, malformed responses, invalid raw model outputs, and concurrency windows that are otherwise impractical to reproduce.
- Monetary expectations are calculated with `Decimal`, `BigDecimal`, or a decimal arithmetic library, never binary floating-point business arithmetic.
- Time, UUID generation, and model/artifact fixtures are controllable where deterministic assertions require them.
- Every test starts from a known database, dataset version, registry state, and artifact root. Tests may not depend on execution order.
- Generated files are written to a unique temporary directory and removed after the test, whether the test passes or fails.
- Tests assert externally meaningful outcomes and durable state, including the absence of partial writes after failure.

## 3. Frameworks and execution layers

| Scope | Required tools | Boundary under test |
| --- | --- | --- |
| Python services and training | pytest, FastAPI test client or HTTPX, mypy, Ruff, Testcontainers where needed | Validation, model loading/inference, training files, registry and HTTP contracts |
| Java market service | JUnit 5, Spring Boot Test, MockMvc/WebTestClient, Testcontainers, controllable cache clock | REST, JDBC, aggregation, caching, export generation, model-service client |
| Next.js portal | Vitest, React Testing Library, MSW for component-only branches, production Next.js server for integration | Route handlers, RSC initial load, cookie/header forwarding, error translation, client/server boundaries |
| Contracts | OpenAPI 3.1 validator plus generated or schema-driven consumer checks | Provider schemas, media types, error envelopes, compatibility |
| Containers and orchestration | Docker Compose v2, image inspection, health checks | Runtime versions, non-root images, networks, volumes, restart behaviour |
| Full browser journeys | Playwright and axe-core | Defined in [`e2e-test-plan.md`](e2e-test-plan.md) |

## 4. Shared fixtures and assertions

The suite maintains these reusable fixtures:

- an empty PostgreSQL 17 database to which all migrations are applied;
- the exact `House Price Dataset.csv` and `Test Data For Prediction.csv` bytes, including the optional UTF-8 byte-order mark;
- an accepted active model fixture with matching registry, metadata, metrics, and SHA-256 values;
- a rejected training-run fixture;
- malformed, missing, path-escaping, and hash-mismatched model artifacts;
- a model-service stub that can return success, advisory warnings, timeouts, invalid schemas, and out-of-domain raw results;
- two unrelated anonymous client cookies and deterministic UUIDv4 request/correlation IDs;
- a fixed UTC clock for history and export assertions; and
- dataset versions V1 and V2 plus model versions M1 and M2 for activation and cache-key tests.

Canonical validation cases include missing values, booleans, numeric strings, fractional integer inputs, `bathrooms` values not divisible by `0.5`, non-finite decimals, lower-bound violations, effective scale greater than three for distance/rating, representational overflow, advisory `school_rating` values, and hard-valid values outside observed training ranges.

## 5. Database and migration integration cases

| ID | Pri | Method and required assertions |
| --- | --- | --- |
| **INT-DB-001 - Empty-database migration and seed** | P0 | Apply migrations to an empty PostgreSQL 17 container. Assert the three schemas, expected objects, exactly one active dataset version, exactly 50 housing records in stable source-ID identity, recorded source/content hashes, and byte-for-byte canonical snapshot hash. Assert no row from `Test Data For Prediction.csv` is seeded. Re-run migration tooling and assert it is safely repeatable. |
| **INT-DB-002 - Runtime permissions and immutable boundaries** | P1 | Connect as migration, training, model, market, and estimator roles. Assert only documented reads/writes/functions succeed; catalog mutation by runtime roles, cross-schema estimate access, unsafe `public` creation, and arbitrary registry table mutation fail. Verify controlled security-definer functions have fixed search paths and no default public execute grant. |
| **INT-DB-003 - Database integrity backstop** | P1 | Attempt direct rows with duplicate source IDs, invalid activation cardinality, non-finite or out-of-range numerics, invalid bathroom steps, invalid hashes, broken lifecycle transitions, and orphaned records. Assert constraints reject them. Confirm API validation rejects excess scale before PostgreSQL could round it. |

## 6. Training and registry integration cases

| ID | Pri | Method and required assertions |
| --- | --- | --- |
| **INT-TRAIN-001 - Explicit immutable snapshot export** | P0 | Execute `training/scripts/export_dataset.py` independently with an explicit dataset UUID and the read-only training role. Assert all 50 valid labelled rows are written in `source_id` order; the snapshot contains source ID, seven canonical features, and price; metadata contains central version and canonical hashes; repeated export is byte-identical; another version is never silently substituted. |
| **INT-TRAIN-002 - Deterministic split and metric calculation** | P0 | Run training/evaluation twice from the same snapshot. Assert shuffled 80/20 membership and row counts are identical with seed 42, no source ID appears in both sets, `source_id` is excluded from model inputs, model features have canonical order, and metrics are computed only from held-out predictions. Independently recompute MAE, RMSE, R-squared, and percentage-point MAPE and compare the canonical 12-decimal values and display precision. |
| **INT-TRAIN-003 - Acceptance policy and rejected-run persistence** | P0 | Exercise a passing fixture, a mean-baseline failure, and each threshold boundary just below, exactly at, and just above its limit. Assert publication eligibility requires improvement over the baseline on MAE/RMSE/MAPE and all four thresholds. Every completed evaluation, including rejection, persists metrics, baseline metrics, lineage, fingerprints, Git commit, and acceptance result; a rejected run publishes no model candidate. |
| **INT-TRAIN-004 - Full-snapshot artifact and metadata publication** | P0 | After an accepted evaluation, assert the production `LinearRegression` model is refitted on all valid snapshot rows, not only the 80% partition. Verify version-matched `split-manifest.json` and `metrics.json` under the run path, then atomic publication of `model.joblib` and `model-metadata.json` under the model-version artifact path. Metadata contains every required version, hash, dependency, feature, coefficient, intercept, timestamp, and Git field; registered paths are relative and remain within configured roots. |
| **INT-TRAIN-005 - Failure, reconciliation, candidate, and activation lifecycle** | P1 | Force export, validation, evaluation, filesystem, registry, and activation failures. Assert non-zero exits, durable `FAILED` state, no partial artifact becoming visible, and no active-model replacement. During a registry outage, assert the local manifest remains pending and publication is blocked until reconciliation records the failure. Register an accepted candidate, race two activations, and assert transactional single-active semantics; failed activation preserves both the previous active model and accepted candidate. |
| **INT-TRAIN-006 - Independent CLI workspace and documentation contract** | P1 | Execute each required script directly without portal or application backends. Assert only PostgreSQL and documented files are needed, commands expose actionable errors, and the training README documents access, fields/units, validation, snapshot/split, metrics, thresholds, artifacts, registry writes, and model-service consumption. |

## 7. Model-service integration cases

| ID | Pri | Method and required assertions |
| --- | --- | --- |
| **INT-MODEL-001 - Health, liveness, and readiness semantics** | P0 | With valid dependencies, assert `GET /health`, liveness, and readiness contracts. Repeat with no active model, unreadable/mismatched artifacts, and unavailable startup registry; readiness fails while liveness reflects a running process. After a successfully loaded immutable model, simulate a transient registry outage and assert behaviour follows the documented dependency policy without treating it as process death. |
| **INT-MODEL-002 - Single and batch prediction contract** | P0 | Send canonical valid inputs with shuffled JSON property order. Assert the service maps by name into canonical order and returns one exact fixed-two-decimal USD result. Send 1, 10, 1,000, 0, and 1,001 records; valid batch output count/order match input, and limits fail structurally without inference or partial output. |
| **INT-MODEL-003 - Canonical validation and advisory warnings** | P0 | Parameterise all validation fixtures across single and batch contracts. Assert type, finiteness, effective scale, discrete step, lower bound, and representational checks are identical. Advisory rating violations predict with field/value/range/reason warnings. Values outside observed dataset minima/maxima are accepted without a warning unless an explicit advisory rule applies. |
| **INT-MODEL-004 - Output conversion and atomic failure** | P0 | Inject raw results at half-cent boundaries, maximum representable amount, negative, NaN, infinities, and overflow. Assert immediate decimal-string conversion, one `ROUND_HALF_UP` operation, exact two-decimal transport, and structured `MODEL_OUTPUT_OUT_OF_DOMAIN` on invalid output. In a batch, one invalid index fails the whole request, identifies the index, and returns no partial prediction. |
| **INT-MODEL-005 - Registry/artifact/model-info consistency** | P0 | Start with a registered active model and verify path containment, SHA-256, metadata/registry/dataset/evaluation joins, model type, and dependency compatibility before load. Assert `model-info` returns the exact loaded version, canonical features, seven coefficients, intercept, and version-matched metrics. Missing, manually copied, mismatched, or path-escaping metadata prevents readiness rather than serving stale claims. |

## 8. Estimator-service integration cases

| ID | Pri | Method and required assertions |
| --- | --- | --- |
| **INT-EST-001 - Successful estimate transaction** | P0 | Submit seven canonical inputs through the estimator to a real or contract-verified model service. Assert downstream price and model version are persisted atomically with immutable estimate ID, UTC timestamp, canonical inputs, request ID, and anonymous client; the DTO exactly matches durable state. A downstream validation, timeout, malformed response, or out-of-domain error creates no estimate row. |
| **INT-EST-002 - Idempotency and concurrent retries** | P1 | Submit the same UUIDv4 request ID and canonical inputs repeatedly, concurrently, and after changing active model version. Assert one model call/row wins and all identical retries return its original result. Reuse the ID with different inputs and assert `409 IDEMPOTENCY_KEY_REUSED` with no inference. Reject malformed or non-v4 request IDs before business processing. |
| **INT-EST-003 - History pagination, deletion, and isolation** | P0 | Create more than 20 timestamp/UUID-tied rows for Client A and several for Client B. Assert bounded cursor pagination is newest-first, deterministic, and free of gaps/duplicates. Read/delete/clear as each client; assert operations are cookie-scoped and cannot accept an owner override. Single delete affects only that estimate; clear affects only the caller; restart preserves remaining rows. |

## 9. Market-service integration cases

| ID | Pri | Method and required assertions |
| --- | --- | --- |
| **INT-MARKET-001 - Default analysis, aggregates, and chart DTOs** | P0 | Query the active dataset with no filters. Assert matching count 50, bounded table page, stable source-ID tie-breaker, resolved dataset UUID/hash, and aggregates calculated with `BigDecimal` where monetary. Independently query the same rows and compare statistics and chart bucket totals; all chart/table/aggregate data must describe the same version and record set. |
| **INT-MARKET-002 - Filter, sort, page, and version consistency** | P0 | Parameterise inclusive lower/upper filters for price and all seven features, combined filters, empty results, invalid ranges, every supported sort/direction, and multiple pages. Assert normalized equivalent queries match, invalid queries fail structurally, stable tie-breaking prevents row drift, and an explicitly resolved immutable version is reused for subsequent export requests. |
| **INT-MARKET-003 - Bounded versioned cache** | P0 | Repeat equivalent normalized queries and assert Spring Cache/Caffeine hits; vary filters, sort/page, dataset hash, and relevant model version and assert distinct keys. Advance a controllable clock past five minutes and exceed the size bound. Assert expiry/eviction causes reconstruction, activation never returns an old version under a new key, restart loses cache only, and authoritative results remain unchanged. |
| **INT-MARKET-004 - What-if model integration and failure isolation** | P0 | Submit valid, advisory, and invalid records through the Java endpoint to the Task 1 contract. Assert canonical validation/serialization, exact price and version propagation, warnings, correlation ID, and structured downstream error mapping. Model failure must not break dataset-only analysis endpoints or create estimator history. |
| **INT-MARKET-005 - CSV export contract** | P0 | Request exports for unfiltered, filtered, sorted, empty, and multi-page result sets. Parse the bytes and assert UTF-8 CSV, media type/disposition, exact required header order, every matching row once in current stable sort order, canonical decimal fields, and fixed-two-decimal USD price. Assert no page-size truncation and no unrelated/history fields. |
| **INT-MARKET-006 - PDF report contract** | P0 | Generate reports with/without filters, empty results, enough rows for multiple pages, and a current advisory what-if result. Parse metadata/text and render every page. Assert A4 landscape geometry, UTC timestamp, normalized filters, matching count, aggregates, charts, complete sorted table, optional what-if inputs/price/version/warnings, page numbering, repeated table headers, valid media headers, and no clipping/overlap. |

## 10. Portal and cross-service integration cases

| ID | Pri | Method and required assertions |
| --- | --- | --- |
| **INT-PORTAL-001 - BFF, cookie, correlation, and error translation** | P0 | Run the production Next.js server against test services. Assert route handlers forward canonical bodies, only permitted headers, and a valid correlation UUID; return the downstream/canonical response status; generate and persist `hsbc_estimator_client` with `HttpOnly`, `SameSite=Lax`, `Path=/`, one-year Max-Age/matching Expires, and environment-correct Secure; and never accept a body/query owner override. Assert timeouts and service errors become stable, non-sensitive browser contracts. |
| **INT-PORTAL-002 - App Router, RSC initial load, and client boundaries** | P0 | Build the production portal and request estimator/market routes without browser-side JavaScript. Assert shared App Router layouts and initial data render from React Server Components, route loading/error boundaries exist, and interactive forms/filters/comparison are scoped Client Components. Static/import checks assert Tailwind use, custom hooks for genuinely shared client behaviour, no direct browser-to-private-service call, and no server secret in the client bundle. |
| **INT-CONTRACT-001 - OpenAPI provider/consumer compatibility** | P0 | Validate every service document as OpenAPI 3.1. Exercise representative valid/error examples against providers and each consuming client. Assert paths, methods, required seven-field schemas, batch limits/order, UUID correlation header, fixed decimal strings, warning/error envelopes, model-info fields, content types, and versioned `/api/v1` compatibility. A breaking change fails verification unless a new version or reviewed migration decision exists. |

## 11. Deployment, security, observability, and quality cases

| ID | Pri | Method and required assertions |
| --- | --- | --- |
| **INT-DEPLOY-001 - Image build and runtime inspection** | P0 | Assert deployable applications and `training/` have clear top-level directories and independent manifests, with no Turborepo-style orchestrator. Build every deployable unit from a clean Docker cache. Inspect and run images to assert Python 3.12.x, Java 21, Spring Boot 3.4.4, pinned dependencies/lockfiles, independent images, Next.js standalone output, non-root user, no `.git`/source secrets, explicit ephemeral writable paths, health/readiness checks, and Git-traceable tags. Inspect the market image for the reviewed Buildpack/layered or equivalent multi-stage approach. |
| **INT-DEPLOY-002 - Compose topology, persistence, and explicit training** | P0 | Validate and start the base Compose file. Assert migration ordering, private service discovery, only loopback portal/model host ports, persistent PostgreSQL and shared artifact volumes, and no host-published market/database/estimator ports. Restart services and assert metadata/history/artifacts persist while cache does not; `make up` never trains; first-use `make bootstrap` may train explicitly; debug/LAN overrides publish only documented ports and origins. |
| **INT-DEPLOY-003 - Makefile and delivery documentation** | P0 | From a clean machine-equivalent environment execute or dry-run documented `build`, `db-migrate`, `train`, `publish`, `bootstrap`, `up`, `up-lan`, `down`, `test`, and `verify` targets. Assert README prerequisites, URLs, Swagger, dataset/training steps, test commands, demo data, loopback/LAN modes, and interview flow are accurate. Confirm the deliverable commit is present in the intended GitHub repository; no hosted runtime is assumed. |
| **INT-SEC-001 - Local API security and resource limits** | P1 | Assert the local build introduces no account/identity-provider requirement, exposes no estimate-history export, and accepts no client-supplied history owner. Assert CORS permits only the configured portal origin, cookie-changing requests enforce same-origin/CSRF rules, invalid correlation IDs return `400 INVALID_CORRELATION_ID` before business work, body/batch/export limits are enforced, and arbitrary file paths cannot escape roots. Scan images/logs/repository fixtures for committed or emitted secrets. |
| **INT-OBS-001 - Correlated logs and local metrics** | P1 | Drive success, validation failure, downstream timeout, cache hit/miss, prediction failure, and export failure. Assert structured logs carry the same canonical correlation ID through each hop without secrets; local metrics/logs expose request count, latency, error rate, model-call latency, cache hit ratio, and prediction/export failures. Readiness failures must not be counted as liveness death. |
| **INT-QUALITY-001 - Local verification and coverage gates** | P0 | Run `make verify` and assert it includes formatting/lint, type checks, unit and integration tests, API contract validation, axe/accessibility checks, E2E smoke, and all image builds. Enforce at least 80% line/70% branch per service and at least 90% line coverage for financial rounding, model validation, history isolation, and cookie security; every exclusion has an inline reviewed justification. |

## 12. Numbered requirement traceability

Every requirement in [`requirements.md`](requirements.md) is mapped below. An E2E mapping means the behaviour is observed through the user or live API surface; an integration mapping verifies its internal contract or durable effect.

### 12.1 Shared portal and prediction

| Requirement | Primary verification |
| --- | --- |
| `REQ-PORTAL-001` | `E2E-PORTAL-001`, `INT-PORTAL-002` |
| `REQ-PORTAL-002` | `E2E-PORTAL-002`, `INT-PORTAL-002` |
| `REQ-PORTAL-003` | `E2E-PORTAL-002`, `E2E-FAIL-001`, `INT-PORTAL-001` |
| `REQ-PRED-001` | `E2E-T1-002`, `E2E-EST-001`, `INT-MODEL-002` |
| `REQ-PRED-002` | `E2E-T1-002`, `INT-MODEL-002`, `INT-MODEL-004` |
| `REQ-PRED-003` | `E2E-T1-003`, `INT-MODEL-002`, `INT-MODEL-004` |
| `REQ-PRED-004` | `E2E-T1-004`, `INT-MODEL-005` |
| `REQ-PRED-005` | `E2E-T1-001`, `INT-MODEL-001` |
| `REQ-PRED-006` | `E2E-T1-001`, `INT-CONTRACT-001` |
| `REQ-PRED-007` | `E2E-T1-005`, `E2E-EST-002`, `INT-MODEL-003` |
| `REQ-PRED-008` | `E2E-T1-005`, `E2E-MARKET-003`, `INT-MODEL-003` |
| `REQ-PRED-009` | `E2E-FAIL-001`, `INT-MODEL-004`, `INT-EST-001` |

### 12.2 Training, evaluation, and model registry

| Requirement | Primary verification |
| --- | --- |
| `REQ-TRAIN-001` | `INT-TRAIN-006`, `INT-DEPLOY-002` |
| `REQ-TRAIN-002` | `INT-TRAIN-001`, `INT-TRAIN-006` |
| `REQ-TRAIN-003` | `INT-TRAIN-001`, `INT-DB-002` |
| `REQ-TRAIN-004` | `INT-TRAIN-002` |
| `REQ-TRAIN-005` | `E2E-T1-004`, `INT-TRAIN-002` |
| `REQ-TRAIN-006` | `INT-TRAIN-003` |
| `REQ-TRAIN-007` | `INT-TRAIN-004` |
| `REQ-TRAIN-008` | `INT-TRAIN-004`, `INT-MODEL-005` |
| `REQ-TRAIN-009` | `E2E-T1-004`, `INT-MODEL-005` |
| `REQ-TRAIN-010` | `INT-TRAIN-003`, `INT-TRAIN-005` |
| `REQ-TRAIN-011` | `INT-TRAIN-006`, `INT-DEPLOY-003` |
| `REQ-TRAIN-012` | `INT-TRAIN-005` |

### 12.3 Data contract and assumptions

| Requirement | Primary verification |
| --- | --- |
| `REQ-DATA-001` | `E2E-MARKET-001`, `INT-DB-001`, `INT-MARKET-001` |
| `REQ-DATA-002` | `E2E-T1-003`, `E2E-EST-006`, `INT-DB-001`, `INT-TRAIN-002` |
| `REQ-DATA-003` | `E2E-EST-001`, `INT-MODEL-003`, `INT-CONTRACT-001` |
| `REQ-DATA-004` | `E2E-T1-005`, `E2E-EST-002`, `INT-MODEL-003` |
| `REQ-DATA-005` | `E2E-T1-005`, `INT-MODEL-003` |
| `REQ-DATA-006` | `E2E-EST-001`, `E2E-MARKET-004`, `E2E-MARKET-005`, `INT-MODEL-004`, `INT-MARKET-005`, `INT-MARKET-006` |
| `REQ-DATA-007` | `E2E-T1-005`, `E2E-MARKET-003`, `INT-MODEL-003` |

### 12.4 Property Value Estimator

| Requirement | Primary verification |
| --- | --- |
| `REQ-EST-001` | `E2E-EST-001`, `INT-CONTRACT-001` |
| `REQ-EST-002` | `E2E-EST-002`, `INT-MODEL-003`, `INT-PORTAL-001` |
| `REQ-EST-003` | `E2E-EST-001`, `INT-EST-001` |
| `REQ-EST-004` | `E2E-EST-001` |
| `REQ-EST-005` | `E2E-EST-001`, `E2E-UX-002` |
| `REQ-EST-006` | `E2E-EST-003`, `INT-EST-003`, `INT-DEPLOY-002` |
| `REQ-EST-007` | `E2E-EST-005`, `INT-PORTAL-002` |
| `REQ-EST-008` | `E2E-EST-001`, `E2E-EST-007`, `INT-EST-001`, `INT-EST-002` |
| `REQ-EST-009` | `E2E-EST-004`, `INT-EST-003` |
| `REQ-EST-010` | `E2E-EST-004`, `INT-EST-003` |

### 12.5 Property Market Analysis

| Requirement | Primary verification |
| --- | --- |
| `REQ-MARKET-001` | `E2E-MARKET-001`, `INT-MARKET-001` |
| `REQ-MARKET-002` | `E2E-MARKET-001`, `E2E-UX-002`, `INT-MARKET-001` |
| `REQ-MARKET-003` | `E2E-MARKET-002`, `INT-MARKET-002` |
| `REQ-MARKET-004` | `E2E-MARKET-003`, `INT-MARKET-004` |
| `REQ-MARKET-005` | `E2E-MARKET-004`, `INT-MARKET-005` |
| `REQ-MARKET-006` | `E2E-MARKET-005`, `INT-MARKET-006` |
| `REQ-MARKET-007` | `E2E-MARKET-002`, `E2E-UX-001`, `INT-MARKET-002` |
| `REQ-MARKET-008` | `E2E-MARKET-001`, `INT-MARKET-001` |
| `REQ-MARKET-009` | `E2E-MARKET-001`, `INT-MARKET-001` |
| `REQ-MARKET-010` | `E2E-MARKET-002`, `E2E-MARKET-004`, `E2E-MARKET-005`, `INT-MARKET-002`, `INT-MARKET-005`, `INT-MARKET-006` |
| `REQ-MARKET-011` | `E2E-MARKET-002`, `INT-MARKET-002` |

### 12.6 Market exports

| Requirement | Primary verification |
| --- | --- |
| `REQ-EXPORT-001` | `E2E-MARKET-004`, `INT-MARKET-005` |
| `REQ-EXPORT-002` | `E2E-MARKET-004`, `INT-MARKET-005` |
| `REQ-EXPORT-003` | `E2E-MARKET-005`, `INT-MARKET-006` |
| `REQ-EXPORT-004` | `E2E-MARKET-005`, `INT-MARKET-006` |
| `REQ-EXPORT-005` | `E2E-MARKET-005`, `INT-MARKET-006` |

## 13. PDF clause traceability

The source PDF also contains technology, architecture, UI, and delivery clauses without `REQ-*` identifiers. They are covered explicitly here.

| PDF requirement | Verification |
| --- | --- |
| Task 1 simple Scikit-learn regression using the supplied features | `INT-TRAIN-002`, `INT-TRAIN-004`, `INT-MODEL-005` |
| Task 1 single and batch prediction endpoints | `E2E-T1-002`, `E2E-T1-003`, `INT-MODEL-002` |
| Task 1 model coefficients and performance metrics | `E2E-T1-004`, `INT-MODEL-005` |
| Task 1 health endpoint | `E2E-T1-001`, `INT-MODEL-001` |
| Python 3.12+, FastAPI, and Scikit-learn | `INT-DEPLOY-001`, `INT-CONTRACT-001` |
| Task 1 source in GitHub, Dockerfile, and live Swagger/OpenAPI | `E2E-T1-001`, `E2E-DEMO-001`, `INT-DEPLOY-001`, `INT-DEPLOY-003` |
| One Next.js portal with two independent applications and different backends | `E2E-PORTAL-001`, `INT-PORTAL-002`, `INT-DEPLOY-002` |
| Both Task 2 applications interact with the Task 1 model | `E2E-EST-001`, `E2E-MARKET-003`, `INT-EST-001`, `INT-MARKET-004` |
| Shared layout/navigation and consistent design | `E2E-PORTAL-001`, `E2E-PORTAL-003` |
| App Router and layout-level loading/error states | `E2E-PORTAL-001`, `E2E-PORTAL-002`, `INT-PORTAL-002` |
| Estimator all-field form, validation, table, chart, history, and comparison | `E2E-EST-001` through `E2E-EST-005` |
| Python estimator form handling, model integration, validation, and errors | `INT-EST-001`, `INT-PORTAL-001`, `E2E-EST-002`, `E2E-FAIL-001` |
| Market dashboard, visualisations, segment filters, what-if, exports, and responsive sortable/filterable table | `E2E-MARKET-001` through `E2E-MARKET-005`, `E2E-UX-001` |
| Java REST endpoints, aggregates, shared-model integration, and caching | `INT-MARKET-001` through `INT-MARKET-004`, `INT-CONTRACT-001` |
| Task 2 Python 3.12+ and FastAPI estimator backend | `INT-DEPLOY-001`, `INT-CONTRACT-001`, `INT-EST-001` |
| React Server Components for initial data and appropriate Server/Client Components | `INT-PORTAL-002` |
| Appropriate data fetching, custom hooks, client/form state, and API/error handling | `INT-PORTAL-001`, `INT-PORTAL-002`, `E2E-PORTAL-002`, `E2E-EST-002` |
| Tailwind responsive UI, WCAG accessibility, error boundaries, smooth transitions, modern cohesive design | `E2E-PORTAL-002`, `E2E-PORTAL-003`, `E2E-UX-001`, `E2E-UX-002`, `INT-PORTAL-002` |
| Next.js best-practice code organisation | `INT-PORTAL-002`, `INT-QUALITY-001` |
| Java 21 and Spring Boot 3.4.4 | `INT-DEPLOY-001` |
| Task 2 source in GitHub and live interview demonstration | `E2E-DEMO-001`, `INT-DEPLOY-003` |

## 14. Release gate and evidence

Integration verification is complete when:

- all P0 cases pass and no open defect invalidates a PDF-required capability;
- all P1 cases pass or have an explicit risk owner and interview-safe workaround;
- OpenAPI provider/consumer validation passes without an unreviewed breaking change;
- database tests ran against a new disposable PostgreSQL 17 container;
- generated CSV and PDF exports were parsed, and every PDF page was rendered for visual inspection;
- coverage thresholds and the complete `make verify` gate pass; and
- the report records Git commit, runtime/image versions, dataset and model versions, test seed/clock, test results, generated evidence, and linked defects.

Results for the current release candidate are recorded in [`test-report.md`](test-report.md).

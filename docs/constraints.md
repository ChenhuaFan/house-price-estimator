# Project Constraints

This document records the technical, architectural, quality, and delivery constraints that must be followed during implementation. It intentionally excludes product feature requirements. [`Interview Tasks Fullstack.pdf`](../requirements/Interview%20Tasks%20Fullstack.pdf) is authoritative for source constraints; an [English Markdown transcription](../requirements/Interview%20Tasks%20Fullstack.md) is available for convenience. PostgreSQL, model-registry, persistence, testing, and local-deployment details identified here are project decisions that supplement the PDF. If a project decision conflicts with the source PDF, the PDF takes precedence.

## 1. Technology Stack and Versions

The baseline uses the lowest version line explicitly permitted by the source document. Versions omitted by the source are inferred from stable releases available when the source PDF was created in April 2025 and from the compatibility requirements of the selected frameworks.

| Scope | Technology | Baseline Version | Basis |
| --- | --- | --- | --- |
| Machine learning runtime | Python | 3.12.x | Source minimum; use the latest available 3.12 patch release |
| Machine learning API | FastAPI | 0.115.12 | Inferred stable release available in March 2025 |
| ASGI server | Uvicorn | 0.34.0 | Inferred compatible runtime for FastAPI 0.115.12 |
| Machine learning | Scikit-learn | 1.6.1 | Inferred stable line with Python 3.12 support |
| Frontend runtime | Node.js | 20.x, minimum 20.9.0 | Inferred LTS runtime compatible with Next.js 15 |
| Unified portal | Next.js | 15.5.28 | Security-maintained Next.js 15 App Router release; preserves the PDF-compatible major baseline |
| UI runtime | React and React DOM | 19.0.1 | Security-patched React 19 release compatible with Next.js 15 |
| Frontend language | TypeScript | 5.1.0 or later within 5.x | Inferred compatible baseline for the selected Next.js line |
| Frontend styling | Tailwind CSS | 4.0.0 | Inferred stable major release available before the source date |
| JavaScript package manager | pnpm | 10.6.x | Inferred stable release line available before the source date |
| Property Value Estimator backend | Python | Same Python 3.12.x runtime | Must integrate with the Task 1 regression model container |
| Property Value Estimator backend framework | FastAPI | 0.115.12 | Source requires FastAPI for the Task 2 Python backend; version is inferred |
| Property Market Analysis runtime | Java | 21 | Source requirement; use a maintained Java 21 patch release |
| Property Market Analysis framework | Spring Boot | 3.4.4 | Exact source requirement |
| Java build system | Maven | 3.6.3 or later | Inferred minimum supported by Spring Boot 3.4.x |
| Relational database | PostgreSQL | 17.x | Inferred stable, supported release line for durable application data |
| Java application cache | Caffeine | 3.1.x | In-process cache compatible with Spring Cache; no separate cache service is required |
| Local container orchestration | Docker Compose | v2 | Mainstream declarative multi-container development workflow |

### Version Enforcement Policy

- Python must remain on the 3.12 release line; Java must remain on the Java 21 release line.
- Spring Boot must remain at 3.4.4 unless the source constraint is explicitly revised.
- Direct application dependencies must be pinned to the baseline versions in their manifests when each application is created.
- The `web-portal` package must declare its pnpm version through the `packageManager` field and commit its `pnpm-lock.yaml`.
- Generated lockfiles must be committed and used for reproducible installs.
- Security and defect fixes may be applied within the selected major release line when an older minor line no longer receives all required fixes. Major-version changes require a documented technical decision.

## 2. Model and Numerical Constraints

- Housing price prediction must use Scikit-learn's ordinary least-squares `LinearRegression` as the simple regression model.
- The model must operate on the supplied housing dataset and its provided features.
- Prediction and training code must map fields by column name into this canonical feature order: `square_footage`, `bedrooms`, `bathrooms`, `year_built`, `lot_size`, `distance_to_city_center`, `school_rating`. JSON property order must never determine feature order.
- The database seed-generation and Web/API test-fixture tooling must handle the optional UTF-8 byte-order mark present in the supplied CSV files. Normal training reads an exported central-dataset snapshot rather than the original CSV.
- Dataset content and snapshot fingerprints must use the canonical serialization defined in [`data-schema.md`](data-schema.md); platform-default CSV formatting is not permitted.
- The top-level `training/` workspace must use the script, snapshot, artifact, and registry paths specified by `REQ-TRAIN-002`, `REQ-TRAIN-008`, `REQ-TRAIN-010`, and `REQ-TRAIN-011`.
- Training and evaluation must be reproducible from an immutable central dataset version, a deterministic exported snapshot, pinned dependencies, the saved local split membership, and the documented random seed.
- Model metadata must identify the central dataset version, source-file fingerprint, normalized dataset fingerprint, exported snapshot fingerprint, Git commit, and training-run identifier.
- `Test Data For Prediction.csv` must not be loaded by database initialization or used to calculate model accuracy metrics. It is exercised through the Web/API path, where successful predictions may become ordinary estimate-history records.
- Both Task 2 applications must integrate with the same machine learning model service produced in Task 1.

### Canonical Model Data Types and Ranges

Hard validation is limited to presence, type, finiteness, discrete-value rules, physically meaningful lower bounds, and the representable range of the canonical API/database type. Unless listed as an advisory range, no upper bound may be inferred from the supplied datasets.

| Field | Canonical source/API type | Model representation | Hard accepted range | Advisory range | Role |
| --- | --- | --- | --- | --- | --- |
| `id` | Integer | Not included | `>= 1`; must fit `bigint` | None | Source-record identifier |
| `square_footage` | Integer | `float64` | `>= 1`; must fit `bigint` | None | Model input, sq. ft. |
| `bedrooms` | Integer | `float64` | `>= 0`; must fit `integer` | None | Model input |
| `bathrooms` | Decimal in increments of `0.5` | `float64` | `>= 0.5`; must fit `numeric(12,1)` | None | Model input |
| `year_built` | Integer | `float64` | `>= 1`; must fit `integer` | None | Model input, calendar year |
| `lot_size` | Integer | `float64` | `>= 0`; must fit `bigint` | None | Model input, sq. ft. |
| `distance_to_city_center` | Decimal | `float64` | `>= 0`; must fit `numeric(15,3)` | None | Model input, miles |
| `school_rating` | Decimal | `float64` | Any finite value fitting `numeric(15,3)` | `1` through `10` inclusive | Model input, 10-point scale |
| `price` | Decimal USD amount | `float64` during training | `> 0`; must fit `numeric(18,2)` | None | Training target; never a prediction input |

- Integer fields must not accept fractional values, booleans, or numeric strings.
- Decimal fields must not accept booleans, numeric strings, NaN, positive infinity, or negative infinity.
- `bathrooms` must be exactly representable in increments of `0.5`; `distance_to_city_center` and `school_rating` must be exactly representable with no more than three fractional decimal places. Additional trailing zeroes are permitted because they do not change effective scale. A value that would require PostgreSQL to round to the declared `numeric` scale must be rejected before inference and before persistence.
- PostgreSQL `numeric(p,s)` coercion is a storage safeguard, not an input-normalization strategy. Every backend must use the same validated decimal value for inference, persistence, response data, and downstream calls; no backend may infer with a higher-precision value and persist a rounded value.
- All seven model inputs must be converted to `float64` only after canonical validation and then arranged in canonical feature order.
- Violating a hard accepted range must reject the record. Violating an advisory range must not reject the record or suppress its prediction; it must produce a structured warning.
- The system must never reject or warn solely because a value is below the training-set minimum or above the training-set maximum.

### Monetary Precision

- USD amounts must use decimal or fixed-point types for storage, transport, rounding, aggregation, comparison, and export. Binary floating-point values must not be used for financial business logic.
- Python financial logic must use `decimal.Decimal`, Java financial logic must use `BigDecimal`, and TypeScript financial logic must use a decimal-arithmetic library such as `decimal.js`.
- A raw Scikit-learn prediction must be converted from its decimal string representation to a decimal type immediately at the model-service boundary, rounded once to `0.01` using `ROUND_HALF_UP`, and transported as a fixed two-decimal string with the currency code `USD`.
- Scikit-learn training, model inference, and statistical metric calculation necessarily use IEEE-754 floating-point arithmetic and are the only exceptions to the financial decimal rule. Their results must not be used for downstream monetary arithmetic until converted as specified above.
- Evaluation values used for acceptance must be converted with `Decimal(str(value))` and quantized to exactly 12 fractional digits using `ROUND_HALF_EVEN`. MAPE is converted to percentage points by multiplying the mean absolute percentage ratio by `100` before this conversion, so a 3.788% error is stored as `3.788000000000`, not `0.037880000000`. The same canonical decimal strings must be stored in `metrics.json` and PostgreSQL; display rounding must never drive acceptance.
- A raw prediction must be finite, non-negative, and representable as `numeric(18,2)` after `ROUND_HALF_UP`. Otherwise the model service must return `MODEL_OUTPUT_OUT_OF_DOMAIN`; it must never clamp, wrap, persist, or return the invalid amount. Batch prediction is atomic for this rule.
- A transient floating-point conversion may be used only to position an already-calculated value in a chart. The exact decimal amount remains the source of truth for labels, tables, comparisons, aggregates, and exports.

## 3. System Architecture Constraints

- The authoritative component, interaction, deployment, and failure architecture is defined in [`architecture.md`](architecture.md).
- A single, unified Next.js portal must host two independent applications.
- The repository must keep each deployable application and `training/` in clear top-level directories with independent build manifests. Turborepo and other monorepo task orchestrators must not be introduced. A root `Makefile` and Docker Compose provide the small amount of cross-project command orchestration required for local use.
- Backend capabilities must be split at coarse business boundaries, not by individual endpoint or entity. The required deployable units are:

| Deployable unit | Technology | Responsibility | State ownership |
| --- | --- | --- | --- |
| `web-portal` | Next.js | Unified UI, shared navigation, server rendering, browser-facing backend-for-frontend routes | No authoritative business data |
| `model-service` | Python, FastAPI, Scikit-learn | PDF-required prediction, model-info, and health capabilities exposed through project-defined routes, plus operational liveness/readiness endpoints; resolves and serves the active trained model | Read-only model registry access and versioned model artifacts |
| `estimator-service` | Python, FastAPI | Validates estimator requests, calls the model service, and manages estimate history and comparisons | Estimate-history tables in PostgreSQL |
| `market-service` | Java 21, Spring Boot 3.4.4 | Dataset queries, filters, sorting, aggregate statistics, what-if orchestration, caching, and CSV/PDF generation from one analysis result | Read-only central catalog access, bounded in-process cache, and temporary export files |

- PostgreSQL is an infrastructure dependency rather than an application microservice. Its authoritative business persistence design is defined in [`data-schema.md`](data-schema.md).
- A new microservice must not be introduced unless it has a distinct business capability and at least one independent reason to deploy separately, such as scaling, security isolation, failure isolation, or lifecycle ownership.
- Both applications must share a layout and navigation and use a consistent design system.
- Routing within and between the applications must use the Next.js App Router.
- Initial data loading must use React Server Components.
- Server Components and Client Components must be divided appropriately according to their responsibilities.
- Shared functionality must be implemented through custom hooks.
- The Next.js codebase must be structured according to Next.js best practices.
- Service-to-service interfaces must use REST with versioned OpenAPI 3.1 contracts and JSON payloads, except that CSV and PDF responses use their corresponding media types.
- The base Compose file may publish only the portal and Task 1 model Swagger/API ports. A separate, explicit debug override may publish the estimator FastAPI port for trusted-network Swagger/contract troubleshooting; the portal must continue using the private Compose address. `market-service` and PostgreSQL must never be host-published in the current scope.
- PostgreSQL schema ownership and runtime grants must follow [`data-schema.md`](data-schema.md). Only database migrations may modify the central `catalog`; the training workspace reads `catalog` and writes `ml_registry`; the market service reads `catalog`; the model service reads `ml_registry`; and the estimator service writes only `estimator`. No runtime component may write outside its assigned boundary.
- The training workspace is an offline subsystem of the overall product rather than an always-on application service. It reads a selected central dataset version, manages snapshots and splits as files, writes training and model metadata to `ml_registry`, and publishes artifacts for `model-service` without depending on the portal or application backends.
- Internal calls must define connection and response timeouts. Retries with exponential backoff and jitter are permitted only for safe or idempotent operations; a retry must not duplicate a history entry or produce a duplicate export side effect.

## 4. Container and Deployment Constraints

- Every deployable unit listed in Section 3 must be packaged as an independent OCI-compatible container image. Next.js, both FastAPI services, and the Java backend are included; no application service may rely on a host-installed runtime in a deployed environment.
- `model-service`, `estimator-service`, and `web-portal` must each include a multi-stage `Dockerfile`. `market-service` must produce a layered OCI image through the Spring Boot Maven plugin and Cloud Native Buildpacks; an equivalent reviewed multi-stage `Dockerfile` is acceptable only if it preserves the same security and reproducibility properties.
- The Next.js production build must use `output: "standalone"` so that the runtime image contains only the minimal server and traced production dependencies.
- Images must run as non-root users, contain no source-control metadata or secrets, and expose health/readiness checks. Writable temporary storage must be explicit and ephemeral.
- Image tags must be traceable to the current Git commit when preparing the interview demonstration.
- The repository must provide one root `compose.yaml` that builds and starts the complete local environment: the database migration job, all application services, and PostgreSQL. A persistent PostgreSQL volume must preserve registered business and model metadata, and a separate shared artifact volume must expose published model files to the training workspace and model service.
- The training workspace must be runnable as an explicit one-shot local command or Compose profile. It must never retrain a model merely because an application container starts.
- Local build and deployment use Docker Compose v2. The required commands must be exposed through simple root `Makefile` targets, including at least `build`, `db-migrate`, `train`, `publish`, `bootstrap`, `up`, `up-lan`, `down`, `test`, and `verify`.
- `make bootstrap` may orchestrate database migration followed by an explicit initial training and publication when no active model exists. Normal `make up` and application restarts must never retrain or silently replace the active model.
- Cloud deployment, cloud-provider infrastructure, Kubernetes, and infrastructure-as-code tooling are outside the current project scope. They must not be required to build, run, test, or demonstrate the system.

## 5. Data Flow, State, and Reliability Constraints

- Appropriate client-side state management must be implemented.
- Form state must be managed effectively, and the estimator must perform client-side validation with appropriate error messages.
- Efficient and appropriate data-fetching strategies must be used.
- API communication must handle error states correctly.
- Estimate history must use PostgreSQL as its server-side durable system of record and follow the `estimator` schema in [`data-schema.md`](data-schema.md). Browser memory, session storage, and local storage must not be the authoritative history store.
- Persisted history must be scoped to an opaque client identifier stored in a first-party cookie named `hsbc_estimator_client`. The cookie must use `HttpOnly`, `SameSite=Lax`, `Path=/`, `Max-Age=31536000`, and a matching `Expires` value so that the same history can be retrieved across ordinary browser restarts. It must use `Secure` whenever the configured public origin uses HTTPS; the flag may be omitted only for the documented local HTTP demonstration. The identifier must be a random UUIDv4, must not contain user or property data, and must map to the client record defined in [`data-schema.md`](data-schema.md). Clearing or blocking the cookie removes that browser's ability to locate its history but does not delete server-side rows.
- History reads must use bounded cursor-based pagination with a default page size of 20.
- Every estimator submission must carry a portal-generated UUIDv4 `request_id`. Before inference, the estimator must look up `(client_id, request_id)`: an existing record with the same seven canonical inputs returns the originally persisted estimate without another model call, while different inputs return `409 IDEMPOTENCY_KEY_REUSED`. Concurrent insert conflicts must load the winning row and apply the same comparison; a later model version must never replace the result associated with an existing key.
- Synchronous batch prediction requests must be limited to 1,000 property records.
- Services must accept an optional `X-Correlation-ID` request header. A supplied value must be a valid UUID and is normalized to the canonical lowercase hyphenated representation; an invalid value returns `400 INVALID_CORRELATION_ID` before business processing. When the header is absent, the receiving service generates a UUIDv4. The UUID must be propagated across internal calls, returned in the response header, and included in request and error logs. OpenAPI contracts must declare the header as a string with `format: uuid`. A correlation ID is request metadata and must never be treated as a model feature.
- Loading and error states must be handled at the shared layout level.
- The UI must use appropriate loading states and error boundaries.
- The Python backend must implement data validation and error handling.
- The market service must use Spring Cache backed by a bounded Caffeine in-process cache for repeated market queries and aggregate results. Cache keys must include the dataset fingerprint, normalized filters, sort specification, and relevant model version. Dataset or model activation must logically invalidate prior entries because the new version produces a different key; old entries must never be returned for the new version and may remain only until the five-minute TTL or bounded-size eviction removes them. Explicit whole-cache mutation is not required.
- Business-data flow into training is one-way: the training role may select the chosen `catalog` dataset version but must never insert, update, or delete central housing records. Its only database writes are run, evaluation, and model-registry records in `ml_registry`.
- Cached data is disposable and must be reconstructed after a market-service restart. Authoritative history and monetary values must never depend on cache persistence.
- All services must emit structured logs and include the correlation ID in request, downstream-call, and error logs. The local demonstration must expose request count, latency, error rate, model-call latency, cache hit ratio, and prediction/export failures through application metrics endpoints or logs; no external observability platform is required.
- Each application service must expose separate liveness and readiness signals. Readiness must fail when a required startup dependency is unavailable, without treating a transient downstream failure as process death.

## 6. Local Scope and API Security Constraints

- The source PDF does not require user accounts, authentication, or role-based authorization. The local interview build must not introduce an identity provider or account-management flow.
- History isolation must use the opaque client identifier defined in Section 5. The server must apply that identifier to every history read and delete operation; a client-supplied history owner in a URL or JSON body must not override it. Estimate-history export is not part of the current requirements or API.
- Published container ports must bind to the loopback interface by default. A documented LAN override may bind the portal and model service to a specific LAN interface or `0.0.0.0`; an independent debug override may do the same for estimator Swagger. LAN use must be explicit, configure the matching portal origin and CORS/CSRF trusted origins, remain behind the host firewall, and never change service-to-service traffic away from the private Docker Compose network.
- CORS must allow only the configured local portal origin. Cookie-based state-changing requests must use `SameSite` protection and an explicit CSRF defence where the framework does not provide equivalent same-origin enforcement.
- Request bodies, uploaded batch files, and export sizes must have explicit limits. All inputs must pass the canonical validation rules before business processing.
- Local secrets and the separate migration, training, market, model, and estimator database credentials must be supplied through ignored environment files or Docker Compose secrets. They must never be committed, copied into images, or printed in logs.
- Authentication and internet-facing security must be reconsidered in a separate technical decision before any future shared or public deployment.

## 7. UI and Accessibility Constraints

- Responsive layouts, including the Property Market Analysis data tables, must be implemented with Tailwind CSS.
- The portal must conform to WCAG 2.2 Level AA. New UI work must meet applicable Level A and AA success criteria.
- Automated accessibility checks must use axe-core in component or end-to-end tests. Release verification must also cover keyboard-only operation, visible focus, semantic headings and landmarks, form labels and errors, color contrast, reduced motion, and representative screen-reader flows.
- Transitions between pages and application states must be smooth.
- Animations must respect `prefers-reduced-motion` and must not be required to understand application state.
- Both applications must use a cohesive UI that follows modern design principles.

## 8. Testing and Local Quality Gates

Concrete browser acceptance cases are defined in [`e2e-test-plan.md`](e2e-test-plan.md); service, data, model, container, and traceability cases are defined in [`integration-test-plan.md`](integration-test-plan.md).

- Frontend unit and component tests must use Vitest and React Testing Library. Browser end-to-end tests must use Playwright.
- Python tests must use pytest. Ruff must enforce linting and formatting, and mypy must type-check application and training code.
- Java tests must use JUnit 5 and Spring Boot Test. Integration tests that depend on PostgreSQL must use a disposable container rather than a shared developer database.
- OpenAPI contracts must be validated by the local quality-gate command. Breaking changes to an existing API version require a new version or an explicit migration decision.
- Each service must meet at least 80% line coverage and 70% branch coverage. Financial rounding, model-input validation, history isolation, and client-cookie security rules must each meet at least 90% line coverage. Coverage exclusions require an inline justification and review.
- The root `make verify` command must run formatting/linting, type checks, unit tests, integration tests, API-contract checks, accessibility checks, end-to-end smoke tests, and container-image builds locally.
- GitHub Actions and any other hosted CI/CD platform are outside the current project scope. GitHub is used to store and review source code, not to host or deploy the running applications.
- Database schemas and the initial 50-row housing dataset must use the centralized versioned SQL migration layout in [`data-schema.md`](data-schema.md), be reproducible against an empty PostgreSQL database, and never preload `Test Data For Prediction.csv`.

## 9. Delivery and Demonstration Constraints

- Source code must be stored in a GitHub repository. The source PDF does not require the applications to be hosted by GitHub.
- Task 1 must be available for a live demonstration through Swagger/OpenAPI.
- Task 2 must be available for a live demonstration during the interview.
- The Task 1 model and all Task 2 application services must be built, containerised, and deployable on the interview machine through Docker Compose. No remote runtime is required.
- The repository README must document prerequisites, local build and startup through Docker Compose, loopback and LAN publication modes, database initialization, dataset snapshot export, training and publication commands, test commands, service URLs, Swagger/OpenAPI locations, demonstration data, and the local interview workflow.

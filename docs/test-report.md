# Release Acceptance Report

## 1. Result

The implementation at commit `f3ea323863c2808a2133706820b3d87f4666ea8c` passes the local release-candidate gate defined by the [end-to-end test plan](e2e-test-plan.md) and [integration test plan](integration-test-plan.md).

| Gate | Result | Notes |
| --- | --- | --- |
| PDF-required application behaviour | **Pass** | All locally executable P0 journeys passed. |
| Reliability, usability, accessibility, and isolation | **Pass** | All locally executable P1 checks passed without a waiver. |
| Build, test, image, and local deployment | **Pass** | `make verify` completed successfully from the tested commit. |
| GitHub delivery | **Pass** | The complete `main` history is published in the public [`ChenhuaFan/house-price-estimator`](https://github.com/ChenhuaFan/house-price-estimator) repository. |

There are no unresolved P0 or P1 product defects and no outstanding PDF delivery items.

## 2. Scope and environment

The final acceptance run was performed on 2026-10-18 in Asia/Shanghai. Application timestamps and exported report timestamps were checked as UTC.

| Component | Tested version |
| --- | --- |
| Git implementation commit | `f3ea323863c2808a2133706820b3d87f4666ea8c` |
| Host | Darwin, arm64 |
| Docker / Docker Compose | 29.2.0 / 5.0.2 |
| Python | 3.12.10 |
| Node.js / pnpm | 20.19.0 / 10.6.5 |
| Java | Eclipse Temurin 21.0.6+7 |
| PostgreSQL | 17.4 |
| Playwright | 1.64.1 |
| Browsers | Chromium 156.0.8078.4, Firefox 157.0, WebKit revision 2370 |

The clean acceptance environment used Compose project `hsbc-housing-acceptance`, portal port `3100`, model API port `8100`, and a new PostgreSQL volume. All five migrations ran against the empty database before the application tests.

## 3. Clean-start and lineage evidence

The bootstrap imported the immutable `house-price-v1` dataset with 50 declared and 50 stored records. It trained and activated model `housing-linear-76a0d11b-6ab` in run `76a0d11b-6abc-441c-b68b-3bf87f528c95` using seed `42`, 40 training rows, and 10 evaluation rows. The stored training lineage names the exact tested Git commit.

All application images used the Git-derived tag `f3ea323863c2`:

| Image | Image digest |
| --- | --- |
| `hsbc/model-service:f3ea323863c2` | `sha256:f352d194941f4e689898639e44bbc40a992c2108a3ae113e3be00389c0ee81fe` |
| `hsbc/estimator-service:f3ea323863c2` | `sha256:2820747f604064a5c70789c71c0588f5455de72db0b93cb522e76f9ff137e5f0` |
| `hsbc/market-service:f3ea323863c2` | `sha256:63ba4db9ccd428618b8d95027c84b6d58a38437d586bd2bea0dcce46f7d8fb91` |
| `hsbc/web-portal:f3ea323863c2` | `sha256:695e4b00336fde708559aa39495c42381dbe42f11072476b9de62487515384e9` |

Only the portal and model API were published to loopback. The estimator service, market service, and PostgreSQL were reachable only on the private Compose network.

After a persisted estimate (`b7043557-53f2-4c47-bc1d-3832d0c41946`) was created, all four application services were restarted without removing volumes. The estimate, live business data, active model with its frozen dataset lineage, and artifact remained available. The training-run count stayed at one, proving that ordinary startup and restart did not retrain the model.

## 4. Automated verification

| Verification | Result | Evidence summary |
| --- | --- | --- |
| Complete release gate | **Pass** | `make verify` completed formatting, lint, static typing, unit/integration tests, coverage gates, production build, Compose validation, image builds, bootstrap, smoke tests, live OpenAPI checks, accessibility checks, and Chromium E2E. |
| Training workspace | **21 passed** | 91.03% line and 71.00% branch coverage. |
| Model service | **38 passed** | 96.63% line and 91.94% branch coverage. |
| Estimator service | **34 passed** | 95.23% line and 85.94% branch coverage. |
| Market service | **33 passed** | 93.21% instruction, 90.64% line, and 70.23% branch coverage. |
| Web portal unit/component tests | **78 passed** | 91.73% line/statement and 91.21% branch coverage; lint, typecheck, and production build passed. |
| Live OpenAPI contracts | **Pass** | Model, estimator, and market documents matched the reviewed OpenAPI 3.1 contracts. |
| Chromium release E2E | **16 passed** | Live services were used; the browser suite did not replace backend traffic with route mocks. |
| Full browser matrix | **42 passed, 6 skipped** | Chromium, Firefox, and WebKit core journeys passed. Three controlled failure/stress cases run once in Chromium and are intentionally skipped in each of Firefox and WebKit. |
| Clean-stack acceptance subset | **14 passed** | Non-fault-injection journeys passed against the isolated fresh-volume stack. |
| Production dependency audit | **Pass** | `pnpm audit --prod` reported no known vulnerabilities. |

The six skipped matrix entries are not untested features: route-failure recovery, 21-record history stress, and model-dependency recovery each passed in Chromium. Their ordinary surrounding journeys ran in all three engines. WebKit's navigation and market-interaction tests were also repeated three times after the hydration-readiness fix; all six repeated executions passed.

## 5. User and requirement coverage

The passing browser and service suites cover every numbered requirement in the two test plans. In particular, the run verified:

- live Swagger discovery, single and batch prediction, model metadata, validation, advisory warnings, and correlation IDs;
- shared navigation, deep links, route loading, user-readable errors, retry recovery, keyboard use, reduced motion, accessibility, and responsive layouts;
- the seven-field estimator, exact USD presentation, durable history, 21-record cursor pagination, comparison, delete, clear, idempotent replay, and browser-client isolation;
- the 50-row market dashboard, filters, stable sorting, URL state, aggregate/chart/table consistency, shared-model what-if analysis, and model-outage isolation;
- complete CSV and PDF exports using the active filter and sort rather than only the visible table page;
- immutable dataset/model lineage, fixed-seed training, evaluation metrics, model activation, versioned caching, migration ordering, private service topology, restart persistence, and non-root application images; and
- structured errors and logs without stack traces, secrets, internal hostnames, or client-supplied history ownership.

No risk waiver was needed. The observability assertions were exercised by automated correlation-ID, logging, metrics, failure, and cache tests. Playwright trace, screenshot, console, and network capture remain configured for failed runs; no failed final-run trace was produced.

## 6. PDF export inspection

The release run generated a 3-page A4 landscape market report from all 50 records sorted by price descending, including an advisory what-if result with `school_rating = 11`.

| Property | Observed value |
| --- | --- |
| File | `tmp/pdfs/release-f3/property-market-analysis.pdf` (ignored test evidence, reproducible from the application) |
| SHA-256 | `cee872a55b7dbec79fd3018dd0e4bf58805c5a3b59d34b6eda15ff7425246df6` |
| PDF metadata | PDF 1.5, OpenPDF 2.0.3, 11,833 bytes |
| Geometry | 595 x 842 points with 90-degree rotation; A4 landscape |
| Content check | Title, UTC timestamp, dataset, filters, count, aggregates, chart, complete sorted table, all what-if inputs, two-decimal USD result, active model version, and warning were present. |
| Visual check | All three rendered pages were inspected. Headers repeat on continuation pages; page numbers, rows, chart, and what-if section are readable with no clipping or overlap. |

The binary and page renders are deliberately kept under ignored `tmp/` paths. The checksum and inspection result are retained here, while the export remains reproducible from the documented UI journey.

## 7. Defects found and closed during acceptance

| Commit | Resolution |
| --- | --- |
| `c707171` | Made bootstrap wait for genuinely healthy dependencies. |
| `515e43b` | Kept generated Next.js files out of the source lint boundary. |
| `29d4923` | Reworked the market PDF into a readable, complete multi-page report. |
| `281147d` | Fixed long pagination-cursor forwarding and made route retry refresh server data; added missing resilience journeys. |
| `f3ea323` | Prevented interactions before client hydration and stabilised keyboard/browser behaviour. |

## 8. Reproduction

Run the complete local release gate:

```sh
make verify
```

Run the full browser matrix against a healthy stack:

```sh
cd web-portal
corepack pnpm test:e2e:all
```

To repeat the fresh-volume acceptance without disturbing the default stack:

```sh
COMPOSE_PROJECT_NAME=hsbc-housing-acceptance \
WEB_PORT=3100 \
MODEL_PORT=8100 \
PORTAL_PUBLIC_ORIGIN=http://127.0.0.1:3100 \
IMAGE_TAG=f3ea323863c2 \
make bootstrap

cd web-portal
PLAYWRIGHT_BASE_URL=http://127.0.0.1:3100 \
MODEL_API_URL=http://127.0.0.1:8100 \
corepack pnpm test:e2e
```

Remove only that isolated acceptance environment when finished:

```sh
COMPOSE_PROJECT_NAME=hsbc-housing-acceptance \
WEB_PORT=3100 \
MODEL_PORT=8100 \
PORTAL_PUBLIC_ORIGIN=http://127.0.0.1:3100 \
IMAGE_TAG=f3ea323863c2 \
docker compose down --volumes --remove-orphans
```

The default local Docker deployment remains the supported demonstration target. The project documents do not require a public hosted runtime. The source PDF's GitHub-publication requirement is satisfied by the public repository linked in Section 1.

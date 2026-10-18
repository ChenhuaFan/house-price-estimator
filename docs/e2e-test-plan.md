# End-to-End Test Plan

## 1. Purpose and authority

This plan verifies the complete user-visible behaviour of the Housing Price Prediction Model API and the Multi-Application Next.js Portal. The primary authority is [`Interview Tasks Fullstack.pdf`](../requirements/Interview%20Tasks%20Fullstack.pdf). [`requirements.md`](requirements.md), [`constraints.md`](constraints.md), [`architecture.md`](architecture.md), and [`data-schema.md`](data-schema.md) add project-defined acceptance detail where the PDF is silent. If those details conflict with the PDF, the PDF takes precedence.

The plan has two goals:

1. prove that every PDF-required capability can be demonstrated from a clean environment; and
2. verify that a user can complete the estimator and market-analysis journeys clearly, safely, and without unreasonable or surprising behaviour.

This document defines browser and live-system tests. Service, database, model-training, cache, and contract boundaries are covered in [`integration-test-plan.md`](integration-test-plan.md).

## 2. Test approach

- Use Playwright for automated browser tests against the containerised system. Do not replace downstream services with browser-route mocks in the release E2E suite.
- Exercise the Task 1 API through its live Swagger/OpenAPI page and direct HTTP requests made by the E2E runner.
- Run critical journeys in Chromium. Run the release smoke subset in Chromium, Firefox, and WebKit.
- Run responsive checks at representative desktop, tablet, and mobile viewports. At minimum use 1440 x 900, 768 x 1024, and 390 x 844.
- Use axe-core for automated accessibility checks and supplement it with keyboard-only, reduced-motion, focus, and representative screen-reader checks.
- Use controlled service shutdown or a documented fault-injection profile for failure tests. A failure test must exercise the same browser-facing and service-facing paths used in production.
- Capture Playwright traces, screenshots, console output, network failures, and the correlation ID for every failed test.

## 3. Environment and entry criteria

The release E2E run starts from a clean clone or equivalent clean checkout and a new PostgreSQL volume. It uses the repository's documented `make bootstrap` and `make up` workflow. Before tests begin:

- database migrations have completed successfully;
- the initial immutable dataset contains the 50 rows from `House Price Dataset.csv`;
- an accepted model is active and loadable by `model-service`;
- the portal, model, estimator, and market services report ready;
- the portal and Task 1 Swagger/API are reachable through their documented host ports;
- no Task 2 backend or PostgreSQL port is host-published by the base Compose configuration;
- browser contexts start without cookies unless a case explicitly reuses stored browser state; and
- clocks, locale, and timezone used by assertions are fixed or recorded so that UTC timestamps and exports are deterministic.

The release candidate fails the E2E gate if an entry criterion cannot be established from repository instructions.

## 4. Canonical test data

Use the following records unless a case specifies otherwise. Expected prices are taken from the active model response and then checked for consistency across the table, chart, history, comparison, and export surfaces; tests must not hard-code a price from an older model version.

| Name | Input or source | Intended use |
| --- | --- | --- |
| Property A | `1500, 3, 2.0, 1995, 6000, 5.0, 8.0` in canonical field order | Ordinary valid estimate |
| Property B | `2200, 4, 2.5, 2012, 9000, 12.5, 9.0` | Comparison and what-if |
| Advisory property | Property A with `school_rating = 11` | Valid prediction with warning |
| Missing-field property | Property A without `lot_size` | Required-field rejection |
| Invalid-integer property | Property A with `bedrooms = 2.5` | Type rejection |
| Invalid-step property | Property A with `bathrooms = 1.3` | Discrete-value rejection |
| Excess-scale property | Property A with `distance_to_city_center = 5.1234` | Precision rejection |
| Demonstration batch | All rows in `Test Data For Prediction.csv`, in file order | Batch and estimator demonstration fixture |

The CSV files may contain a UTF-8 byte-order mark. Test fixture readers must accept it without changing field names. `Test Data For Prediction.csv` must never be treated as labelled market data or used for model evaluation.

## 5. User-reasonableness acceptance rules

These rules are applied to all relevant cases in addition to the case-specific results:

- labels and units make all seven property inputs understandable without inspecting API documentation;
- the primary action and its current state are clear, and pending requests cannot accidentally create duplicate history rows;
- validation messages identify the affected field, explain how to correct it, and remain available to assistive technology;
- errors do not expose stack traces, secrets, internal hostnames, or raw database errors;
- a recoverable failure offers a clear retry, reset, or navigation path and does not discard valid user input without warning;
- exact monetary values use `USD` and two decimal places wherever required; charts do not replace exact table or text values;
- empty, loading, success, warning, and failure states are visually distinct and not communicated by colour alone;
- keyboard focus remains visible and moves predictably after navigation, validation, modal/dialog use, and destructive actions;
- mobile layouts do not hide required actions or force page-level horizontal scrolling; a wide data table may use an accessible contained scroll region; and
- reduced-motion users receive equivalent state feedback without essential animation.

## 6. Priority and release policy

| Priority | Meaning | Release policy |
| --- | --- | --- |
| P0 | PDF-required core journey or release/demo blocker | Must pass on every release candidate |
| P1 | Important reliability, accessibility, or project-defined behaviour | Must pass before interview delivery; a waiver requires a documented defect and owner |
| P2 | Extended compatibility or resilience | May be scheduled after a documented risk review |

## 7. Task 1 API end-to-end cases

### E2E-T1-001 - Discover and operate the live API through Swagger

- **Priority:** P0
- **Covers:** PDF Task 1 deliverable 3; `REQ-PRED-005`, `REQ-PRED-006`
- **Procedure:** Open the documented Swagger URL in a fresh browser, locate `predict`, `model-info`, and `health`, execute `GET /health`, and open the generated OpenAPI document.
- **Expected:** Swagger loads without browser errors; all three PDF-required operations are discoverable; health returns a successful, structured response; the OpenAPI document is valid and describes single/batch prediction, model information, health, errors, and the UUID correlation header.
- **User check:** An interviewer can discover and call the API without repository knowledge beyond the documented URL.

### E2E-T1-002 - Predict one property

- **Priority:** P0
- **Covers:** `REQ-PRED-001`, `REQ-PRED-002`, `REQ-PRED-006`, `REQ-DATA-003`, `REQ-DATA-006`
- **Procedure:** Submit Property A through Swagger, then repeat the same request directly against `POST /api/v1/predict` with the JSON properties in a different order.
- **Expected:** Each response contains exactly one finite, non-negative `USD` price with two decimal places, the active model version, and no warning; both requests return the same price; JSON property order does not change feature mapping.

### E2E-T1-003 - Predict a batch in input order

- **Priority:** P0
- **Covers:** `REQ-PRED-001`, `REQ-PRED-003`, `REQ-DATA-002`
- **Procedure:** Submit all rows from `Test Data For Prediction.csv` as one batch, preserve their file order, and then submit an empty batch and a batch of 1,001 valid records.
- **Expected:** The valid response contains one result per input in the same order; the empty and oversized batches are rejected with structured batch-condition errors and no partial results.

### E2E-T1-004 - Inspect active model information

- **Priority:** P0
- **Covers:** `REQ-PRED-004`, `REQ-TRAIN-005`, `REQ-TRAIN-009`
- **Procedure:** Call `GET /api/v1/model-info` and compare its model version to a prediction made in the same run.
- **Expected:** The response identifies the same active model version, canonical feature order, seven coefficients, intercept, MAE, RMSE, R-squared, and MAPE; units and metric precision follow the requirements; the response contains version-matched dataset and evaluation lineage rather than unrelated or manually copied values.

### E2E-T1-005 - Reject invalid API inputs and preserve advisory inputs

- **Priority:** P0
- **Covers:** `REQ-PRED-007`, `REQ-PRED-008`, `REQ-DATA-004`, `REQ-DATA-005`, `REQ-DATA-007`
- **Procedure:** Send the missing-field, invalid-integer, invalid-step, and excess-scale records, plus JSON boolean/numeric-string inputs and non-finite numbers encoded through an API-capable client. Then submit the advisory property and a hard-valid value outside the observed training range.
- **Expected:** Invalid inputs fail before inference with field-specific structured errors and no prediction. The advisory property succeeds with a structured `school_rating` warning. A value is not rejected or warned merely because it is outside the training-set minimum or maximum.

## 8. Shared portal end-to-end cases

### E2E-PORTAL-001 - Use the shared layout and navigation

- **Priority:** P0
- **Covers:** `REQ-PORTAL-001`; PDF shared layout, App Router, and consistent design requirements
- **Procedure:** Open the portal root, navigate to Estimator, then Market Analysis, and use browser Back and Forward. Repeat using keyboard-only navigation.
- **Expected:** Both applications render inside one recognisable shared layout; navigation names and active state are clear; URLs are stable and deep-linkable; Back/Forward work; focus moves to an appropriate heading or main region; design tokens and interaction patterns remain consistent.

### E2E-PORTAL-002 - Loading, error boundary, and recovery

- **Priority:** P0
- **Covers:** `REQ-PORTAL-002`, `REQ-PORTAL-003`; PDF layout-level loading/error requirements
- **Procedure:** Introduce deterministic response delay during initial route loading, then force a route data-loading failure and restore the dependency before using the offered recovery action.
- **Expected:** The shared layout remains usable; a meaningful loading state appears without layout collapse; the error is explained in user language; no stack trace is shown; retry or navigation recovers without a full environment restart.

### E2E-PORTAL-003 - Smooth transitions and coherent visual design

- **Priority:** P1
- **Covers:** PDF UI/UX requirements; `constraints.md` Section 7
- **Procedure:** Review Estimator and Market pages in every major state at desktop, tablet, and mobile sizes, with normal motion and `prefers-reduced-motion: reduce`.
- **Expected:** Layout, typography, spacing, controls, colours, and feedback are cohesive; transitions do not flash stale data or block interaction; reduced-motion mode removes non-essential animation while retaining state feedback. Record screenshots for the interview review pack.

## 9. Property Value Estimator end-to-end cases

### E2E-EST-001 - Complete a valid estimate

- **Priority:** P0
- **Covers:** `REQ-EST-001`, `REQ-EST-003`, `REQ-EST-004`, `REQ-EST-005`, `REQ-EST-008`, `REQ-DATA-003`, `REQ-DATA-006`
- **Procedure:** Enter Property A using only the UI and submit once.
- **Expected:** All seven labelled fields and units are present; a loading state prevents accidental repeat submission; the result appears in both a table and a chart; exact table/label price is fixed two-decimal USD; the result includes an immutable identifier, UTC creation time, submitted inputs, and model version; table, chart, and persisted history describe the same estimate.

### E2E-EST-002 - Correct validation errors without losing valid input

- **Priority:** P0
- **Covers:** `REQ-EST-002`, `REQ-PRED-007`, `REQ-DATA-004`; PDF client-side validation and backend error handling
- **Procedure:** Attempt submission with no values, then with the missing-field, invalid-integer, invalid-step, and excess-scale variants. Correct one field at a time and submit Property A.
- **Expected:** The client blocks known-invalid submissions; each error is associated with its field and announced; valid values remain intact; server validation remains authoritative if client checks are bypassed; correction clears the relevant error and the final valid submission succeeds once.

### E2E-EST-003 - Retain history across reload, browser restart, and service restart

- **Priority:** P0
- **Covers:** `REQ-EST-006`, `REQ-EST-008`
- **Procedure:** Create estimates for Properties A and B, reload, close and reopen a persistent browser context, restart the portal and estimator services without removing volumes, and reopen Estimator.
- **Expected:** The same history is restored at every stage with identical IDs, inputs, prices, timestamps, and model versions. Browser local/session storage is not required to reconstruct it.

### E2E-EST-004 - Paginate, delete, and clear history

- **Priority:** P1
- **Covers:** `REQ-EST-009`, `REQ-EST-010`
- **Procedure:** Create or prepare 21 estimates through the public estimator path for one browser identity, verify the first page, navigate to the next page, delete one selected estimate, cancel and then confirm clear-history.
- **Expected:** Results are newest first with 20 on the default page; cursor navigation has no duplicate or missing item; single delete removes only the selected estimate; cancelled clear changes nothing; confirmed clear removes the current client's complete history and leaves a useful empty state.

### E2E-EST-005 - Compare multiple properties side by side

- **Priority:** P0
- **Covers:** `REQ-EST-007`
- **Procedure:** Select estimates for Properties A and B from history and open comparison; add and remove a property if the UI supports more than two.
- **Expected:** The comparison shows aligned, labelled inputs and exact predicted prices side by side; differences are understandable without relying on colour; the source estimates are unchanged; removing a comparison item does not delete history.

### E2E-EST-006 - Exercise the supplied demonstration fixture

- **Priority:** P1
- **Covers:** `REQ-DATA-002`, `REQ-EST-003`, `REQ-EST-006`
- **Procedure:** Submit each row from `Test Data For Prediction.csv` through the browser-supported estimator workflow in a clean client session.
- **Expected:** Every valid row receives a prediction and ordinary history record; the UI never presents those rows as known actual prices; the market dashboard's source count remains 50.

### E2E-EST-007 - Prevent duplicate history on repeated submission

- **Priority:** P1
- **Covers:** idempotency and reliability constraints; `REQ-EST-008`
- **Procedure:** Double-click or otherwise repeat one pending Property A submission, capture its request ID, activate a different accepted model fixture, and retry the captured request with the same canonical inputs.
- **Expected:** Only one estimate ID and one history row exist for the request ID; the later retry returns the original price and model version without a new model call; the UI does not show duplicate success messages.

## 10. Property Market Analysis end-to-end cases

### E2E-MARKET-001 - Explore the default dashboard

- **Priority:** P0
- **Covers:** `REQ-MARKET-001`, `REQ-MARKET-002`, `REQ-MARKET-008`, `REQ-MARKET-009`
- **Procedure:** Open Market Analysis with no query parameters and inspect the summary, visualisations, and first table page.
- **Expected:** The page represents all 50 labelled source records; matching count, aggregates, chart totals, and pagination agree; charts have titles, legends or direct labels, accessible alternatives, and meaningful units; loading resolves without client console errors.

### E2E-MARKET-002 - Filter, sort, deep-link, and reset one consistent segment

- **Priority:** P0
- **Covers:** `REQ-MARKET-003`, `REQ-MARKET-007`, `REQ-MARKET-010`, `REQ-MARKET-011`
- **Procedure:** Apply ranges or selections for each supported field across a sequence of filter changes, sort price ascending and descending, copy/open the resulting URL in a new context, then reset all filters.
- **Expected:** Every filter affects the intended field; aggregate values, charts, table, matching count, and later exports use the same record set; sort is stable with `source_id` tie-breaking; the URL reconstructs state; reset removes all filters and returns to the 50-record default view.

### E2E-MARKET-003 - Run what-if analysis through the shared model

- **Priority:** P0
- **Covers:** `REQ-MARKET-004`, `REQ-PRED-001`, `REQ-PRED-008`
- **Procedure:** Submit Property B in the what-if tool, compare its result with a direct Task 1 prediction, submit the advisory property, and then submit an invalid record.
- **Expected:** Valid results match the same active Task 1 model version and price; the advisory case succeeds with its warning; invalid input has useful field feedback and no result; what-if activity does not create estimator history unless explicitly submitted through Estimator.

### E2E-MARKET-004 - Export the current segment as CSV

- **Priority:** P0
- **Covers:** `REQ-MARKET-005`, `REQ-MARKET-010`, `REQ-EXPORT-001`, `REQ-EXPORT-002`, `REQ-DATA-006`
- **Procedure:** Apply a filter and descending price sort, download CSV, parse it as UTF-8, and compare it row-for-row with the full filtered result set.
- **Expected:** Media type and filename are appropriate; the header contains `id`, the seven canonical inputs, and `price`; rows exactly match the active filter and sort, including rows beyond the visible page; each price has two decimals; no estimate-history or hidden client data appears.

### E2E-MARKET-005 - Export a readable, complete PDF report

- **Priority:** P0
- **Covers:** `REQ-MARKET-006`, `REQ-MARKET-010`, `REQ-EXPORT-003`, `REQ-EXPORT-004`, `REQ-EXPORT-005`
- **Procedure:** Apply a segment and sort, run the advisory what-if case, download PDF, verify its text and page structure programmatically, then render every page for visual inspection.
- **Expected:** The file is a valid A4 landscape PDF with title, UTC generation time, active filters, matching count, aggregates, charts, filtered/sorted table, and the complete what-if inputs, fixed two-decimal USD result, model version, and warning; multi-page output has page numbers and repeated table headers; nothing is clipped, overlapped, or unreadable.

### E2E-MARKET-006 - Preserve useful analysis when the model is unavailable

- **Priority:** P1
- **Covers:** `REQ-PORTAL-003`, `REQ-MARKET-001`, `REQ-MARKET-004`; error-state constraints
- **Procedure:** Load a successful market analysis, stop `model-service`, rerun what-if, inspect existing market data, restore the model service, and retry.
- **Expected:** What-if reports a clear recoverable dependency error; dataset exploration, filters, aggregates, tables, and non-what-if exports remain usable; no fabricated prediction appears; retry succeeds after recovery.

## 11. Cross-cutting usability, accessibility, and isolation cases

### E2E-UX-001 - Responsive operation at supported viewports

- **Priority:** P0
- **Covers:** PDF responsive-layout and responsive-table requirements
- **Procedure:** Execute portal navigation, one estimate, comparison, dashboard filtering, what-if, and export at the desktop, tablet, and mobile viewports in Section 2.
- **Expected:** All information and actions remain reachable; forms and charts resize without overlap; text is readable; touch targets are usable; any table overflow is contained, labelled, and keyboard-scrollable; there is no page-level horizontal overflow.

### E2E-UX-002 - WCAG 2.2 AA release checks

- **Priority:** P0
- **Covers:** PDF accessibility requirement; `constraints.md` Section 7
- **Procedure:** Run axe-core on each primary page and state; complete key journeys with keyboard only; check visible focus, skip/main navigation, headings, landmarks, labels, error association, status announcements, contrast, zoom/reflow, reduced motion, chart alternatives, and a representative screen-reader flow.
- **Expected:** No serious or critical axe violation and no applicable WCAG 2.2 Level A/AA failure; dynamic results and errors are announced; destructive controls have clear names and confirmation; all functionality is operable without a pointer.

### E2E-SEC-001 - Isolate anonymous histories between browsers

- **Priority:** P1
- **Covers:** history-isolation and client-cookie constraints
- **Procedure:** In Browser A create Property A; in an unrelated fresh Browser B create Property B; read and delete history in both; attempt to add a client/history owner through URL or JSON fields.
- **Expected:** Each browser sees and can delete only its own history; a client-supplied owner is ignored or rejected; opaque cookie values reveal no user/property data; clearing Browser A's cookie removes its ability to locate old rows but does not expose them to Browser B.

### E2E-FAIL-001 - Recover from model failure without false success

- **Priority:** P1
- **Covers:** `REQ-PORTAL-003`, `REQ-PRED-009`, `REQ-EST-008`; error-handling constraints
- **Procedure:** Use a controlled model-service test profile to produce a timeout, structured dependency error, and out-of-domain raw model result during estimator and batch journeys.
- **Expected:** The UI and API show structured, non-sensitive errors with correlation IDs; no failed estimate or partial batch result is persisted or displayed as success; retry after restoration creates exactly one valid result.

### E2E-OBS-001 - Trace one request across the system

- **Priority:** P2
- **Covers:** correlation and observability constraints
- **Procedure:** Make one estimate with a known valid UUID correlation header through an API-capable client and one browser estimate with an auto-generated ID; inspect response headers, structured logs, and local metrics.
- **Expected:** The canonical ID is returned and propagated through portal, estimator, model, and error logs; the browser request receives a generated UUID; request, latency, model-call, error, cache, prediction, and export signals are locally inspectable; property values and secrets are not leaked into inappropriate logs.

## 12. Clean demonstration acceptance

### E2E-DEMO-001 - Reproduce the interview demonstration from repository instructions

- **Priority:** P0
- **Covers:** both PDF deliverables; containerisation and live-demo requirements
- **Procedure:** On an interview-equivalent machine, follow only the repository README to build, migrate, explicitly train/publish if needed, start the system, open Swagger, run single and batch predictions, and demonstrate both portal applications. Stop and restart the application stack without deleting persistent volumes.
- **Expected:** The documented commands are sufficient; every deployable application runs in its own container; normal startup/restart does not retrain or replace the model; the Swagger/API and portal are reachable at documented URLs; all P0 journeys remain demonstrable after restart; source and image version information is traceable to the Git commit.

## 13. Exit criteria and evidence

Release E2E testing is complete when:

- every P0 case passes in Chromium and the cross-browser smoke subset;
- every P1 case passes or has an explicit, reviewed waiver;
- there are no unresolved serious/critical accessibility violations;
- the PDF export has been programmatically inspected and visually rendered;
- the clean demonstration case passes from repository instructions; and
- the test report retains browser/version data, Git commit, Compose image identifiers, active dataset/model versions, results, traces for failures, and links to defects.

The full `REQ-*` mapping and PDF-clause mapping are maintained in [`integration-test-plan.md`](integration-test-plan.md) so that coverage is reviewed in one authoritative matrix.

Results for the current release candidate are recorded in [`test-report.md`](test-report.md).

# Task 2 frontend design

## 1. Purpose

Task 2 is a unified Next.js portal with two independent frontend applications:

1. **App 1 - Property Value Estimator**, backed by Python.
2. **App 2 - Property Market Analysis**, backed by Java.

Both applications use the machine-learning model from Task 1. They share navigation, layout, design tokens, accessibility standards, loading conventions, and error presentation. Their business state and backend integrations remain separate.

This is a greenfield frontend design. It defines the product behaviour and the intended Next.js architecture without assuming an existing frontend implementation.

## 2. Sources and design authority

The design uses these sources, in order:

1. `requirements/Interview Tasks Fullstack.pdf`, pages 2-3, defines the Task 2 product and technical requirements.
2. `docs/openapi/market-service-v2.yaml` defines the App 2 API contract.
3. Motion's official React documentation defines the animation APIs and reduced-motion behaviour.

The PDF's numbered entries “2 - App 1” and “3 - App 2” are sections inside Task 2. They are not separate tasks.

When prose and OpenAPI differ on App 2 request fields, response fields, limits, or status codes, OpenAPI takes precedence. The Python Estimator API is not specified in the supplied market contract; this document therefore describes the capabilities that its frontend contract must provide rather than inventing a second OpenAPI specification.

## 3. Product structure

```text
Housing Intelligence Portal
├── /estimator     App 1 - Property Value Estimator
│   ├── Property input
│   ├── Prediction result
│   ├── Estimate history
│   └── Property comparison
└── /market        App 2 - Property Market Analysis
    ├── Market overview
    ├── Segment filters
    ├── Visual analysis
    ├── Property explorer
    ├── What-if analysis
    └── Export centre
```

The root route redirects to `/estimator`. A persistent portal layout contains the product identity, links to both applications, a skip link, a main landmark, and a small model-output disclaimer. Navigation uses real links so that URLs remain bookmarkable and browser history works normally.

## 4. App 1 - Property Value Estimator

### 4.1 Functional scope

| ID | Function | Product behaviour |
| --- | --- | --- |
| E1 | Property input form | Collect every model field: square footage, bedrooms, bathrooms, year built, lot size, distance to city centre, and school rating. Every control has a visible label, unit, example, and constraint. |
| E2 | Client-side validation | Validate required fields, number syntax, whole-number fields, decimal precision, and permitted ranges before sending a request. Show an error summary and field-level messages. Server validation remains authoritative. |
| E3 | Prediction summary | Present predicted price, currency, model version, and submission time. State clearly that the result is an estimate rather than an appraisal or financial recommendation. |
| E4 | Tabular result | Show the seven submitted values and prediction metadata in an exact, readable table. This is the accessible source of truth for the result. |
| E5 | Result visualisation | Show predicted price in a compact bar chart. Once history exists, the chart can compare the current estimate with selected previous estimates. It never replaces the table. |
| E6 | Estimate history | Show previous estimates, newest first, with enough property details to identify each entry. History is loaded with the first server render and supports bounded pagination. |
| E7 | Side-by-side comparison | Let the user select two to four history entries and compare their inputs and predicted prices in aligned columns and charts. |
| E8 | Reuse as a scenario | A “Use as new scenario” action copies a historical record into the form without submitting it. The user can change one or more values and request a new estimate. |
| E9 | Example presets | Optional presets, such as “City apartment” and “Family house”, populate the form for a live demonstration. They are labelled examples and never submit automatically. |

E8 and E9 are additional frontend features. They use existing model fields and require no new analytical capability.

### 4.2 Python frontend contract needs

The Estimator frontend needs the Python backend to provide these capabilities through its own reviewed OpenAPI contract:

- create one estimate from all model fields;
- return predicted price, currency, model version, canonical inputs, and a stable estimate identifier;
- list the current visitor's previous estimates using bounded pagination; and
- return structured validation and service errors.

History ownership may be anonymous for an interview demonstration, but it must be isolated by a secure first-party identifier. The browser must not be allowed to choose another visitor's history owner in a request body or URL.

### 4.3 Screen composition

Desktop uses a two-column top section: form on the left and current result on the right. History and comparison sit below. Tablet can keep two columns when space permits. Mobile uses one column in task order: form, result, history, comparison.

The result panel reserves space before a prediction exists. This prevents a large layout jump after submission. History remains visible during a new estimate request.

### 4.4 Black-box acceptance behaviour

| Scenario | Observable result |
| --- | --- |
| The user opens `/estimator` | The response already includes recent history or a clear empty-history state. The browser does not need a second request to discover the initial state after hydration. |
| The user submits an empty or invalid form | No prediction request is sent. A focusable error summary appears, focus moves to it or the first invalid control, and each field explains how to correct the value. |
| The user submits valid details | The submit button shows progress and prevents duplicate activation. Other page content remains usable. |
| Prediction succeeds | The exact table and chart update from the same response. Price, currency, model version, and all submitted values agree. The new estimate appears at the start of history. |
| Prediction fails | The entered values remain in the form. The user sees a recoverable message and correlation ID. No fabricated result or partial history row appears. |
| The user selects history entries | Selecting two entries reveals the comparison. A third and fourth may be added. A fifth selection is blocked with a clear message. |
| The user removes a selected entry | The comparison updates without re-running the model. Focus remains on a sensible nearby control. |
| The user selects “Use as new scenario” | The form is populated with that record, the page moves focus to the form heading, and nothing is submitted until the user confirms. |
| More history is available | “Load more” appends records without removing existing rows. The action disappears or becomes disabled when the final page is reached. |
| The model service is unavailable | Existing history and comparison remain readable. Only estimate creation reports the dependency failure. |

## 5. App 2 - Property Market Analysis

### 5.1 API capabilities

| Capability | API operation | Frontend use |
| --- | --- | --- |
| Filtered properties | `GET /api/v2/market/properties` | Responsive property table, single-field sorting, and keyset pagination |
| Aggregate statistics | `POST /api/v2/market/statistics/query` | Metric cards, grouped charts, and market context for what-if results |
| Create export | `POST /api/v2/market/exports` | Start a durable CSV or PDF export with an Idempotency-Key |
| Export status | `GET /api/v2/market/exports/{export_id}` | Track Queued, Running, Succeeded, Failed, Cancelled, and Expired jobs |
| Cancel export | `DELETE /api/v2/market/exports/{export_id}` | Cancel queued/running work or remove completed temporary content |
| Download export | `GET /api/v2/market/exports/{export_id}/content` | Stream a completed CSV or PDF file |
| What-if prediction | `POST /api/v2/market/what-if` | Predict one hypothetical property's price using the shared model |

### 5.2 Functional scope

| ID | Function | Product behaviour |
| --- | --- | --- |
| M1 | Market overview | Show matching property count, average price, minimum and maximum price, average square footage, and price standard deviation. Request these metrics together. |
| M2 | Market visualisations | Show average price by bedroom count and property count by school-rating band. Charts use grouped statistics over the full filtered scope, not the current table page. |
| M3 | Segment filters | Provide minimum/maximum filters for price, square footage, bedrooms, bathrooms, year built, lot size, distance to city centre, and school rating. Filters are applied explicitly rather than on every keystroke. |
| M4 | Active-filter summary | Show applied filters as removable chips and provide a single Reset action. The summary makes the current segment understandable without reopening the filter panel. |
| M5 | Property explorer | Show property records in a responsive table with one active sort field. Use Next and Back controls backed by opaque cursors. Do not display arbitrary page numbers. |
| M6 | What-if analysis | Accept all seven model fields and show predicted price, model version, and advisory warnings. What-if state is independent of the dashboard read state. |
| M7 | Market context for what-if | Compare the predicted value with the filtered segment's average, minimum, and maximum price. Label the comparison as context, not valuation confidence. |
| M8 | Export centre | Create CSV or PDF jobs, show live job status, cancel eligible jobs, download completed files, and regenerate expired files. The dashboard remains usable while an export runs. |
| M9 | Live-data identity | Show the business data revision in technical details and ensure table, statistics, charts, and exports are revision-consistent. Display the query fingerprint only in technical details. |
| M10 | Guided insight view | Let the user choose one approved grouping dimension and one approved metric for an additional chart. Options come from the OpenAPI allow-list; high-cardinality dimensions require fixed-width buckets. |

M4, M7, M9, and M10 extend the required dashboard without exceeding the v2 contract. They do not introduce free-form queries or client-side full-dataset calculations.

### 5.3 Contract-driven UI rules

#### Dataset consistency

Market requests never send a dataset ID. Each properties/statistics response returns a server-owned
`data_revision`. The server loader fetches the table and chart/statistics calls in parallel and
accepts the assembled page only when all revisions match; otherwise it retries. Cursors bind to the
revision that created them, while filter and sort changes clear cursor state.

#### Decimal values

Money and decimal response fields remain strings in the transport and domain layers. Exact values are never converted to JavaScript floating point and then sent back to the API. A chart adapter may convert bounded display values to numbers, while the original strings remain available for labels, tables, and requests.

#### Sorting and pagination

The table supports one sort expression from the API allow-list. Changing filter, sort, or limit
clears the cursor. Cursors are opaque, include a server-verified business revision, and must not be
parsed or edited. Back navigation uses browser history or a small stack of previously received
cursors.

#### Statistics

The overview sends several metrics in one statistics request. Charts use separate grouped requests
and the server loader compares every returned revision. The frontend honours the limits of 20
metrics and 200 groups. When an aggregate is null, the UI displays an em dash; COUNT still displays
zero.

#### Exports

Export creation returns `202 Accepted`, not a file. The client uses `Location` and `Retry-After`, stops polling in terminal states, pauses polling while the document is hidden, and aborts polling when the export item unmounts. Download uses the streaming content route rather than buffering the whole file as a Blob. PDF details may be bounded to 5,000 rows; the UI states clearly when details are truncated. CSV represents the complete matching selection.

### 5.4 Screen composition

The desktop dashboard uses this order:

1. page title, live-data revision details, and export action;
2. compact metric cards;
3. filter panel and active-filter summary;
4. two primary charts;
5. property table;
6. a sticky what-if panel; and
7. export centre, shown as a non-blocking drawer or panel.

On mobile, this becomes one column. Filters use an accessible disclosure. What-if follows the table rather than covering it. The table sits inside a labelled horizontal scroll region; no required column disappears solely because the screen is narrow.

### 5.5 Black-box acceptance behaviour

| Scenario | Observable result |
| --- | --- |
| The user opens `/market` | Initial properties and core market data are loaded by Server Components. The page shows a route-level skeleton while critical data is pending. |
| Parallel market responses have one revision | Render the page only when table, statistics, and charts share that exact business revision. |
| The user edits filters | No request is sent for each keystroke. Apply commits valid filters to the URL. Reset removes them. |
| The user applies filters | The existing dashboard remains readable but is marked updating. The cursor resets, then cards, charts, and the table update to the same scope. |
| A minimum is greater than its maximum | Client validation blocks Apply and identifies the range. A backend 422 maps to the same controls. |
| No properties match | Count shows 0, nullable metrics show an em dash, charts show an empty state, and the table says no records match. This is not presented as a system error. |
| The user sorts a column | That column toggles between ascending and descending, `aria-sort` changes, and pagination returns to the first cursor. |
| The user selects Next | The client uses `next_cursor`. Back returns to a previous cursor. There is no page-number input or unsupported jump-to-page action. |
| A cursor no longer matches the query | The page retains valid filters and sort, removes the cursor, returns to the first result set, and explains the recovery. |
| The user runs what-if | Only the what-if panel becomes busy. A successful result includes warnings and market context. A 503 leaves the rest of the dashboard intact. |
| The user creates an export | A job item appears immediately with Queued or Running status. The rest of the page remains interactive. |
| An export succeeds | The item shows file type, row count, file size, expiry time, and a Download action. PDF truncation is stated explicitly. |
| The user cancels an export | The item enters a cancellation state, disables duplicate cancellation, and ends as Cancelled or the server-reported terminal state. |
| Export content has expired | The item shows Expired and offers Regenerate. It does not repeatedly request the expired content URL. |
| The API returns 429 | The relevant control shows the rate-limit message and a Retry-After countdown. Automatic retries stop during that period. |
| The API returns a stale-revision/query conflict | The page discards stale cursor state, preserves valid filters, reloads current business data, and tells the user what changed. |

## 6. Shared UX and design system

### 6.1 Visual system

Tailwind CSS supplies semantic design tokens for colour, typography, spacing, borders, shadows, focus rings, and breakpoints. Shared primitives include:

- `Button`, `IconButton`, and `LinkButton`;
- `Field`, `NumberField`, `Select`, and `ErrorSummary`;
- `Panel`, `MetricCard`, `StatusBadge`, and `EmptyState`;
- `DataTable`, `SortButton`, and `PaginationControls`;
- `Dialog`, `Disclosure`, `ToastRegion`, and `Skeleton`.

Feature components compose these primitives. They do not redefine colours, focus styles, or status semantics locally.

### 6.2 Accessibility baseline

The target is WCAG 2.2 AA.

- Every control has a visible label and keyboard focus.
- Colour is never the only indication of error, selection, sort direction, warning, or job status.
- Field errors use `aria-invalid` and `aria-describedby`; the error summary is focusable.
- Sortable table headers are buttons and expose `aria-sort`.
- Charts have titles, descriptions, and an equivalent table or textual summary.
- Pending regions use `aria-busy`; concise `aria-live="polite"` messages announce completed asynchronous actions.
- Loading skeletons expose one status per region, not one status per placeholder.
- Dialogs trap focus, close with Escape, and return focus to their trigger.
- Touch targets are at least 44 by 44 CSS pixels where the component layout allows it.
- The portal works at 200% zoom without losing content or actions.

### 6.3 Loading and errors

There are three loading levels:

1. Route loading for the initial Server Component render.
2. Section loading for streamed or independently recoverable data such as charts.
3. Control loading for estimate, what-if, filter navigation, and export actions.

A page-level error boundary handles a failed critical read. Local errors handle form submissions, charts, what-if, and exports so one failed feature does not erase unrelated content. Error messages include a recovery action and place the correlation ID under technical details.

## 7. Next.js architecture

### 7.1 App Router structure

The portal uses App Router route segments, layouts, loading files, and error boundaries:

```text
src/app/
├── layout.tsx
├── template.tsx
├── page.tsx
├── loading.tsx
├── error.tsx
├── (portal)/
│   ├── layout.tsx
│   ├── estimator/
│   │   ├── page.tsx
│   │   ├── loading.tsx
│   │   └── error.tsx
│   └── market/
│       ├── page.tsx
│       ├── loading.tsx
│       └── error.tsx
└── api/
    ├── estimator/...
    └── market/...
```

The root and portal layouts are Server Components. `template.tsx` is a small Client Component used only for the page-entry transition.

### 7.2 Server and Client Component boundaries

Use Server Components for:

- page composition and metadata;
- initial estimate history;
- initial property records and market statistics;
- parsing and validating committed URL search parameters;
- backend calls that need server-only configuration; and
- converting API responses into serialisable page models.

Use Client Components only for:

- form controls and client validation;
- table sorting/filter controls and cursor navigation;
- charts that need browser canvas APIs;
- estimate selection and comparison interaction;
- what-if and export mutations;
- dialogs, disclosures, announcements, and Motion transitions.

A Client Component boundary should be placed around the smallest interactive area that needs it. Presentational markup that does not use browser APIs remains a Server Component where practical.

### 7.3 Initial data loading

`/estimator/page.tsx` loads the first history page on the server and passes a serialisable page model to the interactive estimator screen.

`/market/page.tsx` performs a two-stage server load:

1. request the first property page and statistics/chart data for the current business revision;
2. request overview and grouped statistics in parallel, pinned to that version.

The table can render as soon as the property page is available. Independent chart sections may stream behind `Suspense` after the version is known. The browser must not repeat these initial requests in `useEffect`.

Server Components call server-only data-access functions directly for the first render. They do not make an HTTP request to the portal's own Route Handlers. Once the interactive screen is hydrated, browser-originated reads and mutations use same-origin Route Handlers as a thin backend-for-frontend boundary. Filter, sort, and cursor changes update the dashboard model in place and synchronise the URL with the History API; they do not start another Server Component render.

### 7.4 Data-fetching policy

| Data | Policy |
| --- | --- |
| Visitor-specific estimate history | `no-store`; private data must not be shared across visitors |
| Market business requests | `no-store`; mutable business data may change |
| Dataset-pinned market reads | Initial RSC read or subsequent same-origin client read; request-scoped memoisation only, with persistent portal caching allowed only if its key space and invalidation policy are measured and bounded |
| Statistics | Batch metrics where possible; parallelise independent grouped requests and verify matching revisions |
| What-if and estimate creation | Explicit user mutation; no cache |
| Export job status | Poll according to `Retry-After`; no cache |
| Export content | Stream through the BFF while preserving content type, disposition, length, digest, and ETag headers |

The Java service owns its bounded business-query cache. The portal does not add an unbounded second cache for arbitrary filter combinations.

## 8. Smart and dummy component model

The design uses one smart container per interactive screen and small dummy components for presentation.

### 8.1 Smart container responsibilities

A smart container may:

- own client-side interaction state or a reducer;
- call browser API clients for post-hydration reads and mutations;
- commit filters and cursors with the History API without starting an RSC navigation;
- coordinate optimistic state, retries, and focus;
- convert domain objects to view props; and
- pass data and event callbacks to dummy components.

It does not fetch the initial page data. That belongs to the route's Server Component.

### 8.2 Dummy component responsibilities

A dummy component:

- receives data, status, and callbacks through typed props;
- renders one UI responsibility;
- does not import `next/navigation`, API clients, server-only modules, or feature stores;
- does not parse wire-format errors, decimals, or cursors; and
- can be rendered in a component test with fixed props.

“Dummy” describes the component's dependency boundary, not a lack of behaviour. A controlled form or sortable table may handle DOM events while all business decisions remain in its parent.

### 8.3 Composition example

```tsx
// Server Component
export default async function EstimatorPage() {
  const initialModel = await getEstimatorPageModel();
  return <EstimatorScreen initialModel={initialModel} />;
}

// Client smart container
function EstimatorScreen({ initialModel }: Props) {
  const [state, dispatch] = useReducer(estimatorReducer, initialModel);

  return (
    <EstimatorView
      model={toEstimatorViewModel(state)}
      onSubmit={submitEstimate}
      onSelectForComparison={selectForComparison}
      onReuseScenario={reuseScenario}
    />
  );
}
```

The same split applies to the Market screen: the Server Component supplies the initial page model, the smart container coordinates interaction, and dummy components render filters, cards, charts, table, what-if, and exports.

## 9. Client state and custom hooks

The portal does not need a global state library. URL state, Server Component data, and feature-level reducers cover the required scope.

| State | Owner |
| --- | --- |
| Committed market filters, sort, limit, cursor | URL search parameters |
| Draft form and filter values | Local controlled form state |
| Initial history and market data | Server Component page model |
| Post-hydration market records, aggregates, charts, and committed query | Market smart-container state, refreshed through the same-origin BFF |
| Estimate comparison selection | Estimator smart-container reducer |
| What-if result and export jobs | Market smart-container reducer |
| Transient disclosure/dialog state | Local component state |

The PDF requires custom hooks for shared functionality. Hooks are therefore limited to behaviour reused by more than one feature; they do not become feature-wide service objects.

| Shared hook | Purpose | Used by |
| --- | --- | --- |
| `useAsyncAction` | Normalise pending/success/error state, abort handling, and duplicate-action prevention for one user action | Estimate, what-if, export create/cancel |
| `useFieldErrors` | Map structured API field violations to controls and a focusable error summary | Estimator form, what-if form, market filters |
| `useLiveAnnouncement` | Publish concise accessible messages after asynchronous completion | Estimate, filter update, pagination, export |
| `useDisclosureState` | Provide consistent open/close, trigger linkage, Escape handling, and focus return | Mobile filters, technical details, export details |

Feature orchestration stays in `EstimatorScreen` and `MarketScreen`. Shared hooks never act as feature-wide service objects.

## 10. API boundary and error model

Browser calls go through same-origin Route Handlers. These handlers:

- enforce same-origin rules for state-changing requests;
- allow-list query parameters and forwarded headers;
- enforce request-size and timeout limits;
- forward or create `X-Correlation-ID`;
- forward `Idempotency-Key`, `Location`, and `Retry-After` where required;
- validate path identifiers before forwarding; and
- preserve streaming download headers.

Route Handlers do not calculate statistics, interpret cursors, generate files, or apply business rules.

The frontend uses one error shape:

```ts
interface FrontendApiError {
  status: number;
  code: string;
  message: string;
  fields: Array<{ field: string; message: string }>;
  correlationId?: string;
  retryAfterSeconds?: number;
}
```

App 2 TypeScript wire types are generated from `market-service-v2.yaml` and are never edited by hand. Mappers convert snake_case wire values and decimal strings into explicit domain/view models. Generated contract types do not leak directly into dummy components.

## 11. Motion design

Motion adds orientation and continuity, not decoration. It is used only in Client Components that already need browser interaction.

### 11.1 Global policy

```tsx
"use client";

import { LazyMotion, MotionConfig, domAnimation } from "motion/react";

export function MotionProvider({ children }: { children: React.ReactNode }) {
  return (
    <MotionConfig reducedMotion="user" transition={{ duration: 0.18 }}>
      <LazyMotion features={domAnimation} strict>
        {children}
      </LazyMotion>
    </MotionConfig>
  );
}
```

Components under strict `LazyMotion` use `m` components. Default transitions last 160-220ms and use a simple ease-out curve. `reducedMotion="user"` respects the operating-system preference; large transform and layout animations disappear in reduced mode.

### 11.2 Approved transitions

| Interaction | Transition |
| --- | --- |
| Route entry | Short opacity fade in `app/template.tsx`; no delayed exit animation |
| Active navigation item | Small shared-layout underline or background |
| Prediction and what-if result | `AnimatePresence` opacity plus at most 4px vertical movement |
| Comparison cards and active-filter chips | `layout` transition for small-list insertion, removal, and reordering |
| Export status | Subtle colour/opacity transition; state is always written as text |
| Error and success notice | Short opacity transition without shaking or flashing |

Do not animate every row in a data table, stagger loading content, use parallax, move the full page horizontally, or run decorative loops. Chart animation is disabled when reduced motion is requested. Animation never delays focus, screen-reader announcements, navigation, or downloads.

References: [Motion for React](https://motion.dev/docs/react), [MotionConfig](https://www.motion.dev/docs/react-motion-config), [accessibility](https://motion.dev/docs/react-accessibility), [layout animations](https://motion.dev/docs/react-layout-animations), and [AnimatePresence](https://motion.dev/docs/react-animate-presence).

## 12. Proposed code organisation

```text
web-portal/
├── src/
│   ├── app/
│   │   ├── layout.tsx
│   │   ├── template.tsx
│   │   ├── page.tsx
│   │   ├── loading.tsx
│   │   ├── error.tsx
│   │   ├── (portal)/
│   │   │   ├── layout.tsx
│   │   │   ├── estimator/
│   │   │   │   ├── page.tsx
│   │   │   │   ├── loading.tsx
│   │   │   │   └── error.tsx
│   │   │   └── market/
│   │   │       ├── page.tsx
│   │   │       ├── loading.tsx
│   │   │       └── error.tsx
│   │   └── api/
│   │       ├── estimator/...
│   │       └── market/
│   │           ├── properties/route.ts
│   │           ├── statistics/route.ts
│   │           ├── what-if/route.ts
│   │           └── exports/...
│   ├── features/
│   │   ├── estimator/
│   │   │   ├── server/get-estimator-page-model.ts
│   │   │   ├── containers/estimator-screen.tsx
│   │   │   ├── components/
│   │   │   │   ├── estimator-view.tsx
│   │   │   │   ├── property-form.tsx
│   │   │   │   ├── prediction-result.tsx
│   │   │   │   ├── prediction-chart.tsx
│   │   │   │   ├── estimate-history.tsx
│   │   │   │   └── estimate-comparison.tsx
│   │   │   ├── model/
│   │   │   │   ├── estimator-reducer.ts
│   │   │   │   ├── estimator-schema.ts
│   │   │   │   └── estimator-view-model.ts
│   │   │   └── api/estimator-client.ts
│   │   └── market/
│   │       ├── server/get-market-page-model.ts
│   │       ├── containers/market-screen.tsx
│   │       ├── components/
│   │       │   ├── market-view.tsx
│   │       │   ├── market-filters.tsx
│   │       │   ├── active-filter-summary.tsx
│   │       │   ├── metric-grid.tsx
│   │       │   ├── market-charts.tsx
│   │       │   ├── property-table.tsx
│   │       │   ├── what-if-panel.tsx
│   │       │   └── export-centre.tsx
│   │       ├── model/
│   │       │   ├── market-reducer.ts
│   │       │   ├── market-query.ts
│   │       │   ├── statistics-presets.ts
│   │       │   └── market-view-model.ts
│   │       └── api/market-client.ts
│   ├── shared/
│   │   ├── api/
│   │   │   ├── api-error.ts
│   │   │   └── request.ts
│   │   ├── contracts/market-v2.generated.ts
│   │   ├── hooks/
│   │   │   ├── use-async-action.ts
│   │   │   ├── use-field-errors.ts
│   │   │   ├── use-live-announcement.ts
│   │   │   └── use-disclosure-state.ts
│   │   ├── motion/
│   │   │   ├── motion-provider.tsx
│   │   │   └── transitions.ts
│   │   ├── ui/
│   │   ├── validation/
│   │   └── formatting/
│   └── styles/globals.css
├── tests/
│   ├── contract/
│   ├── unit/
│   ├── component/
│   └── e2e/
└── package.json
```

Dependency direction is one way:

```text
route Server Component -> feature server data access -> backend
route Server Component -> feature smart container -> dummy components
smart container -> feature API client -> same-origin Route Handler -> server data access -> backend
feature components -> shared UI, hooks, motion, validation, formatting
```

`shared` must not import from `features`. Estimator and Market features must not import each other's internal containers, reducers, or API clients. Cross-application UI belongs in `shared/ui`; cross-application behaviour belongs in `shared/hooks` only when it is genuinely reused.

## 13. Verification strategy

### 13.1 Automated tests

| Layer | Coverage |
| --- | --- |
| Contract | Generated App 2 types, BFF headers, decimal strings, error envelope, export status, and streaming headers |
| Unit | Validation schemas, reducers, URL-to-query mapping, statistics presets, decimal formatting, and shared hooks |
| Component | Dummy components in loading, empty, error, and success states; keyboard and ARIA behaviour |
| Integration | RSC data loading, revision consistency/retry, partial market failures, and BFF forwarding |
| E2E | Both App 1 and App 2 user journeys across desktop and mobile viewports |
| Accessibility | axe checks plus manual keyboard, screen-reader copy, reduced-motion, and 200% zoom checks |

### 13.2 Required end-to-end scenarios

1. Estimator initial history is present in the server-rendered result with no duplicate initial browser fetch.
2. Invalid estimator input is blocked and fully described; valid input produces matching table and chart output.
3. History selection creates a two-to-four property comparison without another prediction call.
4. Market properties, statistics, charts, and exports are bound to one live business revision.
5. Filter and sort changes reset the cursor and update the URL.
6. Next/Back cursor navigation has no duplicated or missing records.
7. Global metrics and charts are never calculated from the current property page.
8. A what-if 503 leaves the rest of the market dashboard usable.
9. Export polling honours `Retry-After`, stops in terminal states, and supports cancel, download, expiry, and regeneration.
10. Status 422 maps to fields, 429 presents a cooldown, and 409 recovers without silently changing the submitted filter scope.
11. Reduced-motion mode removes large movement while preserving every state change.
12. Both applications remain operable at mobile, tablet, and desktop sizes.

## 14. Requirement traceability

| PDF requirement | Design coverage |
| --- | --- |
| Shared layout and navigation | Sections 3, 6, and 7 |
| App Router | Section 7.1 |
| Consistent design system | Section 6 |
| Layout loading and errors | Sections 6.3 and 7.1 |
| App 1 form and validation | Sections 4.1 and 4.4 |
| App 1 table and chart result | Sections 4.1 and 4.4 |
| App 1 history and comparison | Sections 4.1 and 4.4 |
| App 2 dashboard and visualisations | Sections 5.1-5.5 |
| App 2 filters and responsive table | Sections 5.2-5.5 |
| App 2 what-if | Sections 5.1, 5.2, and 5.5 |
| App 2 CSV/PDF exports | Sections 5.1-5.5 |
| Server and Client Components | Section 7.2 |
| RSC initial data | Section 7.3 |
| Data-fetching strategy | Section 7.4 |
| Custom hooks for shared behaviour | Section 9 |
| Tailwind responsive layouts | Sections 5.4 and 6 |
| WCAG components | Section 6.2 |
| Loading states and error boundaries | Section 6.3 |
| Smooth transitions | Section 11 |
| Client state and form state | Sections 8 and 9 |
| API communication and errors | Section 10 |
| Next.js code organisation | Sections 7, 8, and 12 |

## 15. Definition of done

The frontend is complete when:

- every App 1 and App 2 function in the PDF is available and passes the black-box behaviour in sections 4.4 and 5.5;
- the App 2 UI obeys v2 revision, decimal, filter, sort, cursor, statistics, what-if, and export constraints;
- initial business data is loaded by Server Components, with Client Components limited to interactive islands;
- smart containers own orchestration and dummy components remain transport- and router-independent;
- shared hooks contain only cross-feature browser behaviour;
- responsive Tailwind layouts and WCAG 2.2 AA semantics cover both applications;
- Motion transitions are short, optional, and reduced-motion aware; and
- contract, unit, component, integration, E2E, and accessibility checks pass.

# Product requirements

## Purpose

The product must let users estimate individual property values and explore housing market data from one portal. This document describes what the product must do. The runtime component design is defined in [`architecture.md`](architecture.md), technology and quality rules in [`constraints.md`](constraints.md), and the durable business data model in [`data-schema.md`](data-schema.md).

User-visible acceptance is defined in [`e2e-test-plan.md`](e2e-test-plan.md). Service, data, model, and deployment verification plus the complete requirement traceability matrix are defined in [`integration-test-plan.md`](integration-test-plan.md).

[`Interview Tasks Fullstack.pdf`](../requirements/Interview%20Tasks%20Fullstack.pdf) is authoritative for the interview task's source requirements. An [English Markdown transcription](../requirements/Interview%20Tasks%20Fullstack.md) is available for convenience. Requirements explicitly described as project decisions or data assumptions supplement the PDF and must not be presented as PDF-mandated scope.

Requirement provenance is expressed on each requirement or for an explicitly stated requirement range using the following categories:

- **PDF:** directly required by the source PDF;
- **Mixed:** contains a PDF-required capability plus project-defined acceptance or contract details;
- **Project decision:** added to make the implementation precise, reproducible, secure, or demonstrable, but not required by the PDF;
- **Data assumption:** inferred from the supplied CSV files because the PDF does not define the relevant domain convention.

When a project decision or data assumption conflicts with a PDF requirement, the PDF requirement takes precedence.

## Product scope

The product consists of:

1. A reproducible workflow for training, evaluating, and testing the housing price model.
2. A housing price prediction service.
3. A unified portal containing:
   - a Property Value Estimator;
   - a Property Market Analysis application.

Both applications must use the same housing price prediction capability.

## Shared portal requirements

- **REQ-PORTAL-001 [PDF]:** Users must be able to move between the Property Value Estimator and Property Market Analysis applications through shared navigation.
- **REQ-PORTAL-002 [PDF]:** The portal must show clear loading feedback while data or results are pending.
- **REQ-PORTAL-003 [PDF]:** The portal must show useful error messages when data cannot be loaded or an operation fails.

## Housing price prediction requirements

- **REQ-PRED-001 [Mixed]:** Every prediction record must provide all seven model input fields. Requiring all model fields is from the PDF; the canonical names below come from the supplied datasets:
  - `square_footage`
  - `bedrooms`
  - `bathrooms`
  - `year_built`
  - `lot_size`
  - `distance_to_city_center`
  - `school_rating`
- **REQ-PRED-002 [Mixed]:** A valid single-property request whose raw model output satisfies `REQ-PRED-009` must return exactly one predicted price. Single prediction is required by the PDF; USD and fixed two-decimal representation are project data-contract decisions.
- **REQ-PRED-003 [Mixed]:** A batch request must contain between 1 and 1,000 property records using the same seven-field schema. A successful response must contain exactly one prediction for each input record and preserve input order; the atomic model-output failure rule in `REQ-PRED-009` applies to the batch as a whole. Batch prediction is required by the PDF; the limit, ordering, and atomicity rules are project decisions.
- **REQ-PRED-004 [Mixed]:** Model information must be available and must include the model version, feature names in canonical order, one coefficient per feature, the intercept, and the version-matched evaluation metrics defined in `REQ-TRAIN-005`. Coefficients and performance metrics are required by the PDF; the remaining response details are project decisions.
- **REQ-PRED-005 [PDF]:** Service health status must be available.
- **REQ-PRED-006 [Mixed]:** The service interface must expose `predict`, `model-info`, and `health` operations. The operation names come from the PDF; the project maps them to `POST /api/v1/predict`, `GET /api/v1/model-info`, and `GET /health`. Additional liveness and readiness routes supplement rather than replace `GET /health`.
- **REQ-PRED-007 [Project decision]:** Invalid prediction requests must be rejected without returning a prediction and must identify the invalid field or batch condition.
- **REQ-PRED-008 [Project decision]:** A type-valid request that violates an advisory domain range must still return a prediction. The response must also return a non-blocking warning that identifies the field, submitted value, advisory range, and reason for the warning.
- **REQ-PRED-009 [Project decision]:** A raw model output that is non-finite, negative, or cannot be represented as `numeric(18,2)` after required rounding must produce a structured `MODEL_OUTPUT_OUT_OF_DOMAIN` error and no prediction or history row. A batch containing such an output must fail atomically and identify the input index; partial batch results are not returned.

## Model training, evaluation, and testing requirements

The PDF requires a trained regression model and performance metrics but does not prescribe the training workflow below. Every `REQ-TRAIN-*` requirement in this section is a **Project decision** that makes the model, dataset lineage, and evaluation reproducible without adding a training dashboard.

- **REQ-TRAIN-001:** The repository must contain a self-contained top-level `training/` workspace. It may connect directly to PostgreSQL but must not depend on starting either application backend or the portal.
- **REQ-TRAIN-002:** The training workspace must provide independent command-line scripts at `training/scripts/export_dataset.py`, `training/scripts/train.py`, `training/scripts/evaluate.py`, and `training/scripts/publish.py`. The scripts must be independently executable and suitable for automation.
- **REQ-TRAIN-003:** Dataset export must use a read-only database role, require an explicit central `dataset_version_id`, select every valid labelled row in stable `source_id` order, and write an immutable CSV snapshot plus snapshot metadata under `training/data/snapshots/`. The snapshot must retain `source_id` for lineage, use `price` as the target, exclude `source_id` from model inputs, and contain the seven fields in `REQ-PRED-001`.
- **REQ-TRAIN-004:** The training workspace must split the exported snapshot locally using a shuffled 80/20 train/evaluation split with random seed `42`. Metrics must be calculated from predictions on the held-out evaluation partition rather than rows used to fit the evaluated model. The exact record membership must be saved with the training artifacts; split membership is not business data and must not be written to the central dataset tables.
- **REQ-TRAIN-005:** Evaluation must report the following regression metrics:
  - mean absolute error (MAE), expressed in USD;
  - root mean squared error (RMSE), expressed in USD;
  - coefficient of determination (R²);
  - mean absolute percentage error (MAPE), expressed in percentage points as `100 * mean(abs((actual - predicted) / actual))`.

  The evaluation output must include each metric value together with the central dataset version, exported snapshot fingerprint, total, training, and evaluation row counts, and random seed. MAE, RMSE, and MAPE must be displayed to two decimal places. R² must retain at least four decimal places so that model quality is not hidden by display rounding.
- **REQ-TRAIN-006:** Evaluation must run a mean-target baseline using the same training and evaluation partitions. A model is eligible for publication only when it outperforms that baseline on MAE, RMSE, and MAPE and its held-out evaluation results satisfy all of these acceptance thresholds:
  - MAE no greater than USD 15,000.00;
  - RMSE no greater than USD 20,000.00;
  - R² no lower than 0.9000;
  - MAPE no greater than 7.50 percentage points.
- **REQ-TRAIN-007:** After evaluation passes, the production model artifact must be fitted on all valid labelled rows in the exported immutable snapshot.
- **REQ-TRAIN-008:** A completed evaluation must publish version-matched `split-manifest.json` and `metrics.json` files under `training/runs/<training-run-id>/`. An accepted run must additionally publish these model artifacts under `training/artifacts/<model-version>/` for use by other programs:
  - `model.joblib`, the serialized model;
  - `model-metadata.json`, containing the training-run and model versions, central dataset version and fingerprints, snapshot fingerprint, model type, target name, canonical feature names and order, training timestamp, dependency versions, Git commit, coefficients, and intercept.
- **REQ-TRAIN-009:** The model service must resolve the active model from the PostgreSQL model registry, verify and load the registered model and metadata artifacts, and join the linked database evaluation record for `model-info`. It must not maintain a separate manually copied set of coefficients, metadata, or metrics.
- **REQ-TRAIN-010:** The training system must persist every training-run stage and status in PostgreSQL. Every completed evaluation, including a model rejected by the acceptance thresholds, must persist its evaluation metrics, baseline metrics, acceptance result, source dataset version, snapshot fingerprint, artifact locations and fingerprints, and Git commit. Every registered model must additionally persist its model version and lifecycle status. Model binaries and generated JSON files remain filesystem artifacts and must not be stored as database binary objects.
- **REQ-TRAIN-011:** The training workspace must include `training/README.md`, documenting database access, snapshot export, commands, field meanings and units, validation rules, local split and reproducibility controls, output artifacts and schemas, registry writes, metric definitions and acceptance thresholds, and how the model service consumes the published model.
- **REQ-TRAIN-012:** Every script must return a non-zero exit status on invalid data, failed export, failed evaluation, an unreadable artifact, or a registry failure. A run record must be created before export begins, and every failed run must end as `FAILED` without publishing partial artifacts or replacing the active model version. If PostgreSQL is temporarily unavailable when the failure occurs, the durable local run manifest must remain pending and block publication until the failure state is reconciled to the registry. Candidate activation is a later lifecycle operation: an activation failure must roll back without changing the active model, leave the accepted candidate and successful training run intact, and return a non-zero publication status.

## Data contract and domain assumptions

The source PDF does not define units, currency, record roles, or validation ranges. None of the `REQ-DATA-*` requirements is PDF-mandated: `REQ-DATA-001` through `REQ-DATA-003`, `REQ-DATA-006`, and `REQ-DATA-007` contain **Data assumptions** inferred from the supplied files, while `REQ-DATA-004` and `REQ-DATA-005` are **Project decisions** defining safe and consistent validation. Database-loading details within this section are also project decisions.

- **REQ-DATA-001:** `House Price Dataset.csv` is the labelled seed source for the versioned central housing dataset used by training and market analysis. Database initialization SQL must preload its 50 rows as the initial immutable dataset version. Its `id` column is a source-record identifier, its `price` column is the training target, and the remaining seven columns are model inputs. Neither `id` nor `price` is a prediction-request field.
- **REQ-DATA-002:** `Test Data For Prediction.csv` is an unlabelled Web/API demonstration fixture containing exactly the seven model inputs. It must not be preloaded into the central dataset, used as training or evaluation data, or treated as actual-price market data. When its rows are submitted through the estimator, successful predictions follow the same history-persistence rules as other user inputs.
- **REQ-DATA-003:** Feature meanings and units are:
  - `square_footage`: interior floor area in square feet (sq. ft.);
  - `bedrooms`: whole-number bedroom count;
  - `bathrooms`: bathroom count, where a `.5` increment represents a half bathroom;
  - `year_built`: integer calendar year;
  - `lot_size`: lot area in square feet (sq. ft.);
  - `distance_to_city_center`: distance in miles;
  - `school_rating`: rating on a 1-to-10 scale.
- **REQ-DATA-004:** Every model input must be present, finite, and compatible with the canonical data type, scale, and hard lower-bound rules defined in `constraints.md`. A decimal model input must be exactly representable at its canonical scale without rounding; trailing zeroes do not increase its effective scale. Type errors, non-finite values, excess fractional precision, and hard-range violations must prevent prediction.
- **REQ-DATA-005:** Observed minimum and maximum values in the supplied datasets are descriptive data, not validation or warning limits. A prediction must not be rejected or warned only because an input falls outside the range observed during training.
- **REQ-DATA-006:** `price`, predicted prices, price aggregates, and price-derived exports must use USD. User-visible and exported monetary amounts must contain exactly two decimal places.
- **REQ-DATA-007:** `school_rating` has an advisory domain range of 1 through 10 inclusive. A finite numeric value outside this range must trigger the warning required by `REQ-PRED-008` rather than being rejected solely for exceeding the advisory range.

## Property Value Estimator requirements

`REQ-EST-001` through `REQ-EST-005` and `REQ-EST-007` are **PDF** requirements. `REQ-EST-006` is **Mixed**: showing previous estimates is required by the PDF, while durability across reloads, browser sessions, and service restarts is a project decision. `REQ-EST-008` through `REQ-EST-011` are **Project decisions** defining durable history behavior and warning retention.

- **REQ-EST-001:** Users must be able to enter all property fields used by the prediction model.
- **REQ-EST-002:** The application must validate property details before submission and display appropriate validation messages.
- **REQ-EST-003:** Users must be able to submit valid property details and receive a predicted price.
- **REQ-EST-004:** Prediction results must be shown in a table.
- **REQ-EST-005:** Prediction results must also be shown in a visual chart.
- **REQ-EST-006:** Users must be able to view previous estimates from durable history that survives page reloads, browser sessions, and backend service restarts.
- **REQ-EST-007:** Users must be able to compare multiple properties side by side.
- **REQ-EST-008:** Every successful estimate must be persisted with an immutable estimate identifier, creation time in UTC, the seven submitted model inputs, the predicted USD amount, and the model version used for the prediction.
- **REQ-EST-009:** Estimate history must be shown newest first and support pagination with a default page size of 20 records.
- **REQ-EST-010:** Persisted estimates must be retained until the user deletes them. Users must be able to delete one estimate or clear their complete estimate history.
- **REQ-EST-011:** Advisory warnings returned by the prediction service must be shown with the corresponding result and persisted as part of the estimate record.

## Property Market Analysis requirements

`REQ-MARKET-001`, `REQ-MARKET-002`, and `REQ-MARKET-004` through `REQ-MARKET-008` are **PDF** requirements. `REQ-MARKET-003` is **Mixed**: segment filtering is required by the PDF, while the complete list of filterable fields is a project decision. `REQ-MARKET-009` through `REQ-MARKET-011` are **Project decisions** that make filtering behavior deterministic.

- **REQ-MARKET-001:** Users must be able to explore the supplied housing dataset through an interactive dashboard.
- **REQ-MARKET-002:** The dashboard must include property market visualisations.
- **REQ-MARKET-003:** Users must be able to define a property segment by filtering on actual price, square footage, bedroom count, bathroom count, year built, lot size, distance to the city centre, and school rating.
- **REQ-MARKET-004:** Users must be able to change property inputs in a what-if analysis and see the resulting model estimate.
- **REQ-MARKET-005:** Users must be able to export analysis data as CSV.
- **REQ-MARKET-006:** Users must be able to export analysis results as PDF.
- **REQ-MARKET-007:** Market data tables must support sorting and filtering.
- **REQ-MARKET-008:** The application must provide aggregate statistics derived from the supplied housing dataset.
- **REQ-MARKET-009:** The default market view must apply no filters and include every source record.
- **REQ-MARKET-010:** The active filters must define one consistent record set used by the aggregate statistics, charts, data table, and exports.
- **REQ-MARKET-011:** Users must be able to reset all market filters to the default view.

## Market analysis export requirements

The PDF requires CSV and PDF export options. Every `REQ-EXPORT-*` requirement below is a **Project decision** defining the content and consistency of those required exports.

- **REQ-EXPORT-001:** A CSV export must contain the currently filtered records in the table's current sort order.
- **REQ-EXPORT-002:** The CSV must include a header row followed by `id`, the seven canonical model input fields, and `price`. Price values must use fixed two-decimal USD formatting.
- **REQ-EXPORT-003:** A PDF export must use an A4 landscape layout and include the report title, generation time in UTC, active filters, matching record count, current aggregate statistics, market charts, and the filtered and sorted property table.
- **REQ-EXPORT-004:** If a what-if analysis exists when the PDF is generated, the report must include its seven input values, predicted USD amount, model version, and advisory warnings.
- **REQ-EXPORT-005:** Multi-page PDF exports must include page numbers and repeat the property-table header on each table page.

## Source files

- [`House Price Dataset.csv`](../requirements/House%20Price%20Dataset.csv) is the source for the initial database seed migration and is not read directly during normal training or market requests.
- [`Test Data For Prediction.csv`](../requirements/Test%20Data%20For%20Prediction.csv) is an unlabelled Web/API demonstration fixture and is not part of database initialization.

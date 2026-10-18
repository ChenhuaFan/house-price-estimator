# Product requirements

## Purpose

The product must let users estimate individual property values and explore housing market data from one portal. This document describes what the product must do. Technology choices, version rules, implementation details, quality standards, and delivery obligations are defined separately in [`constraints.md`](constraints.md).

The authoritative source for this document is [`Interview Tasks Fullstack.pdf`](../requirements/Interview%20Tasks%20Fullstack.pdf). An [English Markdown transcription](../requirements/Interview%20Tasks%20Fullstack.md) is available for convenience.

## Product scope

The product consists of:

1. A reproducible workflow for training, evaluating, and testing the housing price model.
2. A housing price prediction service.
3. A unified portal containing:
   - a Property Value Estimator;
   - a Property Market Analysis application.

Both applications must use the same housing price prediction capability.

## Shared portal requirements

- **REQ-PORTAL-001:** Users must be able to move between the Property Value Estimator and Property Market Analysis applications through shared navigation.
- **REQ-PORTAL-002:** The portal must show clear loading feedback while data or results are pending.
- **REQ-PORTAL-003:** The portal must show useful error messages when data cannot be loaded or an operation fails.

## Housing price prediction requirements

- **REQ-PRED-001:** Every prediction record must provide all seven model input fields:
  - `square_footage`
  - `bedrooms`
  - `bathrooms`
  - `year_built`
  - `lot_size`
  - `distance_to_city_center`
  - `school_rating`
- **REQ-PRED-002:** A valid single-property request must return exactly one predicted price. The response must identify the currency as USD and represent the amount as a fixed two-decimal decimal value.
- **REQ-PRED-003:** A batch request must contain between 1 and 1,000 property records using the same seven-field schema. The response must contain exactly one prediction for each input record and preserve input order.
- **REQ-PRED-004:** Model information must be available and must include the model version, feature names in canonical order, one coefficient per feature, the intercept, and the version-matched evaluation metrics defined in `REQ-TRAIN-005`.
- **REQ-PRED-005:** Service health status must be available.
- **REQ-PRED-006:** The service interface must expose `predict`, `model-info`, and `health` operations.
- **REQ-PRED-007:** Invalid prediction requests must be rejected without returning a prediction and must identify the invalid field or batch condition.
- **REQ-PRED-008:** A type-valid request that violates an advisory domain range must still return a prediction. The response must also return a non-blocking warning that identifies the field, submitted value, advisory range, and reason for the warning.

## Model training, evaluation, and testing requirements

- **REQ-TRAIN-001:** The repository must contain a self-contained top-level `training/` workspace. Model preparation must not depend on starting either application backend or the portal.
- **REQ-TRAIN-002:** The training workspace must provide independent command-line scripts at `training/scripts/train.py`, `training/scripts/evaluate.py`, and `training/scripts/test.py`. The scripts must be independently executable and suitable for automation.
- **REQ-TRAIN-003:** The training entry point must validate and load `House Price Dataset.csv`, use `price` as the target, exclude `id` from the model inputs, train on the seven fields in `REQ-PRED-001`, and produce a loadable model artifact.
- **REQ-TRAIN-004:** Model evaluation must use a shuffled 80/20 train/evaluation split with random seed `42`. Metrics must be calculated from predictions on the held-out evaluation partition rather than rows used to fit the evaluated model.
- **REQ-TRAIN-005:** Evaluation must report the following regression metrics:
  - mean absolute error (MAE), expressed in USD;
  - root mean squared error (RMSE), expressed in USD;
  - coefficient of determination (R²);
  - mean absolute percentage error (MAPE), expressed as a percentage.

  The evaluation output must include each metric value together with the total, training, and evaluation row counts and the random seed. MAE, RMSE, and MAPE must be displayed to two decimal places. R² must retain at least four decimal places so that model quality is not hidden by display rounding.
- **REQ-TRAIN-006:** Evaluation must run a mean-target baseline using the same training and evaluation partitions. A model is eligible for publication only when it outperforms that baseline on MAE, RMSE, and MAPE and its held-out evaluation results satisfy all of these acceptance thresholds:
  - MAE no greater than USD 15,000.00;
  - RMSE no greater than USD 20,000.00;
  - R² no lower than 0.9000;
  - MAPE no greater than 7.50%.
- **REQ-TRAIN-007:** After evaluation passes, the production model artifact must be fitted on all valid labelled rows in the supplied training dataset.
- **REQ-TRAIN-008:** A successful training run must publish these machine-readable, version-matched artifacts under `training/artifacts/` for use by other programs:
  - `model.joblib`, the serialized model;
  - `model-metadata.json`, containing the model version, model type, target name, canonical feature names and order, training timestamp, dependency versions, dataset fingerprint, coefficients, and intercept;
  - `metrics.json`, containing the protocol, baseline results, acceptance status, and results required by `REQ-TRAIN-004` through `REQ-TRAIN-006`.
- **REQ-TRAIN-009:** The model service's `model-info` operation must read coefficients, metadata, and metrics produced by the training workflow. It must not maintain a separate manually copied set of model information.
- **REQ-TRAIN-010:** The model-testing script must load the produced model artifact, validate the inference schema, run predictions for every row in `Test Data For Prediction.csv`, verify that the output count matches the input count and that every result is finite, and write `training/artifacts/test-predictions.csv`. It must not report accuracy metrics from this file because the file contains no actual `price` values.
- **REQ-TRAIN-011:** The training workspace must include `training/README.md`, documenting setup, commands, source data, field meanings and units, validation rules, reproducibility controls, output artifacts and schemas, metric definitions and acceptance thresholds, and how the model service consumes the artifacts.
- **REQ-TRAIN-012:** Every script must return a non-zero exit status on invalid data, failed evaluation, an unreadable artifact, or an inference-contract failure. A failed run must not publish partial artifacts as a valid model version.

## Data contract and domain assumptions

The source PDF does not define units or currency. The following conventions are project assumptions inferred from the supplied data and must be used consistently unless authoritative requirements replace them.

- **REQ-DATA-001:** `House Price Dataset.csv` is the labelled training and market-analysis dataset. Its `id` column is a source-record identifier, its `price` column is the training target, and the remaining seven columns are model inputs. Neither `id` nor `price` is a prediction-request field.
- **REQ-DATA-002:** `Test Data For Prediction.csv` is an unlabelled inference fixture containing exactly the seven model inputs. It must not be used as training data or as actual-price market data.
- **REQ-DATA-003:** Feature meanings and units are:
  - `square_footage`: interior floor area in square feet (sq. ft.);
  - `bedrooms`: whole-number bedroom count;
  - `bathrooms`: bathroom count, where a `.5` increment represents a half bathroom;
  - `year_built`: integer calendar year;
  - `lot_size`: lot area in square feet (sq. ft.);
  - `distance_to_city_center`: distance in miles;
  - `school_rating`: rating on a 1-to-10 scale.
- **REQ-DATA-004:** Every model input must be present, finite, and compatible with the canonical data type and hard lower-bound rules defined in `constraints.md`. Type errors, non-finite values, and hard-range violations must prevent prediction.
- **REQ-DATA-005:** Observed minimum and maximum values in the supplied datasets are descriptive data, not validation or warning limits. A prediction must not be rejected or warned only because an input falls outside the range observed during training.
- **REQ-DATA-006:** `price`, predicted prices, price aggregates, and price-derived exports must use USD. User-visible and exported monetary amounts must contain exactly two decimal places.
- **REQ-DATA-007:** `school_rating` has an advisory domain range of 1 through 10 inclusive. A finite numeric value outside this range must trigger the warning required by `REQ-PRED-008` rather than being rejected solely for exceeding the advisory range.

## Property Value Estimator requirements

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

- **REQ-EXPORT-001:** A CSV export must contain the currently filtered records in the table's current sort order.
- **REQ-EXPORT-002:** The CSV must include a header row followed by `id`, the seven canonical model input fields, and `price`. Price values must use fixed two-decimal USD formatting.
- **REQ-EXPORT-003:** A PDF export must use an A4 landscape layout and include the report title, generation time in UTC, active filters, matching record count, current aggregate statistics, market charts, and the filtered and sorted property table.
- **REQ-EXPORT-004:** If a what-if analysis exists when the PDF is generated, the report must include its seven input values, predicted USD amount, model version, and advisory warnings.
- **REQ-EXPORT-005:** Multi-page PDF exports must include page numbers and repeat the property-table header on each table page.

## Source files

- [`House Price Dataset.csv`](../requirements/House%20Price%20Dataset.csv) is the supplied labelled dataset.
- [`Test Data For Prediction.csv`](../requirements/Test%20Data%20For%20Prediction.csv) is the supplied unlabelled inference fixture.

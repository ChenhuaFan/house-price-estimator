# Housing model training workspace

`training/` is a one-shot, offline workspace for the Task 1 regression model. It does not run as an application service and it does not depend on the portal, estimator service, market service, or model service.

The workflow reads one explicit immutable dataset version from PostgreSQL, writes a canonical snapshot, creates a reproducible held-out split, evaluates an ordinary least-squares `LinearRegression`, records the result, and publishes an accepted model through the model registry.

## Access boundaries

The training container uses `TRAINING_DATABASE_URL` and the `training_runtime` role. That role can:

- read the selected version from `catalog.dataset_versions` and `catalog.housing_records`;
- execute controlled functions that write training runs, evaluation metrics, and model versions under `ml_registry`.

It cannot insert, update, or delete the central housing catalog. It also cannot write estimate history.

The runtime paths are configured through:

| Variable | Container default | Purpose |
| --- | --- | --- |
| `TRAINING_DATABASE_URL` | supplied by Compose | PostgreSQL connection for catalog reads and registry functions |
| `SNAPSHOT_ROOT` | `/data/snapshots` | Immutable exported dataset snapshots |
| `TRAINING_RUN_ROOT` | `/data/runs` | Run state, split manifests, metrics, and evaluation models |
| `MODEL_ARTIFACT_ROOT` | `/data/artifacts` | Accepted production model and metadata files |
| `GIT_COMMIT_SHA` | supplied by the root Makefile | Source revision recorded with the run and model |

Compose stores the three filesystem roots in separate named volumes. `model-service` mounts only the artifact volume and does so read-only.

## Canonical fields

The initial labelled source is `requirements/House Price Dataset.csv`. Normal training does not read that file directly; migration `V005` imports it into the versioned catalog, and training exports the chosen database version.

| Field | Meaning and unit | Contract |
| --- | --- | --- |
| `source_id` | Stable source-record identifier | Integer at least 1; lineage only, never a model feature |
| `square_footage` | Interior floor area in square feet | Integer at least 1 |
| `bedrooms` | Bedroom count | Integer at least 0 |
| `bathrooms` | Bathroom count; `.5` is a half bathroom | Decimal at least `0.5`, in exact `0.5` increments |
| `year_built` | Calendar year | Integer at least 1 |
| `lot_size` | Lot area in square feet | Integer at least 0 |
| `distance_to_city_center` | Distance in miles | Non-negative decimal with at most three fractional places |
| `school_rating` | School rating on a nominal 1-10 scale | Finite decimal with at most three fractional places; values outside 1-10 remain valid but produce a prediction warning |
| `price` | Labelled sale price in USD | Positive decimal stored and exported with exactly two fractional places |

The feature order is fixed as:

```text
square_footage, bedrooms, bathrooms, year_built, lot_size,
distance_to_city_center, school_rating
```

Column names, not JSON or CSV property order, determine model placement. `source_id` and `price` are excluded from the model input matrix. Invalid schemas, non-finite numbers, excess precision, and hard-range violations stop the run with a non-zero exit status.

`requirements/Test Data For Prediction.csv` is an unlabelled demonstration fixture. It is not exported, split, trained on, or used to calculate accuracy.

## Reproducible evaluation protocol

Each run uses these controls:

- canonical UTF-8 CSV serialization with LF newlines and a SHA-256 fingerprint;
- stable source ordering by `source_id`;
- `train_test_split` with shuffle enabled, an 80/20 split, and random seed `42`;
- a saved list of training and evaluation `source_id` values;
- an evaluation model fitted only on the training partition;
- metrics calculated only on the held-out evaluation partition;
- a mean-target baseline calculated from the same training and evaluation split;
- pinned Python dependencies and the current Git commit;
- a final production model fitted on every valid row only after evaluation passes.

The split is local run metadata. It is not written into the central catalog.

## Metrics and acceptance

The evaluation report contains:

- MAE in USD: `mean(abs(actual - predicted))`;
- RMSE in USD: `sqrt(mean((actual - predicted)^2))`;
- R² from the held-out actual and predicted values;
- MAPE in percentage points: `100 * mean(abs((actual - predicted) / actual))`.

Metric values used for acceptance are converted with `Decimal(str(value))`, rounded to 12 fractional digits using `ROUND_HALF_EVEN`, and stored identically in `metrics.json` and PostgreSQL.

An evaluation is accepted only when the model beats the mean-target baseline on MAE, RMSE, and MAPE and satisfies every threshold:

| Metric | Required value |
| --- | --- |
| MAE | no more than USD 15,000.00 |
| RMSE | no more than USD 20,000.00 |
| R² | at least 0.9000 |
| MAPE | no more than 7.50 percentage points |

A rejected evaluation is still recorded in `ml_registry`, but it cannot create a model candidate.

## One-command workflow

From the repository root, ensure `.env` exists and contains non-example passwords, then run:

```sh
make train \
  DATASET_VERSION_ID=00000000-0000-4000-8000-000000000001 \
  MODEL_VERSION=housing-linear-interview-v2
```

This runs export, split/train, evaluation, publication, and activation. If `MODEL_VERSION` is omitted, the pipeline derives an immutable name from the training-run UUID.

`make bootstrap` uses the same pipeline only when the database has no active model. `make up` never starts training.

## Stage-by-stage workflow

Build the training image and initialize the database first:

```sh
make db-migrate
docker compose --profile training build training
```

Choose one UUID and reuse it for the first three stages:

```sh
TRAINING_RUN_ID=11111111-1111-4111-8111-111111111111
DATASET_VERSION_ID=00000000-0000-4000-8000-000000000001
```

Export the immutable snapshot and create the run:

```sh
docker compose --profile training run --rm training \
  scripts/export_dataset.py \
  --dataset-version-id "$DATASET_VERSION_ID" \
  --training-run-id "$TRAINING_RUN_ID"
```

Create the held-out split and evaluation model:

```sh
docker compose --profile training run --rm training \
  scripts/train.py --training-run-id "$TRAINING_RUN_ID"
```

Evaluate the held-out split. Exit status `2` means the run completed but failed the acceptance policy:

```sh
docker compose --profile training run --rm training \
  scripts/evaluate.py --training-run-id "$TRAINING_RUN_ID"
```

Register an accepted run as a candidate without changing the active model:

```sh
docker compose --profile training run --rm training \
  scripts/publish.py \
  --training-run-id "$TRAINING_RUN_ID" \
  --model-version housing-linear-interview-v2
```

Preflight and activate that candidate through the root command:

```sh
make publish MODEL_VERSION=housing-linear-interview-v2
```

`make publish` checks the candidate and both fingerprints before stopping `model-service`. It performs the transactional activation, starts the service again whether activation succeeds or fails, and returns non-zero on failure. Restarting the service makes it load and verify the newly active version.

## Output files

### Snapshot directory

```text
data/snapshots/<dataset-version-id>/<snapshot-sha256>/
  dataset.csv
  snapshot-metadata.json
```

`dataset.csv` contains `source_id`, the seven features, and `price` in canonical column order. `snapshot-metadata.json` records schema version, dataset identifiers, source and normalized fingerprints, row count, column order, export time, tool version, and Git commit.

The directory is content-addressed. Existing bytes and immutable metadata must match exactly; the exporter refuses to overwrite a different snapshot.

### Run directory

```text
runs/<training-run-id>/
  run-state.json
  split-manifest.json
  evaluation-model.joblib
  metrics.json
```

- `run-state.json` is the durable state machine. It contains the run and dataset IDs, Git commit, stage, status, registry revision, pending operation, known paths and hashes, timestamps, and sanitized failure information.
- `split-manifest.json` records the algorithm and Scikit-learn version, random seed, ratio, row counts, and exact training/evaluation source memberships.
- `evaluation-model.joblib` is the model fitted only on the training partition. It is used to calculate held-out metrics and is not served in production.
- `metrics.json` records model and baseline metrics, threshold results, acceptance decision, dataset lineage, split fingerprint, counts, policy version, and evaluation time.

### Published model directory

```text
artifacts/<model-version>/
  model.joblib
  model-metadata.json
```

`model.joblib` is the accepted `LinearRegression` fitted on all rows. `model-metadata.json` includes the model and run versions, dataset and snapshot lineage, model type, target, ordered features, coefficients, intercept, dependency versions, Git commit, training time, artifact fingerprint, metrics fingerprint, and row count.

Files are written through sibling temporary files, flushed, synced, atomically renamed, and fingerprinted before their paths are committed to the registry.

## Registry writes and lifecycle

The training workspace calls database functions instead of receiving direct table-write grants:

| Registry data | Purpose |
| --- | --- |
| `ml_registry.training_runs` | Stage, status, revision, lineage, artifact paths/hashes, and failure evidence |
| `ml_registry.training_run_metrics` | Immutable model/baseline metrics and policy decision for every completed evaluation |
| `ml_registry.model_versions` | Versioned model artifacts and `CANDIDATE -> ACTIVE -> RETIRED` lifecycle |

Registration completes an accepted run and creates a `CANDIDATE` in one transaction. Promotion uses a transaction-scoped lock, retires the previous active version, and activates the candidate atomically. Historical rows are retained because persisted estimates refer to the exact model version used.

The full SQL contract and permissions are in [`../docs/data-schema.md`](../docs/data-schema.md).

## Failure handling and reconciliation

Every stage exits non-zero on invalid data, a changed fingerprint, an unreadable artifact, a registry error, or a failed filesystem operation. A normal stage failure updates the existing registry run to `FAILED`; it never registers or activates a partial model.

If PostgreSQL is unavailable while the failure is being recorded, `run-state.json` is retained with:

```text
run_status = REGISTRY_RECONCILIATION_REQUIRED
pending_registry_operation = FAIL_RUN
```

After PostgreSQL is healthy, reconcile it explicitly:

```sh
docker compose --profile training run --rm training \
  scripts/reconcile.py --training-run-id "$TRAINING_RUN_ID"
```

The command is idempotent. It creates the registry run first when the outage prevented `start_run`, records the stored failure when the registry run is still `RUNNING`, and accepts an already `FAILED` row. Training, evaluation, and publication also detect this pending failure, reconcile it, and stop with a message instructing the operator to start a new run. A failed run is never resumed.

Candidate activation is separate from training completion. If activation fails, the accepted candidate and successful run remain intact, and the previous active model remains unchanged.

## Model-service consumption

At startup, `model-service` queries the single `ACTIVE` registry row and joins its training run and evaluation metrics. It then:

1. resolves the registered paths below `MODEL_ARTIFACT_ROOT` and rejects path escapes;
2. verifies the SHA-256 hash of `model.joblib` and `model-metadata.json`;
3. checks the model version, canonical feature order, metadata artifact fingerprint, and coefficient count;
4. loads the model and exposes readiness;
5. returns the database-backed metrics and metadata through `GET /api/v1/model-info`.

Missing, changed, or incompatible artifacts keep `/health/ready` unavailable. The service never trains or repairs a model at request time.

## Tests

From the repository root:

```sh
make python-test
```

The training tests cover canonical serialization, deterministic splitting, metric and baseline policy, registry adapters, the complete successful workflow, immutable snapshot reuse, failure persistence, and idempotent failure reconciliation. The quality gate enforces at least 80% line coverage and 70% branch coverage.

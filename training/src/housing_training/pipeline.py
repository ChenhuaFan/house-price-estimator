from __future__ import annotations

import csv
import io
import math
import os
import tempfile
from datetime import UTC, datetime
from decimal import ROUND_HALF_EVEN, Decimal
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

import joblib
import numpy as np
import sklearn
from sklearn.linear_model import LinearRegression
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import train_test_split

from . import __version__
from .constants import (
    ACCEPTANCE_POLICY_VERSION,
    FEATURES,
    METRIC_SCALE,
    RANDOM_SEED,
    SNAPSHOT_COLUMNS,
    TARGET,
    THRESHOLDS,
    TRAIN_RATIO,
)
from .io_utils import atomic_write_bytes, atomic_write_json, read_json, sha256_file
from .registry import Registry
from .settings import Settings
from .state import create_state, load_state, now_iso, save_state, sync_state


def _format_snapshot_row(row: dict[str, Any]) -> list[str]:
    return [
        str(row["source_id"]),
        str(row["square_footage"]),
        str(row["bedrooms"]),
        f"{Decimal(row['bathrooms']):.1f}",
        str(row["year_built"]),
        str(row["lot_size"]),
        f"{Decimal(row['distance_to_city_center']):.3f}",
        f"{Decimal(row['school_rating']):.3f}",
        f"{Decimal(row['price']):.2f}",
    ]


def canonical_snapshot_bytes(rows: list[dict[str, Any]]) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(SNAPSHOT_COLUMNS)
    for row in rows:
        writer.writerow(_format_snapshot_row(row))
    return output.getvalue().encode("utf-8")


def _relative(path: Path, root: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()


def _atomic_joblib_dump(value: Any, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    os.close(descriptor)
    temporary = Path(temporary_name)
    try:
        joblib.dump(value, temporary)
        with temporary.open("rb") as source:
            os.fsync(source.fileno())
        os.replace(temporary, path)
        directory_fd = os.open(path.parent, os.O_DIRECTORY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise


def export_dataset(settings: Settings, dataset_version_id: UUID, run_id: UUID) -> Path:
    registry = Registry(settings.database_url)
    state = create_state(
        settings.run_root, run_id, dataset_version_id, settings.git_commit_sha
    )
    try:
        revision = registry.start_run(run_id, dataset_version_id, settings.git_commit_sha)
        sync_state(settings.run_root, run_id, state, revision, "EXPORTING")
        metadata, rows = registry.dataset(dataset_version_id)
        if len(rows) != int(metadata["row_count"]):
            raise ValueError(
                f"dataset row count mismatch: expected {metadata['row_count']}, got {len(rows)}"
            )
        content = canonical_snapshot_bytes(rows)
        import hashlib

        snapshot_hash = hashlib.sha256(content).hexdigest()
        if snapshot_hash != metadata["content_sha256"].strip():
            raise ValueError("canonical snapshot hash does not match the catalog version")

        directory = settings.snapshot_root / str(dataset_version_id) / snapshot_hash
        snapshot = directory / "dataset.csv"
        if snapshot.exists():
            if snapshot.read_bytes() != content:
                raise ValueError(f"refusing to overwrite different snapshot bytes at {snapshot}")
        else:
            atomic_write_bytes(snapshot, content)

        snapshot_metadata = {
            "schema_version": 1,
            "dataset_version_id": str(dataset_version_id),
            "version_name": metadata["version_name"],
            "source_filename": metadata["source_filename"],
            "source_sha256": metadata["source_sha256"].strip(),
            "content_sha256": metadata["content_sha256"].strip(),
            "snapshot_sha256": snapshot_hash,
            "row_count": len(rows),
            "column_order": list(SNAPSHOT_COLUMNS),
            "exported_at": now_iso(),
            "export_tool_version": __version__,
            "git_commit_sha": settings.git_commit_sha,
        }
        metadata_path = directory / "snapshot-metadata.json"
        if metadata_path.exists():
            existing_metadata = read_json(metadata_path)
            immutable_fields = (
                "schema_version",
                "dataset_version_id",
                "version_name",
                "source_filename",
                "source_sha256",
                "content_sha256",
                "snapshot_sha256",
                "row_count",
                "column_order",
            )
            if any(existing_metadata.get(key) != snapshot_metadata[key] for key in immutable_fields):
                raise ValueError(
                    f"refusing to reuse incompatible snapshot metadata at {metadata_path}"
                )
        else:
            atomic_write_json(metadata_path, snapshot_metadata)
        metadata_hash = sha256_file(metadata_path)
        details = {
            "snapshot_path": _relative(snapshot, settings.snapshot_root),
            "snapshot_sha256": snapshot_hash,
            "snapshot_metadata_path": _relative(metadata_path, settings.snapshot_root),
            "snapshot_metadata_sha256": metadata_hash,
        }
        state["paths"].update(details)
        state["pending_registry_operation"] = "ADVANCE_RUN"
        save_state(settings.run_root, run_id, state)
        revision = registry.advance_run(run_id, revision, "SPLITTING", details)
        sync_state(settings.run_root, run_id, state, revision, "SPLITTING")
        return snapshot
    except BaseException as error:
        _record_failure(settings, run_id, state, error)
        raise


def _load_snapshot(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if tuple(reader.fieldnames or ()) != SNAPSHOT_COLUMNS:
            raise ValueError("snapshot column order is invalid")
        rows = list(reader)
    if len(rows) < 2:
        raise ValueError("snapshot must contain at least two rows")
    return rows


def _arrays(rows: list[dict[str, str]]) -> tuple[np.ndarray[Any, Any], np.ndarray[Any, Any]]:
    features = np.asarray(
        [[float(row[feature]) for feature in FEATURES] for row in rows], dtype=np.float64
    )
    target = np.asarray([float(row[TARGET]) for row in rows], dtype=np.float64)
    if not np.isfinite(features).all() or not np.isfinite(target).all():
        raise ValueError("snapshot contains non-finite model values")
    return features, target


def train(settings: Settings, run_id: UUID) -> Path:
    registry = Registry(settings.database_url)
    state = load_state(settings.run_root, run_id)
    try:
        run = registry.run(run_id)
        if run["run_stage"] != "SPLITTING" or run["run_status"] != "RUNNING":
            raise ValueError("training run is not ready to split")
        snapshot = settings.snapshot_root / str(run["snapshot_path"])
        if sha256_file(snapshot) != str(run["snapshot_sha256"]).strip():
            raise ValueError("snapshot fingerprint changed after export")
        rows = _load_snapshot(snapshot)
        source_ids = [int(row["source_id"]) for row in rows]
        train_ids, evaluation_ids = train_test_split(
            source_ids, test_size=0.20, random_state=RANDOM_SEED, shuffle=True
        )
        train_id_set = set(train_ids)
        evaluation_id_set = set(evaluation_ids)
        if train_id_set & evaluation_id_set or train_id_set | evaluation_id_set != set(source_ids):
            raise AssertionError("split membership is not a disjoint complete partition")

        manifest = {
            "schema_version": 1,
            "training_run_id": str(run_id),
            "dataset_version_id": str(run["dataset_version_id"]),
            "snapshot_sha256": str(run["snapshot_sha256"]).strip(),
            "algorithm": "sklearn.model_selection.train_test_split",
            "library_version": sklearn.__version__,
            "shuffle": True,
            "random_seed": RANDOM_SEED,
            "train_ratio": f"{TRAIN_RATIO:.4f}",
            "training_source_ids": sorted(train_id_set),
            "evaluation_source_ids": sorted(evaluation_id_set),
            "total_rows": len(rows),
            "training_rows": len(train_ids),
            "evaluation_rows": len(evaluation_ids),
        }
        manifest_path = settings.run_root / str(run_id) / "split-manifest.json"
        atomic_write_json(manifest_path, manifest)
        manifest_hash = sha256_file(manifest_path)
        details = {
            "split_manifest_path": _relative(manifest_path, settings.run_root),
            "split_manifest_sha256": manifest_hash,
            "total_rows": len(rows),
            "training_rows": len(train_ids),
            "evaluation_rows": len(evaluation_ids),
        }
        state["paths"].update(details)
        state["pending_registry_operation"] = "ADVANCE_RUN"
        save_state(settings.run_root, run_id, state)
        revision = registry.advance_run(
            run_id, int(run["state_revision"]), "TRAINING", details
        )
        sync_state(settings.run_root, run_id, state, revision, "TRAINING")

        training_rows = [row for row in rows if int(row["source_id"]) in train_id_set]
        x_train, y_train = _arrays(training_rows)
        model = LinearRegression().fit(x_train, y_train)
        model_path = settings.run_root / str(run_id) / "evaluation-model.joblib"
        _atomic_joblib_dump(model, model_path)
        state["paths"]["evaluation_model_path"] = _relative(model_path, settings.run_root)
        state["hashes"]["evaluation_model_sha256"] = sha256_file(model_path)
        state["pending_registry_operation"] = "ADVANCE_RUN"
        save_state(settings.run_root, run_id, state)
        revision = registry.advance_run(run_id, revision, "EVALUATING", {})
        sync_state(settings.run_root, run_id, state, revision, "EVALUATING")
        return model_path
    except BaseException as error:
        _record_failure(settings, run_id, state, error)
        raise


def _metric(value: float) -> Decimal:
    if not math.isfinite(value):
        raise ValueError("metric is not finite")
    return Decimal(str(value)).quantize(METRIC_SCALE, rounding=ROUND_HALF_EVEN)


def _metric_string(value: Decimal) -> str:
    return f"{value:.12f}"


def evaluate(settings: Settings, run_id: UUID) -> bool:
    registry = Registry(settings.database_url)
    state = load_state(settings.run_root, run_id)
    try:
        run = registry.run(run_id)
        if run["run_stage"] != "EVALUATING" or run["run_status"] != "RUNNING":
            raise ValueError("training run is not ready for evaluation")
        snapshot = settings.snapshot_root / str(run["snapshot_path"])
        manifest_path = settings.run_root / str(run["split_manifest_path"])
        if sha256_file(snapshot) != str(run["snapshot_sha256"]).strip():
            raise ValueError("snapshot fingerprint changed before evaluation")
        if sha256_file(manifest_path) != str(run["split_manifest_sha256"]).strip():
            raise ValueError("split manifest fingerprint changed before evaluation")
        manifest = read_json(manifest_path)
        rows = _load_snapshot(snapshot)
        evaluation_ids = set(int(value) for value in manifest["evaluation_source_ids"])
        training_ids = set(int(value) for value in manifest["training_source_ids"])
        evaluation_rows = [row for row in rows if int(row["source_id"]) in evaluation_ids]
        training_rows = [row for row in rows if int(row["source_id"]) in training_ids]
        x_evaluation, y_evaluation = _arrays(evaluation_rows)
        _, y_training = _arrays(training_rows)

        model_path = settings.run_root / str(run_id) / "evaluation-model.joblib"
        if sha256_file(model_path) != state["hashes"]["evaluation_model_sha256"]:
            raise ValueError("evaluation model fingerprint changed")
        model = joblib.load(model_path)
        predictions = np.asarray(model.predict(x_evaluation), dtype=np.float64)
        baseline = np.full_like(y_evaluation, y_training.mean(), dtype=np.float64)
        if (y_evaluation == 0).any():
            raise ValueError("MAPE is undefined for a zero target")

        values_decimal = {
            "mae": _metric(mean_absolute_error(y_evaluation, predictions)),
            "rmse": _metric(math.sqrt(mean_squared_error(y_evaluation, predictions))),
            "r2": _metric(r2_score(y_evaluation, predictions)),
            "mape": _metric(float(np.mean(np.abs((y_evaluation - predictions) / y_evaluation)) * 100)),
            "baseline_mae": _metric(mean_absolute_error(y_evaluation, baseline)),
            "baseline_rmse": _metric(math.sqrt(mean_squared_error(y_evaluation, baseline))),
            "baseline_mape": _metric(
                float(np.mean(np.abs((y_evaluation - baseline) / y_evaluation)) * 100)
            ),
        }
        passed = (
            values_decimal["mae"] <= THRESHOLDS["mae"]
            and values_decimal["rmse"] <= THRESHOLDS["rmse"]
            and values_decimal["r2"] >= THRESHOLDS["r2"]
            and values_decimal["mape"] <= THRESHOLDS["mape"]
            and values_decimal["mae"] < values_decimal["baseline_mae"]
            and values_decimal["rmse"] < values_decimal["baseline_rmse"]
            and values_decimal["mape"] < values_decimal["baseline_mape"]
        )
        values = {name: _metric_string(value) for name, value in values_decimal.items()}
        threshold_results = {
            "mae": values_decimal["mae"] <= THRESHOLDS["mae"],
            "rmse": values_decimal["rmse"] <= THRESHOLDS["rmse"],
            "r2": values_decimal["r2"] >= THRESHOLDS["r2"],
            "mape": values_decimal["mape"] <= THRESHOLDS["mape"],
            "beats_baseline_mae": values_decimal["mae"] < values_decimal["baseline_mae"],
            "beats_baseline_rmse": values_decimal["rmse"] < values_decimal["baseline_rmse"],
            "beats_baseline_mape": values_decimal["mape"] < values_decimal["baseline_mape"],
        }
        report = {
            "schema_version": 1,
            "training_run_id": str(run_id),
            "dataset_version_id": str(run["dataset_version_id"]),
            "snapshot_sha256": str(run["snapshot_sha256"]).strip(),
            "split_manifest_sha256": str(run["split_manifest_sha256"]).strip(),
            "total_rows": int(run["total_rows"]),
            "training_rows": int(run["training_rows"]),
            "evaluation_rows": int(run["evaluation_rows"]),
            "random_seed": RANDOM_SEED,
            "model_metrics": {key: values[key] for key in ("mae", "rmse", "r2", "mape")},
            "baseline_metrics": {
                "mae": values["baseline_mae"],
                "rmse": values["baseline_rmse"],
                "mape": values["baseline_mape"],
            },
            "acceptance_policy_version": ACCEPTANCE_POLICY_VERSION,
            "threshold_results": threshold_results,
            "acceptance_passed": passed,
            "evaluated_at": now_iso(),
        }
        metrics_path = settings.run_root / str(run_id) / "metrics.json"
        atomic_write_json(metrics_path, report)
        metrics_hash = sha256_file(metrics_path)
        state["paths"]["metrics_path"] = _relative(metrics_path, settings.run_root)
        state["hashes"]["metrics_sha256"] = metrics_hash
        state["pending_registry_operation"] = "RECORD_EVALUATION"
        save_state(settings.run_root, run_id, state)
        revision, database_passed = registry.record_evaluation(
            run_id,
            int(run["state_revision"]),
            _relative(metrics_path, settings.run_root),
            metrics_hash,
            values,
        )
        if passed != database_passed:
            raise AssertionError("database and training acceptance decisions differ")
        sync_state(
            settings.run_root,
            run_id,
            state,
            revision,
            "REGISTERING" if passed else "COMPLETE",
            "RUNNING" if passed else "REJECTED",
        )
        return passed
    except BaseException as error:
        _record_failure(settings, run_id, state, error)
        raise


def publish(
    settings: Settings, run_id: UUID, model_version: str, *, promote: bool = False
) -> Path:
    registry = Registry(settings.database_url)
    state = load_state(settings.run_root, run_id)
    try:
        run = registry.run(run_id)
        if run["run_stage"] != "REGISTERING" or run["run_status"] != "RUNNING":
            raise ValueError("training run is not eligible for model publication")
        snapshot = settings.snapshot_root / str(run["snapshot_path"])
        manifest_path = settings.run_root / str(run["split_manifest_path"])
        metrics_path = settings.run_root / str(run_id) / "metrics.json"
        if sha256_file(snapshot) != str(run["snapshot_sha256"]).strip():
            raise ValueError("snapshot fingerprint changed before publication")
        if sha256_file(manifest_path) != str(run["split_manifest_sha256"]).strip():
            raise ValueError("split manifest fingerprint changed before publication")
        if sha256_file(metrics_path) != state["hashes"]["metrics_sha256"]:
            raise ValueError("metrics fingerprint changed before publication")
        report = read_json(metrics_path)
        if not report.get("acceptance_passed"):
            raise ValueError("rejected evaluation cannot be published")

        rows = _load_snapshot(snapshot)
        x_all, y_all = _arrays(rows)
        model = LinearRegression().fit(x_all, y_all)
        artifact_directory = settings.artifact_root / model_version
        artifact_directory.mkdir(parents=True, exist_ok=False)
        artifact_path = artifact_directory / "model.joblib"
        _atomic_joblib_dump(model, artifact_path)
        artifact_hash = sha256_file(artifact_path)

        metadata = {
            "schema_version": 1,
            "model_version": model_version,
            "training_run_id": str(run_id),
            "dataset_version_id": str(run["dataset_version_id"]),
            "source_sha256": read_json(
                settings.snapshot_root
                / Path(str(run["snapshot_metadata_path"]))
            )["source_sha256"],
            "content_sha256": read_json(
                settings.snapshot_root
                / Path(str(run["snapshot_metadata_path"]))
            )["content_sha256"],
            "snapshot_sha256": str(run["snapshot_sha256"]).strip(),
            "model_type": "LinearRegression",
            "target": TARGET,
            "feature_names": list(FEATURES),
            "coefficients": [float(value) for value in model.coef_],
            "intercept": float(model.intercept_),
            "dependency_versions": {
                "python": f"{os.sys.version_info.major}.{os.sys.version_info.minor}.{os.sys.version_info.micro}",
                "numpy": np.__version__,
                "scikit_learn": sklearn.__version__,
                "joblib": joblib.__version__,
            },
            "git_commit_sha": str(run["git_commit_sha"]),
            "trained_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "split_manifest_sha256": str(run["split_manifest_sha256"]).strip(),
            "metrics_sha256": state["hashes"]["metrics_sha256"],
            "artifact_sha256": artifact_hash,
            "training_row_count": len(rows),
        }
        metadata_path = artifact_directory / "model-metadata.json"
        atomic_write_json(metadata_path, metadata)
        metadata_hash = sha256_file(metadata_path)

        loaded_model = joblib.load(artifact_path)
        loaded_metadata = read_json(metadata_path)
        if loaded_metadata["feature_names"] != list(FEATURES):
            raise ValueError("published feature order failed preflight")
        if len(loaded_model.coef_) != len(FEATURES):
            raise ValueError("published model coefficient count failed preflight")

        state["paths"].update(
            {
                "artifact_path": _relative(artifact_path, settings.artifact_root),
                "metadata_path": _relative(metadata_path, settings.artifact_root),
            }
        )
        state["hashes"].update(
            {"artifact_sha256": artifact_hash, "metadata_sha256": metadata_hash}
        )
        state["pending_registry_operation"] = "REGISTER_CANDIDATE"
        save_state(settings.run_root, run_id, state)
        revision = registry.register_candidate(
            run_id,
            int(run["state_revision"]),
            model_version,
            _relative(artifact_path, settings.artifact_root),
            artifact_hash,
            _relative(metadata_path, settings.artifact_root),
            metadata_hash,
        )
        sync_state(settings.run_root, run_id, state, revision, "COMPLETE", "SUCCEEDED")
        if promote:
            state["pending_registry_operation"] = "PROMOTE_MODEL"
            save_state(settings.run_root, run_id, state)
            registry.promote_model(model_version)
            state["pending_registry_operation"] = "NONE"
            save_state(settings.run_root, run_id, state)
        return artifact_path
    except BaseException as error:
        _record_failure(settings, run_id, state, error)
        raise


def run_pipeline(
    settings: Settings,
    dataset_version_id: UUID,
    model_version: str | None = None,
) -> tuple[UUID, str]:
    run_id = uuid4()
    resolved_version = model_version or f"housing-linear-{str(run_id)[:12]}"
    export_dataset(settings, dataset_version_id, run_id)
    train(settings, run_id)
    if not evaluate(settings, run_id):
        raise RuntimeError(f"training run {run_id} was rejected by the acceptance policy")
    publish(settings, run_id, resolved_version, promote=True)
    return run_id, resolved_version


def _record_failure(
    settings: Settings, run_id: UUID, state: dict[str, Any], error: BaseException
) -> None:
    summary = f"{type(error).__name__}: {error}"[:1000]
    state["failure"] = {"summary": summary, "at": now_iso()}
    try:
        run = Registry(settings.database_url).run(run_id)
        if run["run_status"] == "RUNNING":
            state["pending_registry_operation"] = "FAIL_RUN"
            save_state(settings.run_root, run_id, state)
            revision = Registry(settings.database_url).fail_run(
                run_id, int(run["state_revision"]), summary
            )
            sync_state(
                settings.run_root,
                run_id,
                state,
                revision,
                str(run["run_stage"]),
                "FAILED",
            )
            return
    except BaseException:
        state["pending_registry_operation"] = "FAIL_RUN"
        state["run_status"] = "REGISTRY_RECONCILIATION_REQUIRED"
    save_state(settings.run_root, run_id, state)

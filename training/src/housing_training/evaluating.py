from __future__ import annotations

from pathlib import Path
from uuid import UUID

import joblib
import numpy as np

from .artifacts import relative_path
from .constants import ACCEPTANCE_POLICY_VERSION, RANDOM_SEED
from .dataset import arrays, load_snapshot
from .evaluation_policy import evaluate_predictions
from .failure import record_failure
from .io_utils import atomic_write_json, read_json, sha256_file
from .registry import Registry
from .settings import Settings
from .state import load_state, now_iso, save_state, sync_state


def evaluate(settings: Settings, run_id: UUID) -> bool:
    registry = Registry(settings.database_url)
    state = load_state(settings.run_root, run_id)
    try:
        run = registry.run(run_id)
        if run["run_stage"] != "EVALUATING" or run["run_status"] != "RUNNING":
            raise ValueError("training run is not ready for evaluation")
        snapshot = settings.snapshot_root / str(run["snapshot_path"])
        manifest_path = settings.run_root / str(run["split_manifest_path"])
        _verify_input_fingerprints(run, snapshot, manifest_path)
        manifest = read_json(manifest_path)
        rows = load_snapshot(snapshot)
        evaluation_ids = {int(value) for value in manifest["evaluation_source_ids"]}
        training_ids = {int(value) for value in manifest["training_source_ids"]}
        evaluation_rows = [
            row for row in rows if int(row["source_id"]) in evaluation_ids
        ]
        training_rows = [row for row in rows if int(row["source_id"]) in training_ids]
        x_evaluation, y_evaluation = arrays(evaluation_rows)
        _, y_training = arrays(training_rows)

        model_path = settings.run_root / str(run_id) / "evaluation-model.joblib"
        if sha256_file(model_path) != state["hashes"]["evaluation_model_sha256"]:
            raise ValueError("evaluation model fingerprint changed")
        model = joblib.load(model_path)
        predictions = np.asarray(model.predict(x_evaluation), dtype=np.float64)
        decision = evaluate_predictions(y_evaluation, predictions, y_training)

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
            "model_metrics": {
                key: decision.values[key] for key in ("mae", "rmse", "r2", "mape")
            },
            "baseline_metrics": {
                "mae": decision.values["baseline_mae"],
                "rmse": decision.values["baseline_rmse"],
                "mape": decision.values["baseline_mape"],
            },
            "acceptance_policy_version": ACCEPTANCE_POLICY_VERSION,
            "threshold_results": decision.threshold_results,
            "acceptance_passed": decision.passed,
            "evaluated_at": now_iso(),
        }
        metrics_path = settings.run_root / str(run_id) / "metrics.json"
        atomic_write_json(metrics_path, report)
        metrics_hash = sha256_file(metrics_path)
        state["paths"]["metrics_path"] = relative_path(metrics_path, settings.run_root)
        state["hashes"]["metrics_sha256"] = metrics_hash
        state["pending_registry_operation"] = "RECORD_EVALUATION"
        save_state(settings.run_root, run_id, state)
        revision, database_passed = registry.record_evaluation(
            run_id,
            int(run["state_revision"]),
            relative_path(metrics_path, settings.run_root),
            metrics_hash,
            decision.values,
        )
        if decision.passed != database_passed:
            raise AssertionError("database and training acceptance decisions differ")
        sync_state(
            settings.run_root,
            run_id,
            state,
            revision,
            "REGISTERING" if decision.passed else "COMPLETE",
            "RUNNING" if decision.passed else "REJECTED",
        )
        return decision.passed
    except BaseException as error:
        record_failure(settings, run_id, state, error)
        raise


def _verify_input_fingerprints(
    run: dict[str, object], snapshot: Path, manifest_path: Path
) -> None:
    if sha256_file(snapshot) != str(run["snapshot_sha256"]).strip():
        raise ValueError("snapshot fingerprint changed before evaluation")
    if sha256_file(manifest_path) != str(run["split_manifest_sha256"]).strip():
        raise ValueError("split manifest fingerprint changed before evaluation")

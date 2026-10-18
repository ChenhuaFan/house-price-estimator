from __future__ import annotations

from pathlib import Path
from uuid import UUID

import sklearn
from sklearn.linear_model import LinearRegression
from sklearn.model_selection import train_test_split

from .artifacts import atomic_joblib_dump, relative_path
from .constants import RANDOM_SEED, TRAIN_RATIO
from .dataset import arrays, load_snapshot
from .failure import reconcile_pending_failure, record_failure
from .io_utils import atomic_write_json, sha256_file
from .registry import Registry
from .settings import Settings
from .state import load_state, save_state, sync_state


def train(settings: Settings, run_id: UUID) -> Path:
    registry = Registry(settings.database_url)
    state = load_state(settings.run_root, run_id)
    if reconcile_pending_failure(settings, run_id, state):
        raise RuntimeError("the previous failure was reconciled; start a new training run")
    try:
        run = registry.run(run_id)
        if run["run_stage"] != "SPLITTING" or run["run_status"] != "RUNNING":
            raise ValueError("training run is not ready to split")
        snapshot = settings.snapshot_root / str(run["snapshot_path"])
        if sha256_file(snapshot) != str(run["snapshot_sha256"]).strip():
            raise ValueError("snapshot fingerprint changed after export")
        rows = load_snapshot(snapshot)
        source_ids = [int(row["source_id"]) for row in rows]
        training_ids, evaluation_ids = train_test_split(
            source_ids, test_size=0.20, random_state=RANDOM_SEED, shuffle=True
        )
        training_id_set = set(training_ids)
        evaluation_id_set = set(evaluation_ids)
        if training_id_set & evaluation_id_set or training_id_set | evaluation_id_set != set(
            source_ids
        ):
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
            "training_source_ids": sorted(training_id_set),
            "evaluation_source_ids": sorted(evaluation_id_set),
            "total_rows": len(rows),
            "training_rows": len(training_ids),
            "evaluation_rows": len(evaluation_ids),
        }
        manifest_path = settings.run_root / str(run_id) / "split-manifest.json"
        atomic_write_json(manifest_path, manifest)
        details = {
            "split_manifest_path": relative_path(manifest_path, settings.run_root),
            "split_manifest_sha256": sha256_file(manifest_path),
            "total_rows": len(rows),
            "training_rows": len(training_ids),
            "evaluation_rows": len(evaluation_ids),
        }
        state["paths"].update(details)
        state["pending_registry_operation"] = "ADVANCE_RUN"
        save_state(settings.run_root, run_id, state)
        revision = registry.advance_run(run_id, int(run["state_revision"]), "TRAINING", details)
        sync_state(settings.run_root, run_id, state, revision, "TRAINING")

        training_rows = [row for row in rows if int(row["source_id"]) in training_id_set]
        x_train, y_train = arrays(training_rows)
        model = LinearRegression().fit(x_train, y_train)
        model_path = settings.run_root / str(run_id) / "evaluation-model.joblib"
        atomic_joblib_dump(model, model_path)
        state["paths"]["evaluation_model_path"] = relative_path(model_path, settings.run_root)
        state["hashes"]["evaluation_model_sha256"] = sha256_file(model_path)
        state["pending_registry_operation"] = "ADVANCE_RUN"
        save_state(settings.run_root, run_id, state)
        revision = registry.advance_run(run_id, revision, "EVALUATING", {})
        sync_state(settings.run_root, run_id, state, revision, "EVALUATING")
        return model_path
    except BaseException as error:
        record_failure(settings, run_id, state, error)
        raise

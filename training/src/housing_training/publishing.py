from __future__ import annotations

import sys
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

import joblib
import numpy as np
import sklearn
from sklearn.linear_model import LinearRegression

from .artifacts import atomic_joblib_dump, relative_path
from .constants import FEATURES, TARGET
from .dataset import arrays, load_snapshot
from .failure import record_failure
from .io_utils import atomic_write_json, read_json, sha256_file
from .registry import Registry
from .settings import Settings
from .state import load_state, save_state, sync_state


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
        _verify_publication_inputs(run, state, snapshot, manifest_path, metrics_path)
        report = read_json(metrics_path)
        if not report.get("acceptance_passed"):
            raise ValueError("rejected evaluation cannot be published")

        rows = load_snapshot(snapshot)
        x_all, y_all = arrays(rows)
        model = LinearRegression().fit(x_all, y_all)
        artifact_directory = settings.artifact_root / model_version
        artifact_directory.mkdir(parents=True, exist_ok=False)
        artifact_path = artifact_directory / "model.joblib"
        atomic_joblib_dump(model, artifact_path)
        artifact_hash = sha256_file(artifact_path)
        metadata = _model_metadata(
            settings, run_id, model_version, run, state, model, artifact_hash, len(rows)
        )
        metadata_path = artifact_directory / "model-metadata.json"
        atomic_write_json(metadata_path, metadata)
        metadata_hash = sha256_file(metadata_path)
        _verify_published_model(artifact_path, metadata_path)

        state["paths"].update(
            {
                "artifact_path": relative_path(artifact_path, settings.artifact_root),
                "metadata_path": relative_path(metadata_path, settings.artifact_root),
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
            relative_path(artifact_path, settings.artifact_root),
            artifact_hash,
            relative_path(metadata_path, settings.artifact_root),
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
        record_failure(settings, run_id, state, error)
        raise


def preflight_candidate(settings: Settings, model_version: str) -> tuple[Path, UUID]:
    registry = Registry(settings.database_url)
    record = registry.model_version(model_version)
    if record["lifecycle_status"] != "CANDIDATE" or record["run_status"] != "SUCCEEDED":
        raise ValueError("only a candidate from a successful training run can be activated")

    artifact_root = settings.artifact_root.resolve()
    artifact_path = (artifact_root / str(record["artifact_path"])).resolve()
    metadata_path = (artifact_root / str(record["metadata_path"])).resolve()
    for path in (artifact_path, metadata_path):
        if artifact_root not in path.parents or not path.is_file():
            raise ValueError("candidate artifact path is missing or escapes MODEL_ARTIFACT_ROOT")
    if sha256_file(artifact_path) != str(record["artifact_sha256"]).strip():
        raise ValueError("candidate model artifact fingerprint mismatch")
    if sha256_file(metadata_path) != str(record["metadata_sha256"]).strip():
        raise ValueError("candidate metadata fingerprint mismatch")
    _verify_published_model(artifact_path, metadata_path, model_version)
    return artifact_path, UUID(str(record["training_run_id"]))


def activate_candidate(settings: Settings, model_version: str) -> Path:
    artifact_path, run_id = preflight_candidate(settings, model_version)
    registry = Registry(settings.database_url)
    state = load_state(settings.run_root, run_id)
    state["pending_registry_operation"] = "PROMOTE_MODEL"
    save_state(settings.run_root, run_id, state)
    registry.promote_model(model_version)
    state["pending_registry_operation"] = "NONE"
    save_state(settings.run_root, run_id, state)
    return artifact_path


def _verify_publication_inputs(
    run: dict[str, object],
    state: dict[str, object],
    snapshot: Path,
    manifest_path: Path,
    metrics_path: Path,
) -> None:
    if sha256_file(snapshot) != str(run["snapshot_sha256"]).strip():
        raise ValueError("snapshot fingerprint changed before publication")
    if sha256_file(manifest_path) != str(run["split_manifest_sha256"]).strip():
        raise ValueError("split manifest fingerprint changed before publication")
    hashes = state["hashes"]
    if not isinstance(hashes, dict) or sha256_file(metrics_path) != hashes["metrics_sha256"]:
        raise ValueError("metrics fingerprint changed before publication")


def _model_metadata(
    settings: Settings,
    run_id: UUID,
    model_version: str,
    run: dict[str, object],
    state: dict[str, object],
    model: LinearRegression,
    artifact_hash: str,
    row_count: int,
) -> dict[str, object]:
    snapshot_metadata = read_json(
        settings.snapshot_root / Path(str(run["snapshot_metadata_path"]))
    )
    hashes = state["hashes"]
    if not isinstance(hashes, dict):
        raise ValueError("training state hashes are invalid")
    python_version = (
        f"{sys.version_info.major}.{sys.version_info.minor}."
        f"{sys.version_info.micro}"
    )
    return {
        "schema_version": 1,
        "model_version": model_version,
        "training_run_id": str(run_id),
        "dataset_version_id": str(run["dataset_version_id"]),
        "source_sha256": snapshot_metadata["source_sha256"],
        "content_sha256": snapshot_metadata["content_sha256"],
        "snapshot_sha256": str(run["snapshot_sha256"]).strip(),
        "model_type": "LinearRegression",
        "target": TARGET,
        "feature_names": list(FEATURES),
        "coefficients": [float(value) for value in model.coef_],
        "intercept": float(model.intercept_),
        "dependency_versions": {
            "python": python_version,
            "numpy": np.__version__,
            "scikit_learn": sklearn.__version__,
            "joblib": joblib.__version__,
        },
        "git_commit_sha": str(run["git_commit_sha"]),
        "trained_at": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "split_manifest_sha256": str(run["split_manifest_sha256"]).strip(),
        "metrics_sha256": hashes["metrics_sha256"],
        "artifact_sha256": artifact_hash,
        "training_row_count": row_count,
    }


def _verify_published_model(
    artifact_path: Path, metadata_path: Path, model_version: str | None = None
) -> None:
    model = joblib.load(artifact_path)
    metadata = read_json(metadata_path)
    if model_version is not None and metadata.get("model_version") != model_version:
        raise ValueError("candidate metadata version mismatch")
    if metadata.get("feature_names") != list(FEATURES) or len(model.coef_) != len(FEATURES):
        raise ValueError("candidate feature contract failed preflight")

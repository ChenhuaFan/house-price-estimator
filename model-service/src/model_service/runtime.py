from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import joblib
import psycopg
from psycopg.rows import dict_row

from .domain import FEATURES


@dataclass(frozen=True)
class LoadedModel:
    model: Any
    model_version: str
    metadata: dict[str, Any]
    registry: dict[str, Any]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _resolve(root: Path, relative: str) -> Path:
    candidate = (root / relative).resolve()
    resolved_root = root.resolve()
    if candidate == resolved_root or resolved_root not in candidate.parents:
        raise ValueError("registered artifact path escapes MODEL_ARTIFACT_ROOT")
    return candidate


def load_active_model(database_url: str, artifact_root: Path) -> LoadedModel:
    with psycopg.connect(database_url, row_factory=dict_row) as connection:
        row = connection.execute(
            """
            SELECT mv.model_version, mv.model_type, mv.artifact_path, mv.artifact_sha256,
                   mv.metadata_path, mv.metadata_sha256, mv.training_run_id,
                   tr.dataset_version_id, tr.git_commit_sha, tr.snapshot_sha256,
                   m.mae, m.rmse, m.r2, m.mape, m.baseline_mae,
                   m.baseline_rmse, m.baseline_mape, m.acceptance_policy_version,
                   m.evaluated_at
              FROM ml_registry.model_versions mv
              JOIN ml_registry.training_runs tr USING (training_run_id)
              JOIN ml_registry.training_run_metrics m USING (training_run_id)
             WHERE mv.lifecycle_status = 'ACTIVE'
            """
        ).fetchone()
    if row is None:
        raise RuntimeError("no active model is registered")
    registry = dict(row)
    artifact = _resolve(artifact_root, str(row["artifact_path"]))
    metadata_path = _resolve(artifact_root, str(row["metadata_path"]))
    if not artifact.is_file() or not metadata_path.is_file():
        raise RuntimeError("registered model files are missing")
    if _sha256(artifact) != str(row["artifact_sha256"]).strip():
        raise RuntimeError("model artifact fingerprint mismatch")
    if _sha256(metadata_path) != str(row["metadata_sha256"]).strip():
        raise RuntimeError("model metadata fingerprint mismatch")
    with metadata_path.open(encoding="utf-8") as source:
        metadata = json.load(source)
    if metadata.get("model_version") != row["model_version"]:
        raise RuntimeError("model metadata version mismatch")
    if metadata.get("feature_names") != list(FEATURES):
        raise RuntimeError("model feature contract mismatch")
    if metadata.get("artifact_sha256") != str(row["artifact_sha256"]).strip():
        raise RuntimeError("metadata artifact fingerprint mismatch")
    model = joblib.load(artifact)
    if len(getattr(model, "coef_", [])) != len(FEATURES):
        raise RuntimeError("loaded model has an invalid coefficient count")
    return LoadedModel(model, str(row["model_version"]), metadata, registry)

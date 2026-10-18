from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import UUID

from .io_utils import atomic_write_json, read_json


def now_iso() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def state_path(run_root: Path, run_id: UUID) -> Path:
    return run_root / str(run_id) / "run-state.json"


def create_state(
    run_root: Path, run_id: UUID, dataset_version_id: UUID, git_commit_sha: str
) -> dict[str, Any]:
    created_at = now_iso()
    state: dict[str, Any] = {
        "schema_version": 1,
        "training_run_id": str(run_id),
        "dataset_version_id": str(dataset_version_id),
        "git_commit_sha": git_commit_sha,
        "run_stage": "EXPORTING",
        "run_status": "RUNNING",
        "state_revision": 0,
        "last_synced_registry_revision": None,
        "pending_registry_operation": "START_RUN",
        "paths": {},
        "hashes": {},
        "failure": None,
        "created_at": created_at,
        "updated_at": created_at,
    }
    atomic_write_json(state_path(run_root, run_id), state)
    return state


def load_state(run_root: Path, run_id: UUID) -> dict[str, Any]:
    return read_json(state_path(run_root, run_id))


def save_state(run_root: Path, run_id: UUID, state: dict[str, Any]) -> None:
    state["updated_at"] = now_iso()
    atomic_write_json(state_path(run_root, run_id), state)


def sync_state(
    run_root: Path,
    run_id: UUID,
    state: dict[str, Any],
    revision: int,
    stage: str,
    status: str = "RUNNING",
    operation: str = "NONE",
) -> None:
    state.update(
        {
            "state_revision": revision,
            "last_synced_registry_revision": revision,
            "run_stage": stage,
            "run_status": status,
            "pending_registry_operation": operation,
        }
    )
    save_state(run_root, run_id, state)

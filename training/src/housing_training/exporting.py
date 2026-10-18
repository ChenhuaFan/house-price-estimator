from __future__ import annotations

import hashlib
from pathlib import Path
from uuid import UUID

from . import __version__
from .artifacts import relative_path
from .constants import SNAPSHOT_COLUMNS
from .dataset import canonical_snapshot_bytes
from .failure import record_failure
from .io_utils import atomic_write_bytes, atomic_write_json, read_json, sha256_file
from .registry import Registry
from .settings import Settings
from .state import create_state, now_iso, save_state, sync_state


def export_dataset(settings: Settings, dataset_version_id: UUID, run_id: UUID) -> Path:
    registry = Registry(settings.database_url)
    state = create_state(settings.run_root, run_id, dataset_version_id, settings.git_commit_sha)
    try:
        revision = registry.start_run(run_id, dataset_version_id, settings.git_commit_sha)
        sync_state(settings.run_root, run_id, state, revision, "EXPORTING")
        metadata, rows = registry.dataset(dataset_version_id)
        if len(rows) != int(metadata["row_count"]):
            raise ValueError(
                f"dataset row count mismatch: expected {metadata['row_count']}, got {len(rows)}"
            )
        content = canonical_snapshot_bytes(rows)
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
            incompatible = any(
                existing_metadata.get(key) != snapshot_metadata[key] for key in immutable_fields
            )
            if incompatible:
                raise ValueError(
                    f"refusing to reuse incompatible snapshot metadata at {metadata_path}"
                )
        else:
            atomic_write_json(metadata_path, snapshot_metadata)
        metadata_hash = sha256_file(metadata_path)
        details = {
            "snapshot_path": relative_path(snapshot, settings.snapshot_root),
            "snapshot_sha256": snapshot_hash,
            "snapshot_metadata_path": relative_path(metadata_path, settings.snapshot_root),
            "snapshot_metadata_sha256": metadata_hash,
        }
        state["paths"].update(details)
        state["pending_registry_operation"] = "ADVANCE_RUN"
        save_state(settings.run_root, run_id, state)
        revision = registry.advance_run(run_id, revision, "SPLITTING", details)
        sync_state(settings.run_root, run_id, state, revision, "SPLITTING")
        return snapshot
    except BaseException as error:
        record_failure(settings, run_id, state, error)
        raise

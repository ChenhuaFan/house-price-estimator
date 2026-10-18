from __future__ import annotations

import hashlib
from decimal import Decimal
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest

from housing_training import evaluating, exporting, failure, publishing, training_stage
from housing_training.dataset import canonical_snapshot_bytes
from housing_training.io_utils import read_json
from housing_training.settings import Settings
from housing_training.state import create_state, save_state

RUN_ID = UUID("11111111-1111-4111-8111-111111111111")
SECOND_RUN_ID = UUID("22222222-2222-4222-8222-222222222222")
DATASET_ID = UUID("00000000-0000-4000-8000-000000000001")


def source_rows() -> list[dict[str, object]]:
    rows = []
    for source_id in range(1, 51):
        square_footage = 900 + source_id * 35
        rows.append(
            {
                "source_id": source_id,
                "square_footage": square_footage,
                "bedrooms": source_id % 5 + 1,
                "bathrooms": Decimal(f"{(source_id % 5 + 2) / 2:.1f}"),
                "year_built": 1970 + source_id,
                "lot_size": 3000 + source_id * 100,
                "distance_to_city_center": Decimal(f"{source_id / 10:.3f}"),
                "school_rating": Decimal(f"{5 + source_id % 5:.3f}"),
                "price": Decimal(f"{100000 + square_footage * 100:.2f}"),
            }
        )
    return rows


class FakeRegistry:
    def __init__(self) -> None:
        self.rows = source_rows()
        content = canonical_snapshot_bytes(self.rows)
        self.metadata = {
            "version_name": "house-price-v1",
            "source_filename": "House Price Dataset.csv",
            "source_sha256": "1" * 64,
            "content_sha256": hashlib.sha256(content).hexdigest(),
            "row_count": len(self.rows),
        }
        self.runs: dict[UUID, dict[str, Any]] = {}
        self.models: dict[str, dict[str, Any]] = {}

    def start_run(self, run_id: UUID, dataset_version_id: UUID, git_commit_sha: str) -> int:
        self.runs[run_id] = {
            "training_run_id": run_id,
            "dataset_version_id": dataset_version_id,
            "git_commit_sha": git_commit_sha,
            "run_stage": "EXPORTING",
            "run_status": "RUNNING",
            "state_revision": 1,
        }
        return 1

    def dataset(self, dataset_version_id: UUID) -> tuple[dict[str, Any], list[dict[str, Any]]]:
        assert dataset_version_id == DATASET_ID
        return self.metadata, self.rows

    def run(self, run_id: UUID) -> dict[str, Any]:
        try:
            return self.runs[run_id]
        except KeyError:
            raise ValueError(f"training run {run_id} was not found") from None

    def advance_run(
        self, run_id: UUID, revision: int, next_stage: str, details: dict[str, Any]
    ) -> int:
        run = self.runs[run_id]
        assert revision == run["state_revision"]
        run.update(details)
        run["run_stage"] = next_stage
        run["state_revision"] = revision + 1
        return revision + 1

    def record_evaluation(
        self,
        run_id: UUID,
        revision: int,
        metrics_path: str,
        metrics_sha256: str,
        values: dict[str, str],
    ) -> tuple[int, bool]:
        run = self.runs[run_id]
        assert Decimal(values["mae"]) < Decimal("0.000001")
        run.update(
            metrics_path=metrics_path,
            metrics_sha256=metrics_sha256,
            run_stage="REGISTERING",
            state_revision=revision + 1,
        )
        return revision + 1, True

    def register_candidate(
        self,
        run_id: UUID,
        revision: int,
        model_version: str,
        artifact_path: str,
        artifact_sha256: str,
        metadata_path: str,
        metadata_sha256: str,
    ) -> int:
        run = self.runs[run_id]
        run.update(run_stage="COMPLETE", run_status="SUCCEEDED", state_revision=revision + 1)
        self.models[model_version] = {
            "model_version": model_version,
            "training_run_id": run_id,
            "run_status": "SUCCEEDED",
            "lifecycle_status": "CANDIDATE",
            "artifact_path": artifact_path,
            "artifact_sha256": artifact_sha256,
            "metadata_path": metadata_path,
            "metadata_sha256": metadata_sha256,
        }
        return revision + 1

    def model_version(self, model_version: str) -> dict[str, Any]:
        return self.models[model_version]

    def promote_model(self, model_version: str) -> None:
        self.models[model_version]["lifecycle_status"] = "ACTIVE"

    def fail_run(self, run_id: UUID, revision: int, summary: str) -> int:
        run = self.runs[run_id]
        run.update(run_status="FAILED", failure_summary=summary, state_revision=revision + 1)
        return revision + 1


def settings(tmp_path: Path) -> Settings:
    return Settings(
        "postgresql://unused",
        tmp_path / "snapshots",
        tmp_path / "runs",
        tmp_path / "artifacts",
        "abc123",
    )


def install_registry(monkeypatch: pytest.MonkeyPatch, registry: FakeRegistry) -> None:
    for module in (exporting, training_stage, evaluating, publishing, failure):
        monkeypatch.setattr(module, "Registry", lambda _: registry)


def test_training_stages_publish_and_activate_a_verified_model(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    registry = FakeRegistry()
    install_registry(monkeypatch, registry)
    config = settings(tmp_path)

    snapshot = exporting.export_dataset(config, DATASET_ID, RUN_ID)
    evaluation_model = training_stage.train(config, RUN_ID)
    assert evaluating.evaluate(config, RUN_ID) is True
    artifact = publishing.publish(config, RUN_ID, "housing-linear-v1")
    preflight_path, preflight_run = publishing.preflight_candidate(config, "housing-linear-v1")
    activated = publishing.activate_candidate(config, "housing-linear-v1")

    assert snapshot.is_file()
    assert evaluation_model.is_file()
    assert artifact.is_file()
    assert preflight_path == artifact == activated
    assert preflight_run == RUN_ID
    assert registry.models["housing-linear-v1"]["lifecycle_status"] == "ACTIVE"
    metadata = read_json(config.artifact_root / "housing-linear-v1/model-metadata.json")
    assert metadata["feature_names"][0] == "square_footage"
    assert metadata["training_row_count"] == 50
    assert read_json(config.run_root / str(RUN_ID) / "metrics.json")["acceptance_passed"] is True


def test_export_reuses_identical_snapshot_and_rejects_changed_catalog_metadata(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    registry = FakeRegistry()
    install_registry(monkeypatch, registry)
    config = settings(tmp_path)
    first = exporting.export_dataset(config, DATASET_ID, RUN_ID)
    second = exporting.export_dataset(config, DATASET_ID, SECOND_RUN_ID)
    assert second == first

    registry.metadata["version_name"] = "changed"
    third_run = UUID("33333333-3333-4333-8333-333333333333")
    with pytest.raises(ValueError, match="incompatible snapshot metadata"):
        exporting.export_dataset(config, DATASET_ID, third_run)
    assert registry.runs[third_run]["run_status"] == "FAILED"


def test_failure_remains_pending_when_registry_cannot_be_reached(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    config = settings(tmp_path)
    state = {
        "run_stage": "EXPORTING",
        "run_status": "RUNNING",
        "pending_registry_operation": "NONE",
    }
    monkeypatch.setattr(
        failure,
        "Registry",
        lambda _: (_ for _ in ()).throw(RuntimeError("database unavailable")),
    )

    failure.record_failure(config, RUN_ID, state, ValueError("bad input"))

    persisted = read_json(config.run_root / str(RUN_ID) / "run-state.json")
    assert persisted["pending_registry_operation"] == "FAIL_RUN"
    assert persisted["run_status"] == "REGISTRY_RECONCILIATION_REQUIRED"


@pytest.mark.parametrize("registry_row_exists", [False, True])
def test_pending_failure_is_idempotently_reconciled_before_work_can_continue(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    registry_row_exists: bool,
) -> None:
    config = settings(tmp_path)
    registry = FakeRegistry()
    if registry_row_exists:
        registry.start_run(RUN_ID, DATASET_ID, config.git_commit_sha)
    install_registry(monkeypatch, registry)
    state = create_state(config.run_root, RUN_ID, DATASET_ID, config.git_commit_sha)
    state.update(
        pending_registry_operation="FAIL_RUN",
        run_status="REGISTRY_RECONCILIATION_REQUIRED",
        failure={"summary": "ValueError: broken input"},
    )
    save_state(config.run_root, RUN_ID, state)

    assert failure.reconcile_pending_failure(config, RUN_ID) is True
    assert failure.reconcile_pending_failure(config, RUN_ID) is False
    persisted = read_json(config.run_root / str(RUN_ID) / "run-state.json")
    assert persisted["run_status"] == "FAILED"
    assert persisted["pending_registry_operation"] == "NONE"
    assert registry.runs[RUN_ID]["failure_summary"] == "ValueError: broken input"


@pytest.mark.parametrize("registry_status", ["FAILED", "SUCCEEDED"])
def test_failure_reconciliation_handles_terminal_registry_states(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    registry_status: str,
) -> None:
    config = settings(tmp_path)
    registry = FakeRegistry()
    registry.start_run(RUN_ID, DATASET_ID, config.git_commit_sha)
    registry.runs[RUN_ID]["run_status"] = registry_status
    install_registry(monkeypatch, registry)
    state = create_state(config.run_root, RUN_ID, DATASET_ID, config.git_commit_sha)
    state.update(
        pending_registry_operation="FAIL_RUN",
        run_status="REGISTRY_RECONCILIATION_REQUIRED",
        failure={"summary": "ValueError: broken input"},
    )
    save_state(config.run_root, RUN_ID, state)

    if registry_status == "FAILED":
        assert failure.reconcile_pending_failure(config, RUN_ID) is True
    else:
        with pytest.raises(RuntimeError, match="registry status SUCCEEDED"):
            failure.reconcile_pending_failure(config, RUN_ID)

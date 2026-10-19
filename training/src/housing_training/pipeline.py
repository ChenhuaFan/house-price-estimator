from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID, uuid4

from .dataset import canonical_snapshot_bytes
from .evaluating import evaluate
from .exporting import export_dataset
from .publishing import activate_candidate, preflight_candidate, publish
from .registry import Registry
from .settings import Settings
from .training_stage import train

__all__ = [
    "activate_candidate",
    "canonical_snapshot_bytes",
    "evaluate",
    "export_dataset",
    "preflight_candidate",
    "publish",
    "run_pipeline",
    "train",
]


def run_pipeline(
    settings: Settings,
    dataset_version_id: UUID | None = None,
    model_version: str | None = None,
    dataset_version_name: str | None = None,
) -> tuple[UUID, str]:
    run_id = uuid4()
    if dataset_version_id is None:
        version_name = dataset_version_name or (
            "business-snapshot-"
            + datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")
            + "-"
            + str(run_id)[:8]
        )
        dataset_version_id = Registry(settings.database_url).snapshot_business_dataset(version_name)
    resolved_version = model_version or f"housing-linear-{str(run_id)[:12]}"
    export_dataset(settings, dataset_version_id, run_id)
    train(settings, run_id)
    if not evaluate(settings, run_id):
        raise RuntimeError(f"training run {run_id} was rejected by the acceptance policy")
    publish(settings, run_id, resolved_version, promote=True)
    return run_id, resolved_version

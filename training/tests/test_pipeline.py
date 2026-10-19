from pathlib import Path
from uuid import UUID

import pytest

from housing_training import pipeline
from housing_training.settings import Settings

RUN_ID = UUID("11111111-1111-4111-8111-111111111111")
DATASET_ID = UUID("00000000-0000-4000-8000-000000000001")


def settings(tmp_path: object) -> Settings:
    root = Path(str(tmp_path))
    return Settings(
        "postgresql://unused",
        root / "snapshots",
        root / "runs",
        root / "artifacts",
        "abc",
    )


def test_pipeline_runs_each_stage_in_order(
    monkeypatch: pytest.MonkeyPatch, tmp_path: object
) -> None:
    calls: list[object] = []
    monkeypatch.setattr(pipeline, "uuid4", lambda: RUN_ID)
    monkeypatch.setattr(
        pipeline,
        "export_dataset",
        lambda config, dataset_id, run_id: calls.append(("export", dataset_id, run_id)),
    )
    monkeypatch.setattr(pipeline, "train", lambda config, run_id: calls.append(("train", run_id)))
    monkeypatch.setattr(
        pipeline,
        "evaluate",
        lambda config, run_id: calls.append(("evaluate", run_id)) or True,
    )
    monkeypatch.setattr(
        pipeline,
        "publish",
        lambda config, run_id, version, promote: calls.append(
            ("publish", run_id, version, promote)
        ),
    )

    result = pipeline.run_pipeline(settings(tmp_path), DATASET_ID, "model-v1")

    assert result == (RUN_ID, "model-v1")
    assert calls == [
        ("export", DATASET_ID, RUN_ID),
        ("train", RUN_ID),
        ("evaluate", RUN_ID),
        ("publish", RUN_ID, "model-v1", True),
    ]


def test_rejected_evaluation_stops_before_publication(
    monkeypatch: pytest.MonkeyPatch, tmp_path: object
) -> None:
    monkeypatch.setattr(pipeline, "uuid4", lambda: RUN_ID)
    monkeypatch.setattr(pipeline, "export_dataset", lambda *args: None)
    monkeypatch.setattr(pipeline, "train", lambda *args: None)
    monkeypatch.setattr(pipeline, "evaluate", lambda *args: False)
    monkeypatch.setattr(
        pipeline,
        "publish",
        lambda *args, **kwargs: pytest.fail("rejected run must not publish"),
    )

    with pytest.raises(RuntimeError, match="rejected"):
        pipeline.run_pipeline(settings(tmp_path), DATASET_ID)


def test_pipeline_snapshots_live_business_data_by_default(
    monkeypatch: pytest.MonkeyPatch, tmp_path: object
) -> None:
    calls: list[object] = []
    monkeypatch.setattr(pipeline, "uuid4", lambda: RUN_ID)

    class FakeRegistry:
        def __init__(self, database_url: str) -> None:
            assert database_url == "postgresql://unused"

        def snapshot_business_dataset(self, name: str) -> UUID:
            calls.append(("snapshot", name))
            return DATASET_ID

    monkeypatch.setattr(pipeline, "Registry", FakeRegistry)
    monkeypatch.setattr(
        pipeline,
        "export_dataset",
        lambda config, dataset_id, run_id: calls.append(("export", dataset_id, run_id)),
    )
    monkeypatch.setattr(pipeline, "train", lambda *args: None)
    monkeypatch.setattr(pipeline, "evaluate", lambda *args: True)
    monkeypatch.setattr(pipeline, "publish", lambda *args, **kwargs: None)

    pipeline.run_pipeline(
        settings(tmp_path), dataset_version_name="business-snapshot-release-candidate"
    )

    assert calls == [
        ("snapshot", "business-snapshot-release-candidate"),
        ("export", DATASET_ID, RUN_ID),
    ]

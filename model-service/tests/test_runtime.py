import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import joblib
import pytest

from model_service import app as app_module
from model_service import runtime
from model_service.app import ModelContainer, create_app
from model_service.domain import FEATURES


class QueryResult:
    def __init__(self, row: dict[str, Any] | None) -> None:
        self.row = row

    def fetchone(self) -> dict[str, Any] | None:
        return self.row


class Connection:
    def __init__(self, row: dict[str, Any] | None) -> None:
        self.row = row

    def __enter__(self) -> "Connection":
        return self

    def __exit__(self, *args: object) -> None:
        return None

    def execute(self, query: str) -> QueryResult:
        assert "lifecycle_status = 'ACTIVE'" in query
        return QueryResult(self.row)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def registered_model(root: Path, **metadata_overrides: object) -> dict[str, Any]:
    directory = root / "housing-v1"
    directory.mkdir(parents=True)
    artifact = directory / "model.joblib"
    model = SimpleNamespace(coef_=[1.0] * len(FEATURES))
    joblib.dump(model, artifact)
    artifact_hash = digest(artifact)
    metadata = {
        "model_version": "housing-v1",
        "feature_names": list(FEATURES),
        "artifact_sha256": artifact_hash,
    }
    metadata.update(metadata_overrides)
    metadata_path = directory / "model-metadata.json"
    metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
    return {
        "model_version": "housing-v1",
        "artifact_path": "housing-v1/model.joblib",
        "artifact_sha256": artifact_hash,
        "metadata_path": "housing-v1/model-metadata.json",
        "metadata_sha256": digest(metadata_path),
    }


def patch_registry(monkeypatch: pytest.MonkeyPatch, row: dict[str, Any] | None) -> None:
    monkeypatch.setattr(runtime.psycopg, "connect", lambda *args, **kwargs: Connection(row))


def test_load_active_model_verifies_and_loads_registered_artifacts(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    row = registered_model(tmp_path)
    patch_registry(monkeypatch, row)

    loaded = runtime.load_active_model("postgresql://unused", tmp_path)

    assert loaded.model_version == "housing-v1"
    assert loaded.metadata["feature_names"] == list(FEATURES)
    assert loaded.registry == row


def test_load_active_model_requires_an_active_registry_row(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    patch_registry(monkeypatch, None)

    with pytest.raises(RuntimeError, match="no active model"):
        runtime.load_active_model("postgresql://unused", tmp_path)


@pytest.mark.parametrize("relative", [".", "../escape.joblib", "/tmp/escape.joblib"])
def test_registered_paths_must_stay_below_artifact_root(tmp_path: Path, relative: str) -> None:
    with pytest.raises(ValueError, match="escapes"):
        runtime._resolve(tmp_path, relative)


def test_load_active_model_rejects_missing_or_tampered_files(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    row = registered_model(tmp_path)
    patch_registry(monkeypatch, row | {"artifact_path": "housing-v1/missing.joblib"})
    with pytest.raises(RuntimeError, match="files are missing"):
        runtime.load_active_model("postgresql://unused", tmp_path)

    patch_registry(monkeypatch, row | {"artifact_sha256": "0" * 64})
    with pytest.raises(RuntimeError, match="artifact fingerprint"):
        runtime.load_active_model("postgresql://unused", tmp_path)

    patch_registry(monkeypatch, row | {"metadata_sha256": "0" * 64})
    with pytest.raises(RuntimeError, match="metadata fingerprint"):
        runtime.load_active_model("postgresql://unused", tmp_path)


@pytest.mark.parametrize(
    ("metadata", "message"),
    [
        ({"model_version": "other"}, "metadata version"),
        ({"feature_names": ["wrong"]}, "feature contract"),
        ({"artifact_sha256": "0" * 64}, "metadata artifact fingerprint"),
    ],
)
def test_load_active_model_rejects_incompatible_metadata(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    metadata: dict[str, object],
    message: str,
) -> None:
    row = registered_model(tmp_path, **metadata)
    patch_registry(monkeypatch, row)

    with pytest.raises(RuntimeError, match=message):
        runtime.load_active_model("postgresql://unused", tmp_path)


def test_load_active_model_rejects_wrong_coefficient_count(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    row = registered_model(tmp_path)
    artifact = tmp_path / str(row["artifact_path"])
    joblib.dump(SimpleNamespace(coef_=[1.0]), artifact)
    artifact_hash = digest(artifact)
    metadata_path = tmp_path / str(row["metadata_path"])
    metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    metadata["artifact_sha256"] = artifact_hash
    metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
    row.update(
        artifact_sha256=artifact_hash,
        metadata_sha256=digest(metadata_path),
    )
    patch_registry(monkeypatch, row)

    with pytest.raises(RuntimeError, match="coefficient count"):
        runtime.load_active_model("postgresql://unused", tmp_path)


def test_model_container_records_load_success_and_failure(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    expected = runtime.LoadedModel(object(), "v1", {}, {})
    monkeypatch.setattr(app_module, "load_active_model", lambda *args: expected)
    container = ModelContainer()
    container.load()
    assert container.loaded is expected
    assert container.load_error is None

    monkeypatch.setattr(
        app_module,
        "load_active_model",
        lambda *args: (_ for _ in ()).throw(RuntimeError("registry unavailable")),
    )
    container.load()
    assert container.loaded is None
    assert container.load_error == "RuntimeError: registry unavailable"


def test_unready_routes_metrics_and_startup_loader_are_exercised(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from fastapi.testclient import TestClient

    container = ModelContainer()
    calls: list[str] = []

    def load() -> None:
        calls.append("load")

    monkeypatch.setattr(container, "load", load)
    with TestClient(create_app(container)) as client:
        assert client.get("/health/ready").status_code == 503
        assert client.get("/api/v1/model-info").status_code == 503
        metrics = client.get("/metrics")

    assert calls == ["load"]
    assert metrics.status_code == 200
    assert "model_service_requests_total" in metrics.text

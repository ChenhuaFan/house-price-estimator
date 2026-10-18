from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any
from uuid import UUID

import pytest

from housing_training import registry as registry_module
from housing_training.registry import Registry, _scalar

RUN_ID = UUID("11111111-1111-4111-8111-111111111111")
DATASET_ID = UUID("00000000-0000-4000-8000-000000000001")


class Result:
    def __init__(
        self,
        *,
        one: object = None,
        all_rows: list[dict[str, Any]] | None = None,
    ) -> None:
        self.one = one
        self.all_rows = all_rows or []

    def fetchone(self) -> Any:
        return self.one

    def fetchall(self) -> list[dict[str, Any]]:
        return self.all_rows


class Cursor(Result):
    def __init__(self, result: Result) -> None:
        super().__init__(one=result.one, all_rows=result.all_rows)
        self.executed: list[tuple[str, object | None]] = []

    def __enter__(self) -> Cursor:
        return self

    def __exit__(self, *args: object) -> None:
        return None

    def execute(self, query: str, parameters: object | None = None) -> None:
        self.executed.append((query, parameters))


class Connection:
    def __init__(self, *results: Result) -> None:
        self.results = list(results)
        self.committed = False

    def __enter__(self) -> Connection:
        return self

    def __exit__(self, *args: object) -> None:
        return None

    @contextmanager
    def cursor(self) -> Iterator[Cursor]:
        yield Cursor(self.results.pop(0))

    def execute(self, query: str, parameters: object | None = None) -> Result:
        return self.results.pop(0)

    def commit(self) -> None:
        self.committed = True


class Connections:
    def __init__(self, *connections: Connection) -> None:
        self.values = list(connections)

    def __call__(self, *args: object, **kwargs: object) -> Connection:
        return self.values.pop(0)


def test_registry_executes_every_command_and_read_adapter(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    metadata = {"dataset_version_id": DATASET_ID, "row_count": 1}
    row = {"source_id": 1}
    model = {"model_version": "v1"}
    run = {"training_run_id": RUN_ID}
    connections = Connections(
        Connection(Result(one=(1,))),
        Connection(Result(one=(2,))),
        Connection(Result(one=(3,))),
        Connection(Result(one=(4, True))),
        Connection(Result(one=(5,))),
        Connection(Result()),
        Connection(Result(one=model)),
        Connection(Result(one=run)),
        Connection(Result(), Result(one=metadata), Result(all_rows=[row])),
    )
    monkeypatch.setattr(registry_module.psycopg, "connect", connections)
    registry = Registry("postgresql://unused")

    assert registry.start_run(RUN_ID, DATASET_ID, "abc") == 1
    assert registry.advance_run(RUN_ID, 1, "SPLITTING", {"rows": 50}) == 2
    assert registry.fail_run(RUN_ID, 2, "failure") == 3
    assert registry.record_evaluation(
        RUN_ID,
        3,
        "metrics.json",
        "a" * 64,
        {
            "mae": "1",
            "rmse": "2",
            "r2": "0.9",
            "mape": "3",
            "baseline_mae": "4",
            "baseline_rmse": "5",
            "baseline_mape": "6",
        },
    ) == (4, True)
    assert (
        registry.register_candidate(RUN_ID, 4, "v1", "model", "a" * 64, "metadata", "b" * 64) == 5
    )
    registry.promote_model("v1")
    assert registry.model_version("v1") == model
    assert registry.run(RUN_ID) == run
    assert registry.dataset(DATASET_ID) == (metadata, [row])
    assert connections.values == []


def test_registry_rejects_missing_rows(monkeypatch: pytest.MonkeyPatch) -> None:
    connections = Connections(
        Connection(Result(one=None)),
        Connection(Result(one=None)),
        Connection(Result(), Result(one=None)),
        Connection(Result(one=None)),
    )
    monkeypatch.setattr(registry_module.psycopg, "connect", connections)
    registry = Registry("postgresql://unused")

    with pytest.raises(ValueError, match="model version"):
        registry.model_version("missing")
    with pytest.raises(ValueError, match="training run"):
        registry.run(RUN_ID)
    with pytest.raises(ValueError, match="dataset version"):
        registry.dataset(DATASET_ID)
    with pytest.raises(RuntimeError, match="record_evaluation"):
        registry.record_evaluation(
            RUN_ID,
            1,
            "metrics.json",
            "a" * 64,
            {
                "mae": "1",
                "rmse": "2",
                "r2": "0.9",
                "mape": "3",
                "baseline_mae": "4",
                "baseline_rmse": "5",
                "baseline_mape": "6",
            },
        )

    with pytest.raises(RuntimeError, match="operation returned no result"):
        _scalar(None, "operation")

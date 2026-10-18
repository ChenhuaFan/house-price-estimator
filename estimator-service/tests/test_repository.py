from __future__ import annotations

from datetime import UTC, datetime, timedelta
from decimal import Decimal
from types import SimpleNamespace
from typing import Any
from uuid import UUID

import pytest

from estimator_service import database
from estimator_service.database import EstimateRepository, IdempotencyConflict
from estimator_service.domain import CreateEstimateRequest

CLIENT_ID = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
REQUEST_ID = UUID("11111111-1111-4111-8111-111111111111")
ESTIMATE_ID = UUID("22222222-2222-4222-8222-222222222222")
CORRELATION_ID = UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")


class Cursor:
    def __init__(
        self,
        *,
        one: dict[str, Any] | None = None,
        all_rows: list[dict[str, Any]] | None = None,
        rowcount: int = 0,
    ) -> None:
        self.one = one
        self.all_rows = all_rows or []
        self.rowcount = rowcount

    async def fetchone(self) -> dict[str, Any] | None:
        return self.one

    async def fetchall(self) -> list[dict[str, Any]]:
        return self.all_rows


class Transaction:
    async def __aenter__(self) -> Transaction:
        return self

    async def __aexit__(self, *args: object) -> None:
        return None


class Connection:
    def __init__(self, results: list[Cursor]) -> None:
        self.results = list(results)
        self.queries: list[tuple[str, object | None]] = []

    async def __aenter__(self) -> Connection:
        return self

    async def __aexit__(self, *args: object) -> None:
        return None

    def transaction(self) -> Transaction:
        return Transaction()

    async def execute(self, query: str, parameters: object | None = None) -> Cursor:
        self.queries.append((query, parameters))
        return self.results.pop(0)


class ConnectionFactory:
    def __init__(self, *connections: Connection) -> None:
        self.connections = list(connections)

    async def connect(self, *args: object, **kwargs: object) -> Connection:
        return self.connections.pop(0)


def install_factory(monkeypatch: pytest.MonkeyPatch, *connections: Connection) -> ConnectionFactory:
    factory = ConnectionFactory(*connections)
    monkeypatch.setattr(
        database.psycopg,
        "AsyncConnection",
        SimpleNamespace(connect=factory.connect),
    )
    return factory


def request(**overrides: object) -> CreateEstimateRequest:
    values: dict[str, object] = {
        "request_id": REQUEST_ID,
        "square_footage": 1800,
        "bedrooms": 3,
        "bathrooms": 2.5,
        "year_built": 2005,
        "lot_size": 6500,
        "distance_to_city_center": 4.125,
        "school_rating": 8.5,
    }
    values.update(overrides)
    return CreateEstimateRequest.model_validate(values)


def estimate_row(**overrides: object) -> dict[str, Any]:
    values: dict[str, Any] = {
        "estimate_id": ESTIMATE_ID,
        "request_id": REQUEST_ID,
        "created_at": datetime(2026, 1, 1, tzinfo=UTC),
        "square_footage": 1800,
        "bedrooms": 3,
        "bathrooms": Decimal("2.5"),
        "year_built": 2005,
        "lot_size": 6500,
        "distance_to_city_center": Decimal("4.125"),
        "school_rating": Decimal("8.5"),
        "predicted_price": Decimal("250000.00"),
        "model_version": "housing-linear-test",
    }
    values.update(overrides)
    return values


@pytest.mark.asyncio
async def test_ping_and_find_by_request_cover_missing_and_stored_rows(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    ping = Connection([Cursor()])
    missing = Connection([Cursor(one=None)])
    found = Connection([Cursor(one=estimate_row())])
    install_factory(monkeypatch, ping, missing, found)
    repository = EstimateRepository("postgresql://unused")

    await repository.ping()
    assert await repository.find_by_request(CLIENT_ID, REQUEST_ID) is None
    estimate = await repository.find_by_request(CLIENT_ID, REQUEST_ID)

    assert estimate is not None
    assert estimate.predicted_price == "250000.00"


@pytest.mark.asyncio
async def test_create_returns_new_estimate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = Connection([Cursor(), Cursor(one=estimate_row())])
    install_factory(monkeypatch, connection)

    estimate, inserted = await EstimateRepository("postgresql://unused").create(
        CLIENT_ID,
        request(school_rating=11),
        CORRELATION_ID,
        Decimal("250000.00"),
        "housing-linear-test",
    )

    assert inserted is True
    assert estimate.estimate_id == ESTIMATE_ID
    assert len(connection.queries) == 2


@pytest.mark.asyncio
async def test_create_returns_identical_concurrent_winner(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = Connection([Cursor(), Cursor(one=None), Cursor(one=estimate_row())])
    install_factory(monkeypatch, connection)

    estimate, inserted = await EstimateRepository("postgresql://unused").create(
        CLIENT_ID,
        request(),
        CORRELATION_ID,
        Decimal("250000.00"),
        "housing-linear-test",
    )

    assert inserted is False
    assert estimate.estimate_id == ESTIMATE_ID


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("winner", "error"),
    [
        (None, RuntimeError),
        (estimate_row(square_footage=1900), IdempotencyConflict),
    ],
)
async def test_create_rejects_invalid_concurrent_winner(
    monkeypatch: pytest.MonkeyPatch,
    winner: dict[str, Any] | None,
    error: type[Exception],
) -> None:
    connection = Connection([Cursor(), Cursor(one=None), Cursor(one=winner)])
    install_factory(monkeypatch, connection)

    with pytest.raises(error):
        await EstimateRepository("postgresql://unused").create(
            CLIENT_ID,
            request(),
            CORRELATION_ID,
            Decimal("250000.00"),
            "housing-linear-test",
        )


@pytest.mark.asyncio
async def test_history_supports_empty_and_cursor_paginated_pages(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    empty = Connection([Cursor(all_rows=[])])
    first = estimate_row()
    second = estimate_row(
        estimate_id=UUID("33333333-3333-4333-8333-333333333333"),
        created_at=datetime(2025, 12, 31, tzinfo=UTC),
    )
    paged = Connection([Cursor(all_rows=[first, second])])
    install_factory(monkeypatch, empty, paged)
    repository = EstimateRepository("postgresql://unused")

    assert (await repository.history(CLIENT_ID, 20, None)).items == []
    cursor = (datetime(2026, 1, 2, tzinfo=UTC), ESTIMATE_ID)
    page = await repository.history(CLIENT_ID, 1, cursor)

    assert len(page.items) == 1
    assert page.next_cursor is not None
    assert database.cursor_decode(page.next_cursor) == (
        first["created_at"],
        first["estimate_id"],
    )
    assert "created_at, estimate_id" in paged.queries[0][0]


@pytest.mark.asyncio
async def test_delete_and_clear_return_database_counts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    delete = Connection([Cursor(rowcount=1)])
    clear = Connection([Cursor(rowcount=3)])
    install_factory(monkeypatch, delete, clear)
    repository = EstimateRepository("postgresql://unused")

    assert await repository.delete_one(CLIENT_ID, ESTIMATE_ID) is True
    assert await repository.clear(CLIENT_ID) == 3


def test_same_inputs_detects_reused_request_with_changed_values() -> None:
    assert database._same_inputs(estimate_row(), request()) is True
    assert database._same_inputs(estimate_row(square_footage=1900), request()) is False


def test_cursor_round_trip_preserves_timestamp_and_identifier() -> None:
    created_at = datetime.now(UTC) - timedelta(days=1)
    encoded = database._cursor_encode(created_at, ESTIMATE_ID)

    assert database.cursor_decode(encoded) == (created_at, ESTIMATE_ID)

from __future__ import annotations

import base64
import json
from datetime import datetime
from decimal import Decimal
from typing import Any
from uuid import UUID, uuid4

import psycopg
from psycopg.rows import dict_row

from .domain import AdvisoryWarning, CreateEstimateRequest, Estimate, HistoryPage, PropertyInput


def _cursor_encode(created_at: datetime, estimate_id: UUID) -> str:
    payload = json.dumps(
        {"created_at": created_at.isoformat(), "estimate_id": str(estimate_id)},
        separators=(",", ":"),
    ).encode()
    return base64.urlsafe_b64encode(payload).decode().rstrip("=")


def cursor_decode(value: str) -> tuple[datetime, UUID]:
    try:
        padded = value + "=" * (-len(value) % 4)
        payload = json.loads(base64.urlsafe_b64decode(padded).decode())
        return datetime.fromisoformat(payload["created_at"]), UUID(payload["estimate_id"])
    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
        raise ValueError("invalid history cursor") from None


def _same_inputs(row: dict[str, Any], request: CreateEstimateRequest) -> bool:
    return (
        int(row["square_footage"]),
        int(row["bedrooms"]),
        Decimal(row["bathrooms"]),
        int(row["year_built"]),
        int(row["lot_size"]),
        Decimal(row["distance_to_city_center"]),
        Decimal(row["school_rating"]),
    ) == request.property_input().canonical_tuple()


def _estimate(row: dict[str, Any], warnings: list[dict[str, Any]]) -> Estimate:
    return Estimate(
        estimate_id=row["estimate_id"],
        request_id=row["request_id"],
        created_at=row["created_at"],
        inputs=PropertyInput(
            square_footage=row["square_footage"],
            bedrooms=row["bedrooms"],
            bathrooms=row["bathrooms"],
            year_built=row["year_built"],
            lot_size=row["lot_size"],
            distance_to_city_center=row["distance_to_city_center"],
            school_rating=row["school_rating"],
        ),
        predicted_price=f"{Decimal(row['predicted_price']):.2f}",
        model_version=row["model_version"],
        warnings=[
            AdvisoryWarning(
                field=warning["field_name"],
                submitted_value=f"{Decimal(warning['submitted_value']):f}",
                advisory_min=(
                    f"{Decimal(warning['advisory_min']):f}"
                    if warning["advisory_min"] is not None
                    else None
                ),
                advisory_max=(
                    f"{Decimal(warning['advisory_max']):f}"
                    if warning["advisory_max"] is not None
                    else None
                ),
                reason=warning["reason"],
            )
            for warning in warnings
        ],
    )


class EstimateRepository:
    def __init__(self, database_url: str) -> None:
        self.database_url = database_url

    async def ping(self) -> None:
        connection = await psycopg.AsyncConnection.connect(self.database_url)
        async with connection:
            await connection.execute("SELECT 1")

    async def find_by_request(self, client_id: UUID, request_id: UUID) -> Estimate | None:
        connection = await psycopg.AsyncConnection.connect(self.database_url, row_factory=dict_row)
        async with connection:
            row = await (
                await connection.execute(
                    """
                    SELECT * FROM estimator.estimates
                     WHERE client_id = %s AND request_id = %s
                    """,
                    (client_id, request_id),
                )
            ).fetchone()
            if row is None:
                return None
            warnings = await (
                await connection.execute(
                    """
                    SELECT * FROM estimator.estimate_warnings
                     WHERE estimate_id = %s ORDER BY warning_order
                    """,
                    (row["estimate_id"],),
                )
            ).fetchall()
        return _estimate(dict(row), [dict(item) for item in warnings])

    async def create(
        self,
        client_id: UUID,
        request: CreateEstimateRequest,
        correlation_id: UUID,
        predicted_price: Decimal,
        model_version: str,
        warnings: list[AdvisoryWarning],
    ) -> tuple[Estimate, bool]:
        connection = await psycopg.AsyncConnection.connect(self.database_url, row_factory=dict_row)
        async with connection, connection.transaction():
                await connection.execute(
                    """
                    INSERT INTO estimator.clients (client_id)
                    VALUES (%s)
                    ON CONFLICT (client_id) DO UPDATE SET last_seen_at = clock_timestamp()
                    """,
                    (client_id,),
                )
                row = await (
                    await connection.execute(
                        """
                        INSERT INTO estimator.estimates (
                            estimate_id, client_id, request_id, square_footage, bedrooms,
                            bathrooms, year_built, lot_size, distance_to_city_center,
                            school_rating, predicted_price, model_version, correlation_id
                        ) VALUES (
                            %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s
                        )
                        ON CONFLICT (client_id, request_id) DO NOTHING
                        RETURNING *
                        """,
                        (
                            uuid4(),
                            client_id,
                            request.request_id,
                            request.square_footage,
                            request.bedrooms,
                            request.bathrooms,
                            request.year_built,
                            request.lot_size,
                            request.distance_to_city_center,
                            request.school_rating,
                            predicted_price,
                            model_version,
                            correlation_id,
                        ),
                    )
                ).fetchone()
                inserted = row is not None
                if row is None:
                    row = await (
                        await connection.execute(
                            """
                            SELECT * FROM estimator.estimates
                             WHERE client_id = %s AND request_id = %s
                            """,
                            (client_id, request.request_id),
                        )
                    ).fetchone()
                    if row is None:
                        raise RuntimeError("concurrent estimate winner was not visible")
                    if not _same_inputs(dict(row), request):
                        raise IdempotencyConflict
                else:
                    for order, warning in enumerate(warnings, start=1):
                        await connection.execute(
                            """
                            INSERT INTO estimator.estimate_warnings (
                                estimate_id, warning_order, field_name, submitted_value,
                                advisory_min, advisory_max, reason
                            ) VALUES (%s, %s, %s, %s, %s, %s, %s)
                            """,
                            (
                                row["estimate_id"],
                                order,
                                warning.field,
                                Decimal(warning.submitted_value),
                                Decimal(warning.advisory_min) if warning.advisory_min else None,
                                Decimal(warning.advisory_max) if warning.advisory_max else None,
                                warning.reason,
                            ),
                        )
                stored_warnings = await (
                    await connection.execute(
                        """
                        SELECT * FROM estimator.estimate_warnings
                         WHERE estimate_id = %s ORDER BY warning_order
                        """,
                        (row["estimate_id"],),
                    )
                ).fetchall()
        return _estimate(dict(row), [dict(item) for item in stored_warnings]), inserted

    async def history(
        self,
        client_id: UUID,
        limit: int,
        cursor: tuple[datetime, UUID] | None,
    ) -> HistoryPage:
        connection = await psycopg.AsyncConnection.connect(self.database_url, row_factory=dict_row)
        async with connection:
            parameters: list[Any] = [client_id]
            predicate = ""
            if cursor is not None:
                predicate = "AND (created_at, estimate_id) < (%s, %s)"
                parameters.extend(cursor)
            parameters.append(limit + 1)
            rows = await (
                await connection.execute(
                    f"""
                    SELECT * FROM estimator.estimates
                     WHERE client_id = %s {predicate}
                     ORDER BY created_at DESC, estimate_id DESC
                     LIMIT %s
                    """,
                    parameters,
                )
            ).fetchall()
            page_rows = rows[:limit]
            ids = [row["estimate_id"] for row in page_rows]
            warning_rows: list[dict[str, Any]] = []
            if ids:
                warning_rows = [
                    dict(item)
                    for item in await (
                        await connection.execute(
                            """
                            SELECT * FROM estimator.estimate_warnings
                             WHERE estimate_id = ANY(%s)
                             ORDER BY estimate_id, warning_order
                            """,
                            (ids,),
                        )
                    ).fetchall()
                ]
        by_estimate: dict[UUID, list[dict[str, Any]]] = {identifier: [] for identifier in ids}
        for warning in warning_rows:
            by_estimate[warning["estimate_id"]].append(warning)
        items = [_estimate(dict(row), by_estimate[row["estimate_id"]]) for row in page_rows]
        next_cursor = None
        if len(rows) > limit and page_rows:
            last = page_rows[-1]
            next_cursor = _cursor_encode(last["created_at"], last["estimate_id"])
        return HistoryPage(items=items, next_cursor=next_cursor)

    async def delete_one(self, client_id: UUID, estimate_id: UUID) -> bool:
        connection = await psycopg.AsyncConnection.connect(self.database_url)
        async with connection:
            cursor = await connection.execute(
                "DELETE FROM estimator.estimates WHERE client_id = %s AND estimate_id = %s",
                (client_id, estimate_id),
            )
            return cursor.rowcount == 1

    async def clear(self, client_id: UUID) -> int:
        connection = await psycopg.AsyncConnection.connect(self.database_url)
        async with connection:
            cursor = await connection.execute(
                "DELETE FROM estimator.estimates WHERE client_id = %s", (client_id,)
            )
            return cursor.rowcount


class IdempotencyConflict(Exception):
    pass

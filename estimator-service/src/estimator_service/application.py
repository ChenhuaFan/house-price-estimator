from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Protocol
from uuid import UUID

from .database import IdempotencyConflict
from .domain import AdvisoryWarning, CreateEstimateRequest, Estimate, HistoryPage
from .model_gateway import ModelGateway


class EstimateStore(Protocol):
    async def ping(self) -> None: ...

    async def find_by_request(self, client_id: UUID, request_id: UUID) -> Estimate | None: ...

    async def create(
        self,
        client_id: UUID,
        request: CreateEstimateRequest,
        correlation_id: UUID,
        predicted_price: Decimal,
        model_version: str,
        warnings: list[AdvisoryWarning],
    ) -> tuple[Estimate, bool]: ...

    async def history(
        self, client_id: UUID, limit: int, cursor: tuple[datetime, UUID] | None
    ) -> HistoryPage: ...

    async def delete_one(self, client_id: UUID, estimate_id: UUID) -> bool: ...

    async def clear(self, client_id: UUID) -> int: ...


@dataclass(frozen=True)
class CreateEstimateResult:
    estimate: Estimate
    created: bool


class EstimateApplicationService:
    def __init__(self, store: EstimateStore, model_gateway: ModelGateway) -> None:
        self._store = store
        self._model_gateway = model_gateway

    async def ready(self, correlation_id: UUID) -> None:
        await self._store.ping()
        await self._model_gateway.ready(correlation_id)

    async def create(
        self,
        client_id: UUID,
        request: CreateEstimateRequest,
        correlation_id: UUID,
    ) -> CreateEstimateResult:
        existing = await self._store.find_by_request(client_id, request.request_id)
        if existing is not None:
            if existing.inputs.canonical_tuple() != request.property_input().canonical_tuple():
                raise IdempotencyConflict
            return CreateEstimateResult(existing, False)

        prediction = await self._model_gateway.predict(request.property_input(), correlation_id)
        estimate, inserted = await self._store.create(
            client_id,
            request,
            correlation_id,
            prediction.predicted_price,
            prediction.model_version,
            prediction.warnings,
        )
        return CreateEstimateResult(estimate, inserted)

    async def history(
        self,
        client_id: UUID,
        limit: int,
        cursor: tuple[datetime, UUID] | None,
    ) -> HistoryPage:
        return await self._store.history(client_id, limit, cursor)

    async def delete(self, client_id: UUID, estimate_id: UUID) -> bool:
        return await self._store.delete_one(client_id, estimate_id)

    async def clear(self, client_id: UUID) -> None:
        await self._store.clear(client_id)

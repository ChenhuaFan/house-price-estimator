from __future__ import annotations

import json
import logging
import os
import time
from collections.abc import Awaitable, Callable
from typing import Any
from uuid import UUID, uuid4

import httpx
from fastapi import FastAPI, Header, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

from .application import EstimateApplicationService, EstimateStore
from .database import EstimateRepository, IdempotencyConflict, cursor_decode
from .domain import CreateEstimateRequest, Estimate, HistoryPage
from .model_gateway import HttpModelGateway, ModelGateway, ModelRejected, ModelUnavailable

LOGGER = logging.getLogger("estimator-service")
REQUESTS = Counter("estimator_service_requests_total", "Requests", ["operation", "status"])
LATENCY = Histogram("estimator_service_request_seconds", "Request latency", ["operation"])


def _error(status: int, code: str, message: str, correlation_id: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={"error": {"code": code, "message": message}, "correlation_id": correlation_id},
        headers={"X-Correlation-ID": correlation_id},
    )


def _client(value: str | None) -> UUID:
    if value is None:
        raise ValueError("X-Estimator-Client-ID is required")
    identifier = UUID(value)
    if identifier.version != 4:
        raise ValueError("X-Estimator-Client-ID must be a UUIDv4")
    return identifier


def create_app(
    repository: EstimateStore | None = None,
    model_gateway: ModelGateway | None = None,
) -> FastAPI:
    store: EstimateStore = repository or EstimateRepository(
        os.environ.get(
            "ESTIMATOR_DATABASE_URL",
            "postgresql://estimator_runtime:estimator@localhost:5432/housing",
        )
    )
    timeout = httpx.Timeout(
        connect=float(os.environ.get("MODEL_CONNECT_TIMEOUT_SECONDS", "1")),
        read=float(os.environ.get("MODEL_RESPONSE_TIMEOUT_SECONDS", "3")),
        write=3.0,
        pool=1.0,
    )
    gateway = model_gateway or HttpModelGateway(
        os.environ.get("MODEL_SERVICE_URL", "http://localhost:8000"), timeout
    )
    service = EstimateApplicationService(store, gateway)
    app = FastAPI(title="Property Value Estimator API", version="1.0.0", openapi_version="3.1.0")
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[os.environ.get("PORTAL_ORIGIN", "http://localhost:3000")],
        allow_credentials=True,
        allow_methods=["GET", "POST", "DELETE"],
        allow_headers=["Content-Type", "X-Correlation-ID", "X-Estimator-Client-ID"],
    )

    @app.middleware("http")
    async def correlation(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        supplied = request.headers.get("X-Correlation-ID")
        if supplied:
            try:
                correlation_id = str(UUID(supplied))
            except ValueError:
                return _error(
                    400,
                    "INVALID_CORRELATION_ID",
                    "X-Correlation-ID must be a valid UUID.",
                    str(uuid4()),
                )
        else:
            correlation_id = str(uuid4())
        request.state.correlation_id = correlation_id
        started = time.perf_counter()
        response = await call_next(request)
        response.headers["X-Correlation-ID"] = correlation_id
        duration = time.perf_counter() - started
        REQUESTS.labels(operation=request.url.path, status=str(response.status_code)).inc()
        LATENCY.labels(operation=request.url.path).observe(duration)
        LOGGER.info(
            json.dumps(
                {
                    "service": "estimator-service",
                    "operation": request.url.path,
                    "correlation_id": correlation_id,
                    "status": response.status_code,
                    "latency_ms": round(duration * 1000, 3),
                }
            )
        )
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, error: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={
                "error": {
                    "code": "VALIDATION_ERROR",
                    "message": "Estimate request is invalid.",
                    "fields": [
                        {
                            "field": ".".join(str(part) for part in item["loc"] if part != "body"),
                            "message": item["msg"],
                        }
                        for item in error.errors()
                    ],
                },
                "correlation_id": getattr(request.state, "correlation_id", str(uuid4())),
            },
        )

    @app.get("/health/live", tags=["health"])
    async def live() -> dict[str, str]:
        return {"status": "ok", "service": "estimator-service"}

    @app.get("/health/ready", tags=["health"])
    async def ready(request: Request) -> Response:
        try:
            await service.ready(UUID(request.state.correlation_id))
        except Exception:
            return _error(
                503,
                "DEPENDENCY_NOT_READY",
                "Estimator dependencies are not ready.",
                request.state.correlation_id,
            )
        return JSONResponse({"status": "ready"})

    @app.post("/api/v1/estimates", response_model=Estimate, status_code=201)
    async def create_estimate(
        payload: CreateEstimateRequest,
        request: Request,
        client_header: str | None = Header(default=None, alias="X-Estimator-Client-ID"),
    ) -> Any:
        try:
            client_id = _client(client_header)
        except ValueError as error:
            return _error(400, "INVALID_CLIENT_ID", str(error), request.state.correlation_id)
        try:
            result = await service.create(
                client_id, payload, UUID(request.state.correlation_id)
            )
        except ModelRejected as error:
            return _error(
                error.status_code,
                error.code,
                error.message,
                request.state.correlation_id,
            )
        except ModelUnavailable:
            return _error(
                503,
                "MODEL_SERVICE_UNAVAILABLE",
                "Prediction is temporarily unavailable. Please retry.",
                request.state.correlation_id,
            )
        except IdempotencyConflict:
            return _error(
                409,
                "IDEMPOTENCY_KEY_REUSED",
                "request_id has already been used with different property inputs.",
                request.state.correlation_id,
            )
        if not result.created:
            return JSONResponse(
                status_code=200,
                content=json.loads(result.estimate.model_dump_json()),
                headers={"X-Correlation-ID": request.state.correlation_id},
            )
        return result.estimate

    @app.get("/api/v1/estimates", response_model=HistoryPage)
    async def history(
        request: Request,
        limit: int = Query(default=20, ge=1, le=100),
        cursor: str | None = None,
        client_header: str | None = Header(default=None, alias="X-Estimator-Client-ID"),
    ) -> Any:
        try:
            client_id = _client(client_header)
            decoded_cursor = cursor_decode(cursor) if cursor else None
        except ValueError as error:
            return _error(400, "INVALID_HISTORY_REQUEST", str(error), request.state.correlation_id)
        return await service.history(client_id, limit, decoded_cursor)

    @app.delete("/api/v1/estimates/{estimate_id}", status_code=204)
    async def delete_estimate(
        estimate_id: UUID,
        request: Request,
        client_header: str | None = Header(default=None, alias="X-Estimator-Client-ID"),
    ) -> Response:
        try:
            client_id = _client(client_header)
        except ValueError as error:
            return _error(400, "INVALID_CLIENT_ID", str(error), request.state.correlation_id)
        if not await service.delete(client_id, estimate_id):
            return _error(
                404,
                "ESTIMATE_NOT_FOUND",
                "Estimate was not found.",
                request.state.correlation_id,
            )
        return Response(status_code=204)

    @app.delete("/api/v1/estimates", status_code=204)
    async def clear_history(
        request: Request,
        client_header: str | None = Header(default=None, alias="X-Estimator-Client-ID"),
    ) -> Response:
        try:
            client_id = _client(client_header)
        except ValueError as error:
            return _error(400, "INVALID_CLIENT_ID", str(error), request.state.correlation_id)
        await service.clear(client_id)
        return Response(status_code=204)

    @app.get("/metrics", include_in_schema=False)
    async def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    app.state.estimate_service = service
    return app

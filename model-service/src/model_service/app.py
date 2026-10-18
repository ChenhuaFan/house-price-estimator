from __future__ import annotations

import json
import logging
import os
import time
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, Response
from prometheus_client import CONTENT_TYPE_LATEST, Counter, Histogram, generate_latest

from .domain import PredictionResult, PredictRequest, PredictResponse, price_from_raw
from .runtime import LoadedModel, load_active_model

LOGGER = logging.getLogger("model-service")
REQUESTS = Counter("model_service_requests_total", "Requests", ["operation", "status"])
LATENCY = Histogram("model_service_request_seconds", "Request latency", ["operation"])
PREDICTION_FAILURES = Counter("model_service_prediction_failures_total", "Prediction failures")


class ModelContainer:
    def __init__(self, loaded: LoadedModel | None = None) -> None:
        self.loaded = loaded
        self.load_error: str | None = None

    def load(self) -> None:
        try:
            self.loaded = load_active_model(
                os.environ.get(
                    "MODEL_DATABASE_URL",
                    "postgresql://model_runtime:model@localhost:5432/housing",
                ),
                Path(os.environ.get("MODEL_ARTIFACT_ROOT", "/artifacts")),
            )
            self.load_error = None
        except Exception as error:
            self.loaded = None
            self.load_error = f"{type(error).__name__}: {error}"
            LOGGER.error(json.dumps({"event": "model_load_failed", "error": self.load_error}))


def error_response(status: int, code: str, message: str, correlation_id: str) -> JSONResponse:
    return JSONResponse(
        status_code=status,
        content={"error": {"code": code, "message": message}, "correlation_id": correlation_id},
        headers={"X-Correlation-ID": correlation_id},
    )


def create_app(container: ModelContainer | None = None, *, load_on_start: bool = True) -> FastAPI:
    model_container = container or ModelContainer()

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        if load_on_start and model_container.loaded is None:
            model_container.load()
        yield

    app = FastAPI(
        title="Housing Price Prediction Model API",
        version="1.0.0",
        openapi_version="3.1.0",
        lifespan=lifespan,
    )
    origins = [
        value.strip()
        for value in os.environ.get("ALLOWED_ORIGINS", "http://localhost:3000").split(",")
        if value.strip()
    ]
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        allow_credentials=False,
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type", "X-Correlation-ID"],
    )

    @app.middleware("http")
    async def correlation_and_logging(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        supplied = request.headers.get("X-Correlation-ID")
        if supplied:
            try:
                correlation_id = str(UUID(supplied))
            except ValueError:
                return error_response(
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
        operation = request.url.path
        REQUESTS.labels(operation=operation, status=str(response.status_code)).inc()
        LATENCY.labels(operation=operation).observe(duration)
        LOGGER.info(
            json.dumps(
                {
                    "service": "model-service",
                    "operation": operation,
                    "correlation_id": correlation_id,
                    "status": response.status_code,
                    "latency_ms": round(duration * 1000, 3),
                }
            )
        )
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, error: RequestValidationError) -> JSONResponse:
        fields = [
            {
                "field": ".".join(str(part) for part in item["loc"] if part != "body"),
                "message": item["msg"],
            }
            for item in error.errors()
        ]
        correlation_id = getattr(request.state, "correlation_id", str(uuid4()))
        return JSONResponse(
            status_code=422,
            content={
                "error": {
                    "code": "VALIDATION_ERROR",
                    "message": "Prediction request is invalid.",
                    "fields": fields,
                },
                "correlation_id": correlation_id,
            },
            headers={"X-Correlation-ID": correlation_id},
        )

    @app.get("/health", tags=["health"])
    @app.get("/health/live", tags=["health"])
    async def health() -> dict[str, str]:
        return {"status": "ok", "service": "model-service"}

    @app.get("/health/ready", tags=["health"])
    async def ready(request: Request) -> Response:
        if model_container.loaded is None:
            return error_response(
                503,
                "MODEL_NOT_READY",
                "No verified active model is loaded.",
                request.state.correlation_id,
            )
        return JSONResponse(
            {"status": "ready", "model_version": model_container.loaded.model_version}
        )

    @app.post("/api/v1/predict", response_model=PredictResponse, tags=["prediction"])
    async def predict(payload: PredictRequest, request: Request) -> Any:
        loaded = model_container.loaded
        if loaded is None:
            return error_response(
                503,
                "MODEL_NOT_READY",
                "No verified active model is loaded.",
                request.state.correlation_id,
            )
        results: list[PredictionResult] = []
        for index, item in enumerate(payload.items()):
            raw = loaded.model.predict(item.vector())[0]
            try:
                price = price_from_raw(raw)
            except ValueError:
                PREDICTION_FAILURES.inc()
                return error_response(
                    422,
                    "MODEL_OUTPUT_OUT_OF_DOMAIN",
                    f"Model output at input index {index} cannot be represented as a price.",
                    request.state.correlation_id,
                )
            results.append(
                PredictionResult(
                    predicted_price=price,
                    model_version=loaded.model_version,
                    warnings=item.warnings(),
                )
            )
        return PredictResponse(predictions=results)

    @app.get("/api/v1/model-info", tags=["model"], response_model=None)
    async def model_info(request: Request) -> dict[str, Any] | Response:
        loaded = model_container.loaded
        if loaded is None:
            return error_response(
                503,
                "MODEL_NOT_READY",
                "No verified active model is loaded.",
                request.state.correlation_id,
            )
        registry = loaded.registry
        return {
            "model_version": loaded.model_version,
            "model_type": registry["model_type"],
            "feature_names": loaded.metadata["feature_names"],
            "coefficients": loaded.metadata["coefficients"],
            "intercept": loaded.metadata["intercept"],
            "dataset_version_id": str(registry["dataset_version_id"]),
            "training_run_id": str(registry["training_run_id"]),
            "snapshot_sha256": str(registry["snapshot_sha256"]).strip(),
            "metrics": {
                "mae_usd": f"{registry['mae']:.12f}",
                "rmse_usd": f"{registry['rmse']:.12f}",
                "r2": f"{registry['r2']:.12f}",
                "mape_percentage_points": f"{registry['mape']:.12f}",
            },
            "acceptance_policy_version": registry["acceptance_policy_version"],
            "evaluated_at": registry["evaluated_at"],
        }

    @app.get("/metrics", include_in_schema=False)
    async def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    app.state.model_container = model_container
    return app

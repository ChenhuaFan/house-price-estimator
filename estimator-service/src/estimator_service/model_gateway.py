from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from typing import Protocol
from uuid import UUID

import httpx

from .domain import PropertyInput


@dataclass(frozen=True)
class ModelPrediction:
    predicted_price: Decimal
    model_version: str


class ModelRejected(Exception):
    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


class ModelUnavailable(Exception):
    pass


class ModelGateway(Protocol):
    async def ready(self, correlation_id: UUID) -> None: ...

    async def predict(
        self, property_input: PropertyInput, correlation_id: UUID
    ) -> ModelPrediction: ...


class HttpModelGateway:
    def __init__(
        self,
        base_url: str,
        timeout: httpx.Timeout,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout
        self._transport = transport

    async def ready(self, correlation_id: UUID) -> None:
        async with httpx.AsyncClient(timeout=self._timeout, transport=self._transport) as client:
            response = await client.get(
                f"{self._base_url}/health/ready",
                headers={"X-Correlation-ID": str(correlation_id)},
            )
            response.raise_for_status()

    async def predict(self, property_input: PropertyInput, correlation_id: UUID) -> ModelPrediction:
        try:
            async with httpx.AsyncClient(
                timeout=self._timeout, transport=self._transport
            ) as client:
                response = await client.post(
                    f"{self._base_url}/api/v1/predict",
                    json={"property": _prediction_payload(property_input)},
                    headers={"X-Correlation-ID": str(correlation_id)},
                )
        except httpx.HTTPError as error:
            raise ModelUnavailable from error

        if response.status_code != 200:
            code, message = _downstream_error(response)
            raise ModelRejected(response.status_code, code, message)
        try:
            body = response.json()
            prediction = body["predictions"][0]
            price = Decimal(prediction["predicted_price"])
            if not price.is_finite() or price < 0 or price.as_tuple().exponent != -2:
                raise ValueError("model service returned a non-canonical price")
            model_version = prediction["model_version"]
            if not isinstance(model_version, str) or not model_version:
                raise ValueError("model service returned an invalid model version")
        except (KeyError, IndexError, TypeError, InvalidOperation, ValueError) as error:
            raise ModelUnavailable from error
        return ModelPrediction(price, model_version)


def _downstream_error(response: httpx.Response) -> tuple[str, str]:
    code = "MODEL_SERVICE_ERROR"
    message = "Prediction service rejected the request."
    if response.headers.get("content-type", "").startswith("application/json"):
        try:
            body = response.json()
            downstream_error = body.get("error", {})
            if isinstance(downstream_error, dict):
                if isinstance(downstream_error.get("code"), str):
                    code = downstream_error["code"]
                if isinstance(downstream_error.get("message"), str):
                    message = downstream_error["message"]
        except ValueError:
            pass
    return code, message


def _prediction_payload(property_input: PropertyInput) -> dict[str, int | float]:
    return {
        "square_footage": property_input.square_footage,
        "bedrooms": property_input.bedrooms,
        "bathrooms": float(property_input.bathrooms),
        "year_built": property_input.year_built,
        "lot_size": property_input.lot_size,
        "distance_to_city_center": float(property_input.distance_to_city_center),
        "school_rating": float(property_input.school_rating),
    }

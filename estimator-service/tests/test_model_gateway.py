import json
from decimal import Decimal
from uuid import UUID

import httpx
import pytest

from estimator_service.domain import PropertyInput
from estimator_service.model_gateway import (
    HttpModelGateway,
    ModelRejected,
    ModelUnavailable,
)

CORRELATION_ID = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")


def property_input() -> PropertyInput:
    return PropertyInput(
        square_footage=1800,
        bedrooms=3,
        bathrooms=Decimal("2.5"),
        year_built=2005,
        lot_size=6500,
        distance_to_city_center=Decimal("4.125"),
        school_rating=Decimal("8.5"),
    )


def gateway(handler: httpx.MockTransport) -> HttpModelGateway:
    return HttpModelGateway("http://model", httpx.Timeout(1), transport=handler)


@pytest.mark.asyncio
async def test_predict_maps_canonical_model_response() -> None:
    async def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Correlation-ID"] == str(CORRELATION_ID)
        assert request.url.path == "/api/v1/predict"
        assert json.loads(request.content)["property"] == {
            "square_footage": 1800,
            "bedrooms": 3,
            "bathrooms": 2.5,
            "year_built": 2005,
            "lot_size": 6500,
            "distance_to_city_center": 4.125,
            "school_rating": 8.5,
        }
        return httpx.Response(
            200,
            json={
                "predictions": [
                    {
                        "predicted_price": "250000.00",
                        "model_version": "housing-linear-test",
                        "warnings": [],
                    }
                ]
            },
        )

    result = await gateway(httpx.MockTransport(handler)).predict(property_input(), CORRELATION_ID)

    assert result.predicted_price == Decimal("250000.00")
    assert result.model_version == "housing-linear-test"


@pytest.mark.asyncio
async def test_predict_preserves_structured_model_rejection() -> None:
    transport = httpx.MockTransport(
        lambda _: httpx.Response(
            422,
            json={"error": {"code": "MODEL_OUTPUT_OUT_OF_DOMAIN", "message": "bad"}},
        )
    )

    with pytest.raises(ModelRejected) as raised:
        await gateway(transport).predict(property_input(), CORRELATION_ID)

    assert raised.value.status_code == 422
    assert raised.value.code == "MODEL_OUTPUT_OUT_OF_DOMAIN"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "body",
    [
        {"predictions": []},
        {"predictions": [{"predicted_price": "1", "model_version": "v", "warnings": []}]},
        {"predictions": [{"predicted_price": "NaN", "model_version": "v", "warnings": []}]},
    ],
)
async def test_predict_rejects_malformed_success_response(body: object) -> None:
    transport = httpx.MockTransport(lambda _: httpx.Response(200, json=body))

    with pytest.raises(ModelUnavailable):
        await gateway(transport).predict(property_input(), CORRELATION_ID)


@pytest.mark.asyncio
async def test_readiness_raises_for_unready_model() -> None:
    transport = httpx.MockTransport(lambda _: httpx.Response(503))

    with pytest.raises(httpx.HTTPStatusError):
        await gateway(transport).ready(CORRELATION_ID)

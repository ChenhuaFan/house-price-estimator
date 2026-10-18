from datetime import UTC, datetime
from decimal import Decimal
from typing import Any
from uuid import UUID

import numpy as np
from fastapi.testclient import TestClient

from model_service.app import ModelContainer, create_app
from model_service.runtime import LoadedModel

CORRELATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
DATASET_ID = UUID("00000000-0000-4000-8000-000000000001")
RUN_ID = UUID("11111111-1111-4111-8111-111111111111")


class StubModel:
    def __init__(self, outputs: list[float]) -> None:
        self.outputs = iter(outputs)
        self.inputs: list[list[list[float]]] = []

    def predict(self, value: np.ndarray[Any, Any]) -> np.ndarray[Any, Any]:
        self.inputs.append(value.tolist())
        return np.asarray([next(self.outputs)])


def property_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "square_footage": 1800,
        "bedrooms": 3,
        "bathrooms": 2.5,
        "year_built": 2005,
        "lot_size": 6500,
        "distance_to_city_center": 4.125,
        "school_rating": 8.5,
    }
    payload.update(overrides)
    return payload


def loaded_model(outputs: list[float]) -> LoadedModel:
    return LoadedModel(
        model=StubModel(outputs),
        model_version="housing-linear-test",
        metadata={
            "feature_names": [
                "square_footage",
                "bedrooms",
                "bathrooms",
                "year_built",
                "lot_size",
                "distance_to_city_center",
                "school_rating",
            ],
            "coefficients": [1.0] * 7,
            "intercept": 42.0,
        },
        registry={
            "model_type": "LinearRegression",
            "dataset_version_id": DATASET_ID,
            "training_run_id": RUN_ID,
            "snapshot_sha256": "a" * 64,
            "mae": Decimal("1.25"),
            "rmse": Decimal("2.50"),
            "r2": Decimal("0.90"),
            "mape": Decimal("0.75"),
            "acceptance_policy_version": "v1",
            "evaluated_at": datetime(2026, 1, 1, tzinfo=UTC),
        },
    )


def client(outputs: list[float] | None = None) -> TestClient:
    container = ModelContainer(loaded_model(outputs or [250000.0]))
    return TestClient(create_app(container, load_on_start=False))


def test_health_readiness_and_model_metadata() -> None:
    with client() as api:
        assert api.get("/health/live").json()["status"] == "ok"
        ready = api.get("/health/ready")
        info = api.get("/api/v1/model-info")

    assert ready.status_code == 200
    assert ready.json() == {"status": "ready", "model_version": "housing-linear-test"}
    assert info.status_code == 200
    assert info.json()["metrics"]["mae_usd"] == "1.250000000000"
    assert info.json()["feature_names"][0] == "square_footage"


def test_single_prediction_preserves_correlation_and_advisory_warning() -> None:
    with client([123456.789]) as api:
        response = api.post(
            "/api/v1/predict",
            json={"property": property_payload(school_rating=11)},
            headers={"X-Correlation-ID": CORRELATION_ID},
        )

    assert response.status_code == 200
    assert response.headers["X-Correlation-ID"] == CORRELATION_ID
    assert response.json()["predictions"] == [
        {
            "predicted_price": "123456.79",
            "currency": "USD",
            "model_version": "housing-linear-test",
            "warnings": [
                {
                    "field": "school_rating",
                    "submitted_value": "11",
                    "advisory_min": "1",
                    "advisory_max": "10",
                    "reason": "School rating is outside the advisory 1-10 range.",
                }
            ],
        }
    ]


def test_batch_prediction_keeps_input_order() -> None:
    with client([100.0, 200.0]) as api:
        response = api.post(
            "/api/v1/predict",
            json={"properties": [property_payload(), property_payload(square_footage=2000)]},
        )

    assert response.status_code == 200
    assert [item["predicted_price"] for item in response.json()["predictions"]] == [
        "100.00",
        "200.00",
    ]


def test_invalid_request_returns_stable_error_contract() -> None:
    with client() as api:
        response = api.post(
            "/api/v1/predict",
            json={"property": property_payload(bathrooms=2.25)},
            headers={"X-Correlation-ID": CORRELATION_ID},
        )

    assert response.status_code == 422
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"
    assert response.json()["correlation_id"] == CORRELATION_ID
    assert response.headers["X-Correlation-ID"] == CORRELATION_ID


def test_invalid_correlation_id_is_rejected_before_routing() -> None:
    with client() as api:
        response = api.get("/health/live", headers={"X-Correlation-ID": "not-a-uuid"})

    assert response.status_code == 400
    assert response.json()["error"]["code"] == "INVALID_CORRELATION_ID"


def test_not_ready_and_bad_model_output_are_explicit() -> None:
    unavailable = TestClient(create_app(ModelContainer(), load_on_start=False))
    with unavailable as api:
        not_ready = api.post("/api/v1/predict", json={"property": property_payload()})
    with client([-1.0]) as api:
        invalid_output = api.post("/api/v1/predict", json={"property": property_payload()})

    assert not_ready.status_code == 503
    assert not_ready.json()["error"]["code"] == "MODEL_NOT_READY"
    assert invalid_output.status_code == 422
    assert invalid_output.json()["error"]["code"] == "MODEL_OUTPUT_OUT_OF_DOMAIN"

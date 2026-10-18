from datetime import UTC, datetime
from decimal import Decimal
from uuid import UUID

from fastapi.testclient import TestClient

from estimator_service.app import create_app
from estimator_service.domain import (
    AdvisoryWarning,
    CreateEstimateRequest,
    Estimate,
    HistoryPage,
    PropertyInput,
)
from estimator_service.model_gateway import (
    ModelPrediction,
    ModelRejected,
    ModelUnavailable,
)

CLIENT_ID = UUID("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
REQUEST_ID = UUID("11111111-1111-4111-8111-111111111111")
CORRELATION_ID = UUID("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
ESTIMATE_ID = UUID("22222222-2222-4222-8222-222222222222")


def payload(**overrides: object) -> dict[str, object]:
    values: dict[str, object] = {
        "request_id": str(REQUEST_ID),
        "square_footage": 1800,
        "bedrooms": 3,
        "bathrooms": 2.5,
        "year_built": 2005,
        "lot_size": 6500,
        "distance_to_city_center": 4.125,
        "school_rating": 8.5,
    }
    values.update(overrides)
    return values


def make_estimate(request: CreateEstimateRequest) -> Estimate:
    return Estimate(
        estimate_id=ESTIMATE_ID,
        request_id=request.request_id,
        created_at=datetime(2026, 1, 1, tzinfo=UTC),
        inputs=request.property_input(),
        predicted_price="250000.00",
        model_version="housing-linear-test",
        warnings=[],
    )


class FakeStore:
    def __init__(self) -> None:
        self.estimates: dict[UUID, Estimate] = {}
        self.ready = True
        self.create_calls = 0

    async def ping(self) -> None:
        if not self.ready:
            raise RuntimeError("database unavailable")

    async def find_by_request(self, client_id: UUID, request_id: UUID) -> Estimate | None:
        assert client_id == CLIENT_ID
        return self.estimates.get(request_id)

    async def create(
        self,
        client_id: UUID,
        request: CreateEstimateRequest,
        correlation_id: UUID,
        predicted_price: Decimal,
        model_version: str,
        warnings: list[AdvisoryWarning],
    ) -> tuple[Estimate, bool]:
        assert client_id == CLIENT_ID
        assert correlation_id == CORRELATION_ID
        assert predicted_price == Decimal("250000.00")
        assert model_version == "housing-linear-test"
        self.create_calls += 1
        estimate = make_estimate(request).model_copy(update={"warnings": warnings})
        self.estimates[request.request_id] = estimate
        return estimate, True

    async def history(
        self,
        client_id: UUID,
        limit: int,
        cursor: tuple[datetime, UUID] | None,
    ) -> HistoryPage:
        assert client_id == CLIENT_ID
        assert cursor is None
        return HistoryPage(items=list(self.estimates.values())[:limit], next_cursor=None)

    async def delete_one(self, client_id: UUID, estimate_id: UUID) -> bool:
        assert client_id == CLIENT_ID
        for request_id, estimate in list(self.estimates.items()):
            if estimate.estimate_id == estimate_id:
                del self.estimates[request_id]
                return True
        return False

    async def clear(self, client_id: UUID) -> int:
        assert client_id == CLIENT_ID
        count = len(self.estimates)
        self.estimates.clear()
        return count


class FakeModelGateway:
    def __init__(self) -> None:
        self.ready_state = True
        self.predict_calls = 0
        self.error: Exception | None = None

    async def ready(self, correlation_id: UUID) -> None:
        if not self.ready_state:
            raise RuntimeError("model unavailable")

    async def predict(self, property_input: PropertyInput, correlation_id: UUID) -> ModelPrediction:
        assert property_input.square_footage > 0
        assert correlation_id == CORRELATION_ID
        self.predict_calls += 1
        if self.error is not None:
            raise self.error
        return ModelPrediction(Decimal("250000.00"), "housing-linear-test", [])


def api(
    store: FakeStore | None = None, gateway: FakeModelGateway | None = None
) -> tuple[TestClient, FakeStore, FakeModelGateway]:
    resolved_store = store or FakeStore()
    resolved_gateway = gateway or FakeModelGateway()
    return (
        TestClient(create_app(resolved_store, resolved_gateway)),
        resolved_store,
        resolved_gateway,
    )


def headers() -> dict[str, str]:
    return {
        "X-Estimator-Client-ID": str(CLIENT_ID),
        "X-Correlation-ID": str(CORRELATION_ID),
    }


def test_create_replay_and_conflict_cover_idempotency_contract() -> None:
    client, store, gateway = api()
    with client:
        created = client.post("/api/v1/estimates", json=payload(), headers=headers())
        replayed = client.post("/api/v1/estimates", json=payload(), headers=headers())
        conflict = client.post(
            "/api/v1/estimates",
            json=payload(square_footage=1900),
            headers=headers(),
        )

    assert created.status_code == 201
    assert created.headers["X-Correlation-ID"] == str(CORRELATION_ID)
    assert replayed.status_code == 200
    assert replayed.json() == created.json()
    assert conflict.status_code == 409
    assert conflict.json()["error"]["code"] == "IDEMPOTENCY_KEY_REUSED"
    assert gateway.predict_calls == 1
    assert store.create_calls == 1


def test_model_failures_are_not_persisted() -> None:
    gateway = FakeModelGateway()
    gateway.error = ModelUnavailable()
    client, store, _ = api(gateway=gateway)

    with client:
        response = client.post("/api/v1/estimates", json=payload(), headers=headers())

    assert response.status_code == 503
    assert response.json()["error"]["code"] == "MODEL_SERVICE_UNAVAILABLE"
    assert store.estimates == {}


def test_structured_model_rejection_is_preserved() -> None:
    gateway = FakeModelGateway()
    gateway.error = ModelRejected(422, "MODEL_OUTPUT_OUT_OF_DOMAIN", "bad output")
    client, _, _ = api(gateway=gateway)

    with client:
        response = client.post("/api/v1/estimates", json=payload(), headers=headers())

    assert response.status_code == 422
    assert response.json()["error"] == {
        "code": "MODEL_OUTPUT_OUT_OF_DOMAIN",
        "message": "bad output",
    }


def test_history_delete_and_clear_are_scoped_to_client() -> None:
    client, _, _ = api()
    with client:
        created = client.post("/api/v1/estimates", json=payload(), headers=headers())
        history = client.get("/api/v1/estimates?limit=20", headers=headers())
        deleted = client.delete(
            f"/api/v1/estimates/{created.json()['estimate_id']}", headers=headers()
        )
        missing = client.delete(
            f"/api/v1/estimates/{created.json()['estimate_id']}", headers=headers()
        )
        cleared = client.delete("/api/v1/estimates", headers=headers())

    assert history.status_code == 200
    assert len(history.json()["items"]) == 1
    assert deleted.status_code == 204
    assert missing.status_code == 404
    assert cleared.status_code == 204


def test_validation_and_identity_errors_are_stable() -> None:
    client, _, _ = api()
    invalid_client = headers() | {"X-Estimator-Client-ID": "11111111-1111-1111-8111-111111111111"}
    with client:
        missing = client.post("/api/v1/estimates", json=payload())
        wrong_version = client.post("/api/v1/estimates", json=payload(), headers=invalid_client)
        malformed = client.post(
            "/api/v1/estimates",
            json=payload(bathrooms=2.25),
            headers=headers(),
        )
        invalid_cursor = client.get("/api/v1/estimates?cursor=invalid", headers=headers())
        bad_correlation = client.get("/health/live", headers={"X-Correlation-ID": "invalid"})

    assert missing.status_code == 400
    assert missing.json()["error"]["code"] == "INVALID_CLIENT_ID"
    assert wrong_version.status_code == 400
    assert wrong_version.json()["error"]["code"] == "INVALID_CLIENT_ID"
    assert malformed.status_code == 422
    assert malformed.json()["error"]["code"] == "VALIDATION_ERROR"
    assert invalid_cursor.status_code == 400
    assert invalid_cursor.json()["error"]["code"] == "INVALID_HISTORY_REQUEST"
    assert bad_correlation.status_code == 400
    assert bad_correlation.json()["error"]["code"] == "INVALID_CORRELATION_ID"


def test_readiness_checks_both_dependencies() -> None:
    store = FakeStore()
    gateway = FakeModelGateway()
    client, _, _ = api(store, gateway)
    with client:
        healthy = client.get("/health/ready", headers=headers())
        gateway.ready_state = False
        unhealthy = client.get("/health/ready", headers=headers())

    assert healthy.status_code == 200
    assert unhealthy.status_code == 503
    assert unhealthy.json()["error"]["code"] == "DEPENDENCY_NOT_READY"

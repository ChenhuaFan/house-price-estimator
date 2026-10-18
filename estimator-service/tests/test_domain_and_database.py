from datetime import UTC, datetime
from decimal import Decimal
from uuid import UUID

import pytest
from pydantic import ValidationError

from estimator_service.database import cursor_decode
from estimator_service.domain import CreateEstimateRequest, PropertyInput


def valid_request(**overrides: object) -> dict[str, object]:
    values: dict[str, object] = {
        "request_id": "11111111-1111-4111-8111-111111111111",
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


def test_request_exposes_only_canonical_property_fields() -> None:
    request = CreateEstimateRequest.model_validate(valid_request())

    assert request.property_input().canonical_tuple() == (
        1800,
        3,
        Decimal("2.5"),
        2005,
        6500,
        Decimal("4.125"),
        Decimal("8.5"),
    )


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("request_id", "00000000-0000-0000-0000-000000000001"),
        ("square_footage", 0),
        ("bedrooms", True),
        ("bathrooms", "2.5"),
        ("bathrooms", 1.25),
        ("distance_to_city_center", -1),
        ("school_rating", float("inf")),
    ],
)
def test_request_rejects_values_outside_the_contract(field: str, value: object) -> None:
    with pytest.raises(ValidationError):
        CreateEstimateRequest.model_validate(valid_request(**{field: value}))


def test_cursor_decode_accepts_urlsafe_unpadded_cursor() -> None:
    # Encoding for {"created_at":"2026-01-01T00:00:00+00:00", "estimate_id": UUID}.
    import base64
    import json

    instant = datetime(2026, 1, 1, tzinfo=UTC)
    estimate_id = UUID("22222222-2222-4222-8222-222222222222")
    raw = json.dumps(
        {"created_at": instant.isoformat(), "estimate_id": str(estimate_id)},
        separators=(",", ":"),
    ).encode()
    cursor = base64.urlsafe_b64encode(raw).decode().rstrip("=")

    assert cursor_decode(cursor) == (instant, estimate_id)


@pytest.mark.parametrize("cursor", ["not-base64", "e30", "bnVsbA"])
def test_cursor_decode_rejects_malformed_values(cursor: str) -> None:
    with pytest.raises(ValueError, match="invalid history cursor"):
        cursor_decode(cursor)


def test_property_model_forbids_unknown_fields() -> None:
    with pytest.raises(ValidationError):
        PropertyInput.model_validate(
            {
                key: value
                for key, value in valid_request(unexpected="value").items()
                if key != "request_id"
            }
        )

from decimal import Decimal

import pytest
from pydantic import ValidationError

from model_service.domain import PredictRequest, PropertyInput, price_from_raw


def valid_property(**overrides: object) -> dict[str, object]:
    values: dict[str, object] = {
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


def test_property_vector_uses_the_contract_order() -> None:
    property_input = PropertyInput.model_validate(valid_property())

    assert property_input.vector().tolist() == [[1800.0, 3.0, 2.5, 2005.0, 6500.0, 4.125, 8.5]]


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("square_footage", True),
        ("bedrooms", 2.0),
        ("bathrooms", "2.5"),
        ("bathrooms", 2.25),
        ("distance_to_city_center", -0.1),
        ("school_rating", float("nan")),
    ],
)
def test_property_rejects_non_contract_values(field: str, value: object) -> None:
    with pytest.raises(ValidationError):
        PropertyInput.model_validate(valid_property(**{field: value}))


def test_school_rating_outside_advisory_range_is_warning_not_error() -> None:
    property_input = PropertyInput.model_validate(valid_property(school_rating=Decimal("10.001")))

    assert [warning.field for warning in property_input.warnings()] == ["school_rating"]
    assert property_input.warnings()[0].submitted_value == "10.001"


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"property": valid_property(), "properties": [valid_property()]},
        {"properties": []},
        {"properties": [valid_property()] * 1001},
    ],
)
def test_prediction_request_requires_exactly_one_bounded_mode(
    payload: dict[str, object],
) -> None:
    with pytest.raises(ValidationError):
        PredictRequest.model_validate(payload)


@pytest.mark.parametrize(
    ("raw", "expected"),
    [(1.005, "1.01"), (0, "0.00"), (Decimal("999.999"), "1000.00")],
)
def test_price_is_canonical_usd(raw: object, expected: str) -> None:
    assert price_from_raw(raw) == expected


@pytest.mark.parametrize("raw", [-1, float("nan"), float("inf"), "invalid", 10**16])
def test_price_rejects_unrepresentable_model_output(raw: object) -> None:
    with pytest.raises(ValueError, match="MODEL_OUTPUT_OUT_OF_DOMAIN"):
        price_from_raw(raw)

from __future__ import annotations

import math
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Annotated, Any, Literal

import numpy as np
from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator, model_validator

FEATURES = (
    "square_footage",
    "bedrooms",
    "bathrooms",
    "year_built",
    "lot_size",
    "distance_to_city_center",
    "school_rating",
)


def _decimal(value: Any, field_name: str, scale: int, *, minimum: Decimal | None = None) -> Decimal:
    if isinstance(value, bool) or isinstance(value, str) or value is None:
        raise ValueError(f"{field_name} must be a JSON number")
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise ValueError(f"{field_name} must be a finite decimal number") from None
    if not result.is_finite():
        raise ValueError(f"{field_name} must be finite")
    normalized = result.normalize()
    effective_scale = max(0, -normalized.as_tuple().exponent)
    if effective_scale > scale:
        raise ValueError(f"{field_name} supports at most {scale} fractional decimal places")
    if minimum is not None and result < minimum:
        raise ValueError(f"{field_name} must be at least {minimum}")
    integer_digits = result.copy_abs().adjusted() + 1 if result else 1
    if integer_digits > 12:
        raise ValueError(f"{field_name} is outside its representable range")
    return result


class PropertyInput(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    square_footage: Annotated[StrictInt, Field(ge=1, le=9_223_372_036_854_775_807)]
    bedrooms: Annotated[StrictInt, Field(ge=0, le=2_147_483_647)]
    bathrooms: Decimal
    year_built: Annotated[StrictInt, Field(ge=1, le=2_147_483_647)]
    lot_size: Annotated[StrictInt, Field(ge=0, le=9_223_372_036_854_775_807)]
    distance_to_city_center: Decimal
    school_rating: Decimal

    @field_validator("bathrooms", mode="before")
    @classmethod
    def validate_bathrooms(cls, value: Any) -> Decimal:
        result = _decimal(value, "bathrooms", 1, minimum=Decimal("0.5"))
        if result % Decimal("0.5") != 0:
            raise ValueError("bathrooms must be in increments of 0.5")
        if result >= Decimal("100000000000"):
            raise ValueError("bathrooms is outside numeric(12,1)")
        return result

    @field_validator("distance_to_city_center", mode="before")
    @classmethod
    def validate_distance(cls, value: Any) -> Decimal:
        result = _decimal(value, "distance_to_city_center", 3, minimum=Decimal("0"))
        if result >= Decimal("1000000000000"):
            raise ValueError("distance_to_city_center is outside numeric(15,3)")
        return result

    @field_validator("school_rating", mode="before")
    @classmethod
    def validate_rating(cls, value: Any) -> Decimal:
        result = _decimal(value, "school_rating", 3)
        if abs(result) >= Decimal("1000000000000"):
            raise ValueError("school_rating is outside numeric(15,3)")
        return result

    def vector(self) -> np.ndarray[Any, Any]:
        return np.asarray([[float(getattr(self, name)) for name in FEATURES]], dtype=np.float64)

    def warnings(self) -> list["AdvisoryWarning"]:
        if Decimal("1") <= self.school_rating <= Decimal("10"):
            return []
        return [
            AdvisoryWarning(
                field="school_rating",
                submitted_value=f"{self.school_rating:f}",
                advisory_min="1",
                advisory_max="10",
                reason="School rating is outside the advisory 1-10 range.",
            )
        ]


class PredictRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    property: PropertyInput | None = None
    properties: list[PropertyInput] | None = None

    @model_validator(mode="after")
    def exactly_one_mode(self) -> "PredictRequest":
        if (self.property is None) == (self.properties is None):
            raise ValueError("provide exactly one of property or properties")
        if self.properties is not None and not 1 <= len(self.properties) <= 1000:
            raise ValueError("properties must contain between 1 and 1000 records")
        return self

    def items(self) -> list[PropertyInput]:
        return [self.property] if self.property is not None else list(self.properties or [])


class AdvisoryWarning(BaseModel):
    field: str
    submitted_value: str
    advisory_min: str | None
    advisory_max: str | None
    reason: str


class PredictionResult(BaseModel):
    predicted_price: str
    currency: Literal["USD"] = "USD"
    model_version: str
    warnings: list[AdvisoryWarning]


class PredictResponse(BaseModel):
    predictions: list[PredictionResult]


def price_from_raw(raw_value: Any) -> str:
    try:
        numeric = float(raw_value)
    except (TypeError, ValueError):
        raise ValueError("MODEL_OUTPUT_OUT_OF_DOMAIN") from None
    if not math.isfinite(numeric):
        raise ValueError("MODEL_OUTPUT_OUT_OF_DOMAIN")
    value = Decimal(str(numeric))
    if value < 0:
        raise ValueError("MODEL_OUTPUT_OUT_OF_DOMAIN")
    rounded = value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    if rounded >= Decimal("10000000000000000"):
        raise ValueError("MODEL_OUTPUT_OUT_OF_DOMAIN")
    return f"{rounded:.2f}"

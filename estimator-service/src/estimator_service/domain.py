from __future__ import annotations

from datetime import datetime
from decimal import Decimal, InvalidOperation
from typing import Annotated, Any, Literal, cast
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator

FEATURES = (
    "square_footage",
    "bedrooms",
    "bathrooms",
    "year_built",
    "lot_size",
    "distance_to_city_center",
    "school_rating",
)


def _decimal(value: Any, field_name: str, scale: int, minimum: Decimal | None = None) -> Decimal:
    if isinstance(value, bool | str) or value is None:
        raise ValueError(f"{field_name} must be a JSON number")
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise ValueError(f"{field_name} must be a finite decimal number") from None
    if not result.is_finite():
        raise ValueError(f"{field_name} must be finite")
    exponent = cast(int, result.normalize().as_tuple().exponent)
    if max(0, -exponent) > scale:
        raise ValueError(f"{field_name} supports at most {scale} fractional decimal places")
    if minimum is not None and result < minimum:
        raise ValueError(f"{field_name} must be at least {minimum}")
    return result


class PropertyInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

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
        result = _decimal(value, "bathrooms", 1, Decimal("0.5"))
        if result % Decimal("0.5") != 0:
            raise ValueError("bathrooms must be in increments of 0.5")
        if result >= Decimal("100000000000"):
            raise ValueError("bathrooms is outside numeric(12,1)")
        return result

    @field_validator("distance_to_city_center", mode="before")
    @classmethod
    def validate_distance(cls, value: Any) -> Decimal:
        result = _decimal(value, "distance_to_city_center", 3, Decimal("0"))
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

    def canonical_tuple(self) -> tuple[Any, ...]:
        return tuple(getattr(self, field) for field in FEATURES)


class CreateEstimateRequest(PropertyInput):
    request_id: UUID

    @field_validator("request_id")
    @classmethod
    def validate_request_id(cls, value: UUID) -> UUID:
        if value.version != 4:
            raise ValueError("request_id must be a UUIDv4")
        return value

    def property_input(self) -> PropertyInput:
        return PropertyInput.model_validate(self.model_dump(exclude={"request_id"}))


class Estimate(BaseModel):
    estimate_id: UUID
    request_id: UUID
    created_at: datetime
    inputs: PropertyInput
    predicted_price: str
    currency: Literal["USD"] = "USD"
    model_version: str


class HistoryPage(BaseModel):
    items: list[Estimate]
    next_cursor: str | None

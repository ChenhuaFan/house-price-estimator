from decimal import Decimal

import numpy as np
import pytest

from housing_training.dataset import arrays, canonical_snapshot_bytes, load_snapshot


def row(source_id: int, **overrides: object) -> dict[str, object]:
    values: dict[str, object] = {
        "source_id": source_id,
        "square_footage": 1800,
        "bedrooms": 3,
        "bathrooms": Decimal("2.5"),
        "year_built": 2005,
        "lot_size": 6500,
        "distance_to_city_center": Decimal("4.125"),
        "school_rating": Decimal("8.500"),
        "price": Decimal("250000.00"),
    }
    values.update(overrides)
    return values


def test_canonical_snapshot_has_fixed_order_scale_and_newlines() -> None:
    result = canonical_snapshot_bytes([row(1)])

    assert result == (
        b"source_id,square_footage,bedrooms,bathrooms,year_built,lot_size,"
        b"distance_to_city_center,school_rating,price\n"
        b"1,1800,3,2.5,2005,6500,4.125,8.500,250000.00\n"
    )


def test_load_snapshot_and_arrays_preserve_feature_contract(tmp_path: object) -> None:
    from pathlib import Path

    directory = Path(str(tmp_path))
    snapshot = directory / "dataset.csv"
    snapshot.write_bytes(canonical_snapshot_bytes([row(1), row(2, square_footage=2000)]))

    rows = load_snapshot(snapshot)
    features, target = arrays(rows)

    assert features.tolist()[0] == [1800.0, 3.0, 2.5, 2005.0, 6500.0, 4.125, 8.5]
    assert target.tolist() == [250000.0, 250000.0]


def test_load_snapshot_rejects_wrong_schema_or_too_few_rows(tmp_path: object) -> None:
    from pathlib import Path

    directory = Path(str(tmp_path))
    wrong_schema = directory / "wrong.csv"
    wrong_schema.write_text("source_id,price\n1,2\n", encoding="utf-8")
    one_row = directory / "one.csv"
    one_row.write_bytes(canonical_snapshot_bytes([row(1)]))

    with pytest.raises(ValueError, match="column order"):
        load_snapshot(wrong_schema)
    with pytest.raises(ValueError, match="at least two"):
        load_snapshot(one_row)


def test_arrays_reject_non_finite_values() -> None:
    string_rows = [
        {key: str(value) for key, value in row(1).items()},
        {key: str(value) for key, value in row(2, price=np.inf).items()},
    ]

    with pytest.raises(ValueError, match="non-finite"):
        arrays(string_rows)

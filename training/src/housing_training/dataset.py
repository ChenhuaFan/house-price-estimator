from __future__ import annotations

import csv
import io
from decimal import Decimal
from pathlib import Path
from typing import Any

import numpy as np

from .constants import FEATURES, SNAPSHOT_COLUMNS, TARGET


def _format_snapshot_row(row: dict[str, Any]) -> list[str]:
    return [
        str(row["source_id"]),
        str(row["square_footage"]),
        str(row["bedrooms"]),
        f"{Decimal(row['bathrooms']):.1f}",
        str(row["year_built"]),
        str(row["lot_size"]),
        f"{Decimal(row['distance_to_city_center']):.3f}",
        f"{Decimal(row['school_rating']):.3f}",
        f"{Decimal(row['price']):.2f}",
    ]


def canonical_snapshot_bytes(rows: list[dict[str, Any]]) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.writer(output, lineterminator="\n")
    writer.writerow(SNAPSHOT_COLUMNS)
    for row in rows:
        writer.writerow(_format_snapshot_row(row))
    return output.getvalue().encode("utf-8")


def load_snapshot(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if tuple(reader.fieldnames or ()) != SNAPSHOT_COLUMNS:
            raise ValueError("snapshot column order is invalid")
        rows = list(reader)
    if len(rows) < 2:
        raise ValueError("snapshot must contain at least two rows")
    return rows


def arrays(
    rows: list[dict[str, str]],
) -> tuple[np.ndarray[Any, Any], np.ndarray[Any, Any]]:
    features = np.asarray(
        [[float(row[feature]) for feature in FEATURES] for row in rows], dtype=np.float64
    )
    target = np.asarray([float(row[TARGET]) for row in rows], dtype=np.float64)
    if not np.isfinite(features).all() or not np.isfinite(target).all():
        raise ValueError("snapshot contains non-finite model values")
    return features, target

from __future__ import annotations

import math
from dataclasses import dataclass
from decimal import ROUND_HALF_EVEN, Decimal
from typing import Any

import numpy as np
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

from .constants import METRIC_SCALE, THRESHOLDS


@dataclass(frozen=True)
class EvaluationDecision:
    values: dict[str, str]
    threshold_results: dict[str, bool]
    passed: bool


def metric(value: float) -> Decimal:
    if not math.isfinite(value):
        raise ValueError("metric is not finite")
    return Decimal(str(value)).quantize(METRIC_SCALE, rounding=ROUND_HALF_EVEN)


def evaluate_predictions(
    y_evaluation: np.ndarray[Any, Any],
    predictions: np.ndarray[Any, Any],
    y_training: np.ndarray[Any, Any],
) -> EvaluationDecision:
    if (y_evaluation == 0).any():
        raise ValueError("MAPE is undefined for a zero target")
    if not np.isfinite(predictions).all():
        raise ValueError("predictions contain non-finite values")

    baseline = np.full_like(y_evaluation, y_training.mean(), dtype=np.float64)
    values_decimal = {
        "mae": metric(mean_absolute_error(y_evaluation, predictions)),
        "rmse": metric(math.sqrt(mean_squared_error(y_evaluation, predictions))),
        "r2": metric(r2_score(y_evaluation, predictions)),
        "mape": metric(float(np.mean(np.abs((y_evaluation - predictions) / y_evaluation)) * 100)),
        "baseline_mae": metric(mean_absolute_error(y_evaluation, baseline)),
        "baseline_rmse": metric(math.sqrt(mean_squared_error(y_evaluation, baseline))),
        "baseline_mape": metric(
            float(np.mean(np.abs((y_evaluation - baseline) / y_evaluation)) * 100)
        ),
    }
    threshold_results = {
        "mae": values_decimal["mae"] <= THRESHOLDS["mae"],
        "rmse": values_decimal["rmse"] <= THRESHOLDS["rmse"],
        "r2": values_decimal["r2"] >= THRESHOLDS["r2"],
        "mape": values_decimal["mape"] <= THRESHOLDS["mape"],
        "beats_baseline_mae": values_decimal["mae"] < values_decimal["baseline_mae"],
        "beats_baseline_rmse": values_decimal["rmse"] < values_decimal["baseline_rmse"],
        "beats_baseline_mape": values_decimal["mape"] < values_decimal["baseline_mape"],
    }
    values = {name: f"{value:.12f}" for name, value in values_decimal.items()}
    return EvaluationDecision(values, threshold_results, all(threshold_results.values()))

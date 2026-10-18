from decimal import Decimal

import numpy as np
import pytest

from housing_training.evaluation_policy import evaluate_predictions, metric


def test_perfect_predictions_pass_every_acceptance_gate() -> None:
    actual = np.asarray([100000.0, 200000.0, 300000.0])
    training = np.asarray([90000.0, 150000.0, 350000.0])

    decision = evaluate_predictions(actual, actual.copy(), training)

    assert decision.passed is True
    assert all(decision.threshold_results.values())
    assert decision.values["mae"] == "0.000000000000"
    assert decision.values["r2"] == "1.000000000000"


def test_model_must_strictly_beat_baseline() -> None:
    actual = np.asarray([100000.0, 200000.0, 300000.0])
    training = np.asarray([200000.0, 200000.0, 200000.0])
    baseline_predictions = np.asarray([200000.0, 200000.0, 200000.0])

    decision = evaluate_predictions(actual, baseline_predictions, training)

    assert decision.passed is False
    assert decision.threshold_results["beats_baseline_mae"] is False


def test_evaluation_rejects_undefined_or_non_finite_metrics() -> None:
    with pytest.raises(ValueError, match="zero target"):
        evaluate_predictions(np.asarray([0.0, 1.0]), np.asarray([0.0, 1.0]), np.asarray([1.0, 2.0]))
    with pytest.raises(ValueError, match="non-finite"):
        evaluate_predictions(
            np.asarray([1.0, 2.0]),
            np.asarray([1.0, np.inf]),
            np.asarray([1.0, 2.0]),
        )
    with pytest.raises(ValueError, match="not finite"):
        metric(float("nan"))


def test_metric_uses_fixed_twelve_decimal_scale() -> None:
    assert metric(1.2345678901234) == Decimal("1.234567890123")

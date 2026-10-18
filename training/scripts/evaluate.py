#!/usr/bin/env python3
from __future__ import annotations

import argparse
from uuid import UUID

from housing_training.pipeline import evaluate
from housing_training.settings import Settings


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Evaluate a training run against its held-out split"
    )
    parser.add_argument("--training-run-id", required=True, type=UUID)
    args = parser.parse_args()
    passed = evaluate(Settings.from_environment(), args.training_run_id)
    print(f"acceptance_passed={str(passed).lower()}")
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
from __future__ import annotations

import argparse
from uuid import UUID

from housing_training.pipeline import run_pipeline
from housing_training.settings import Settings


def main() -> int:
    parser = argparse.ArgumentParser(description="Run export, train, evaluate, publish, and promotion")
    parser.add_argument("--dataset-version-id", required=True, type=UUID)
    parser.add_argument("--model-version")
    args = parser.parse_args()
    run_id, model_version = run_pipeline(
        Settings.from_environment(), args.dataset_version_id, args.model_version
    )
    print(f"training_run_id={run_id}")
    print(f"model_version={model_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

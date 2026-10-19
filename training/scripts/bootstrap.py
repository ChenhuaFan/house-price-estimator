#!/usr/bin/env python3
from __future__ import annotations

import argparse
from uuid import UUID

from housing_training.pipeline import run_pipeline
from housing_training.settings import Settings


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run export, train, evaluate, publish, and promotion"
    )
    source = parser.add_mutually_exclusive_group()
    source.add_argument(
        "--dataset-version-id",
        type=UUID,
        help="Reuse an existing immutable dataset instead of snapshotting live business data",
    )
    source.add_argument(
        "--dataset-version-name",
        help="Name for the new business-data snapshot (generated when omitted)",
    )
    parser.add_argument("--model-version")
    args = parser.parse_args()
    run_id, model_version = run_pipeline(
        Settings.from_environment(),
        args.dataset_version_id,
        args.model_version,
        args.dataset_version_name,
    )
    print(f"training_run_id={run_id}")
    print(f"model_version={model_version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

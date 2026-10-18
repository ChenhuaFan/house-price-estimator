#!/usr/bin/env python3
from __future__ import annotations

import argparse
from uuid import UUID, uuid4

from housing_training.pipeline import export_dataset
from housing_training.settings import Settings


def main() -> int:
    parser = argparse.ArgumentParser(description="Export one immutable catalog dataset version")
    parser.add_argument("--dataset-version-id", required=True, type=UUID)
    parser.add_argument("--training-run-id", type=UUID, default=uuid4())
    args = parser.parse_args()
    path = export_dataset(
        Settings.from_environment(), args.dataset_version_id, args.training_run_id
    )
    print(f"training_run_id={args.training_run_id}")
    print(f"snapshot={path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

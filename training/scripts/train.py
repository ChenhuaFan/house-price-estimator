#!/usr/bin/env python3
from __future__ import annotations

import argparse
from uuid import UUID

from housing_training.pipeline import train
from housing_training.settings import Settings


def main() -> int:
    parser = argparse.ArgumentParser(description="Split a snapshot and train the evaluation model")
    parser.add_argument("--training-run-id", required=True, type=UUID)
    args = parser.parse_args()
    path = train(Settings.from_environment(), args.training_run_id)
    print(f"evaluation_model={path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

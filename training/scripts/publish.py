#!/usr/bin/env python3
from __future__ import annotations

import argparse
from uuid import UUID

from housing_training.pipeline import publish
from housing_training.settings import Settings


def main() -> int:
    parser = argparse.ArgumentParser(description="Publish an accepted model candidate")
    parser.add_argument("--training-run-id", required=True, type=UUID)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--promote", action="store_true")
    args = parser.parse_args()
    path = publish(
        Settings.from_environment(),
        args.training_run_id,
        args.model_version,
        promote=args.promote,
    )
    print(f"artifact={path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

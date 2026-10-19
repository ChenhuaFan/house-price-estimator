#!/usr/bin/env python3
from __future__ import annotations

import argparse
from datetime import UTC, datetime
from uuid import uuid4

from housing_training.registry import Registry
from housing_training.settings import Settings


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Freeze the current business store as an immutable training dataset"
    )
    parser.add_argument("--dataset-version-name")
    parser.add_argument("--no-activate", action="store_true")
    args = parser.parse_args()

    name = args.dataset_version_name or (
        "business-snapshot-" + datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ") + "-" + str(uuid4())[:8]
    )
    settings = Settings.from_environment()
    dataset_id = Registry(settings.database_url).snapshot_business_dataset(
        name, activate=not args.no_activate
    )
    print(f"dataset_version_id={dataset_id}")
    print(f"dataset_version_name={name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

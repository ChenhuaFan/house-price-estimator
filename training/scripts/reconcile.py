#!/usr/bin/env python3
from __future__ import annotations

import argparse
from uuid import UUID

from housing_training.failure import reconcile_pending_failure
from housing_training.settings import Settings


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Reconcile a locally pending training failure with the model registry"
    )
    parser.add_argument("--training-run-id", required=True, type=UUID)
    args = parser.parse_args()
    reconciled = reconcile_pending_failure(Settings.from_environment(), args.training_run_id)
    print(f"failure_reconciled={str(reconciled).lower()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

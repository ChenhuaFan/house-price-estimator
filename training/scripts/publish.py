#!/usr/bin/env python3
from __future__ import annotations

import argparse
from uuid import UUID

from housing_training.pipeline import activate_candidate, preflight_candidate, publish
from housing_training.settings import Settings


def main() -> int:
    parser = argparse.ArgumentParser(description="Publish an accepted model candidate")
    parser.add_argument("--training-run-id", type=UUID)
    parser.add_argument("--model-version", required=True)
    parser.add_argument("--promote", action="store_true")
    parser.add_argument("--preflight-only", action="store_true")
    args = parser.parse_args()
    settings = Settings.from_environment()
    if args.preflight_only:
        if args.training_run_id is not None or args.promote:
            parser.error("--preflight-only cannot be combined with --training-run-id or --promote")
        path, _ = preflight_candidate(settings, args.model_version)
    elif args.training_run_id is None:
        if not args.promote:
            parser.error("--training-run-id is required unless --promote activates a candidate")
        path = activate_candidate(settings, args.model_version)
    else:
        path = publish(settings, args.training_run_id, args.model_version, promote=args.promote)
    print(f"artifact={path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

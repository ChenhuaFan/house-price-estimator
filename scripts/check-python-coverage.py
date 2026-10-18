from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

LINE_MINIMUM = 80.0
BRANCH_MINIMUM = 70.0


def percentage(covered: int, total: int) -> float:
    return 100.0 if total == 0 else covered * 100.0 / total


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-python-coverage.py COVERAGE_JSON", file=sys.stderr)
        return 2

    payload: dict[str, Any] = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    totals = payload["totals"]
    line_coverage = percentage(int(totals["covered_lines"]), int(totals["num_statements"]))
    branch_coverage = percentage(
        int(totals["covered_branches"]), int(totals["num_branches"])
    )
    print(f"Coverage gate: lines={line_coverage:.2f}% branches={branch_coverage:.2f}%")

    failures = []
    if line_coverage < LINE_MINIMUM:
        failures.append(f"line coverage is below {LINE_MINIMUM:.0f}%")
    if branch_coverage < BRANCH_MINIMUM:
        failures.append(f"branch coverage is below {BRANCH_MINIMUM:.0f}%")
    if failures:
        print("; ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

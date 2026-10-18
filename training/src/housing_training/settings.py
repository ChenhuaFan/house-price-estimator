from __future__ import annotations

import os
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    database_url: str
    snapshot_root: Path
    run_root: Path
    artifact_root: Path
    git_commit_sha: str

    @classmethod
    def from_environment(cls) -> Settings:
        return cls(
            database_url=os.environ.get(
                "TRAINING_DATABASE_URL",
                "postgresql://training_runtime:training@localhost:5432/housing",
            ),
            snapshot_root=Path(os.environ.get("SNAPSHOT_ROOT", "training/data/snapshots")),
            run_root=Path(os.environ.get("TRAINING_RUN_ROOT", "training/runs")),
            artifact_root=Path(os.environ.get("MODEL_ARTIFACT_ROOT", "training/artifacts")),
            git_commit_sha=os.environ.get("GIT_COMMIT_SHA") or _git_commit(),
        )


def _git_commit() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL
        ).strip()
    except (FileNotFoundError, subprocess.CalledProcessError):
        raise RuntimeError("GIT_COMMIT_SHA is required outside a Git checkout") from None

from __future__ import annotations

from typing import Any
from uuid import UUID

from .registry import Registry
from .settings import Settings
from .state import now_iso, save_state, sync_state


def record_failure(
    settings: Settings, run_id: UUID, state: dict[str, Any], error: BaseException
) -> None:
    summary = f"{type(error).__name__}: {error}"[:1000]
    state["failure"] = {"summary": summary, "at": now_iso()}
    try:
        registry = Registry(settings.database_url)
        run = registry.run(run_id)
        if run["run_status"] == "RUNNING":
            state["pending_registry_operation"] = "FAIL_RUN"
            save_state(settings.run_root, run_id, state)
            revision = registry.fail_run(run_id, int(run["state_revision"]), summary)
            sync_state(
                settings.run_root,
                run_id,
                state,
                revision,
                str(run["run_stage"]),
                "FAILED",
            )
            return
    except BaseException:
        state["pending_registry_operation"] = "FAIL_RUN"
        state["run_status"] = "REGISTRY_RECONCILIATION_REQUIRED"
    save_state(settings.run_root, run_id, state)

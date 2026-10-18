from uuid import UUID

from housing_training.io_utils import (
    atomic_write_json,
    canonical_json_bytes,
    read_json,
    sha256_file,
)
from housing_training.state import create_state, load_state, sync_state

RUN_ID = UUID("11111111-1111-4111-8111-111111111111")
DATASET_ID = UUID("00000000-0000-4000-8000-000000000001")


def test_atomic_json_is_canonical_and_hashable(tmp_path: object) -> None:
    from pathlib import Path

    path = Path(str(tmp_path)) / "nested" / "value.json"
    atomic_write_json(path, {"z": 1, "a": "é"})

    assert path.read_bytes() == canonical_json_bytes({"a": "é", "z": 1})
    assert path.read_bytes() == b'{"a":"\\u00e9","z":1}\n'
    assert len(sha256_file(path)) == 64
    assert read_json(path) == {"a": "é", "z": 1}


def test_state_transitions_are_durable(tmp_path: object) -> None:
    from pathlib import Path

    root = Path(str(tmp_path))
    state = create_state(root, RUN_ID, DATASET_ID, "abc1234")
    sync_state(root, RUN_ID, state, 2, "SPLITTING")

    persisted = load_state(root, RUN_ID)
    assert persisted["state_revision"] == 2
    assert persisted["last_synced_registry_revision"] == 2
    assert persisted["run_stage"] == "SPLITTING"
    assert persisted["pending_registry_operation"] == "NONE"

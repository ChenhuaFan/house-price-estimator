from __future__ import annotations

import argparse
import difflib
import json
import os
import subprocess
import sys
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CONTRACT_ROOT = ROOT / "contracts" / "openapi"
HTTP_METHODS = {"delete", "get", "head", "options", "patch", "post", "put", "trace"}


def fetch_public_model() -> dict[str, Any]:
    origin = os.environ.get("MODEL_PUBLIC_ORIGIN", "http://127.0.0.1:8000")
    with urllib.request.urlopen(f"{origin}/openapi.json", timeout=10) as response:
        return json.load(response)


def fetch_from_container(service: str, command: list[str]) -> dict[str, Any]:
    completed = subprocess.run(
        ["docker", "compose", "exec", "-T", service, *command],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    return json.loads(completed.stdout)


def fetch_estimator() -> dict[str, Any]:
    return fetch_from_container(
        "estimator-service",
        [
            "python",
            "-c",
            "import urllib.request; print(urllib.request.urlopen("
            "'http://127.0.0.1:8000/openapi.json').read().decode())",
        ],
    )


def fetch_market() -> dict[str, Any]:
    return fetch_from_container(
        "market-service",
        ["curl", "-fsS", "http://127.0.0.1:8080/v3/api-docs"],
    )


CONTRACTS: dict[str, Callable[[], dict[str, Any]]] = {
    "model-service.openapi.json": fetch_public_model,
    "estimator-service.openapi.json": fetch_estimator,
    "market-service.openapi.json": fetch_market,
}


def canonical(specification: dict[str, Any]) -> str:
    return json.dumps(specification, indent=2, sort_keys=True, ensure_ascii=True) + "\n"


def resolve_pointer(document: dict[str, Any], pointer: str) -> Any:
    if not pointer.startswith("#/"):
        raise ValueError(f"only local OpenAPI references are allowed: {pointer}")
    current: Any = document
    for encoded in pointer[2:].split("/"):
        key = encoded.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or key not in current:
            raise ValueError(f"unresolved OpenAPI reference: {pointer}")
        current = current[key]
    return current


def validate_structure(name: str, specification: dict[str, Any]) -> None:
    version = specification.get("openapi")
    if not isinstance(version, str) or not version.startswith("3.1."):
        raise ValueError(f"{name}: expected OpenAPI 3.1, got {version!r}")
    info = specification.get("info")
    if not isinstance(info, dict) or not isinstance(info.get("title"), str):
        raise ValueError(f"{name}: info.title is required")
    paths = specification.get("paths")
    if not isinstance(paths, dict) or not paths:
        raise ValueError(f"{name}: at least one path is required")
    for path, path_item in paths.items():
        if not isinstance(path, str) or not path.startswith("/") or not isinstance(path_item, dict):
            raise ValueError(f"{name}: invalid path item {path!r}")
        for method, operation in path_item.items():
            if method not in HTTP_METHODS:
                continue
            if not isinstance(operation, dict) or not isinstance(operation.get("responses"), dict):
                raise ValueError(f"{name}: {method.upper()} {path} has no responses")

    def walk(value: Any) -> None:
        if isinstance(value, dict):
            reference = value.get("$ref")
            if isinstance(reference, str):
                resolve_pointer(specification, reference)
            for nested in value.values():
                walk(nested)
        elif isinstance(value, list):
            for nested in value:
                walk(nested)

    walk(specification)


def update() -> int:
    CONTRACT_ROOT.mkdir(parents=True, exist_ok=True)
    for name, fetch in CONTRACTS.items():
        specification = fetch()
        validate_structure(name, specification)
        (CONTRACT_ROOT / name).write_text(canonical(specification), encoding="utf-8")
        print(f"Updated {name}")
    return 0


def validate() -> int:
    failed = False
    for name, fetch in CONTRACTS.items():
        expected_path = CONTRACT_ROOT / name
        if not expected_path.is_file():
            print(f"Missing reviewed contract: {expected_path}", file=sys.stderr)
            failed = True
            continue
        expected = json.loads(expected_path.read_text(encoding="utf-8"))
        actual = fetch()
        validate_structure(name, actual)
        if expected != actual:
            failed = True
            difference = difflib.unified_diff(
                canonical(expected).splitlines(),
                canonical(actual).splitlines(),
                fromfile=f"reviewed/{name}",
                tofile=f"runtime/{name}",
                lineterm="",
            )
            print("\n".join(list(difference)[:120]), file=sys.stderr)
        else:
            print(f"Validated {name}")
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Update or validate live OpenAPI contracts.")
    parser.add_argument("mode", choices=("update", "validate"))
    arguments = parser.parse_args()
    return update() if arguments.mode == "update" else validate()


if __name__ == "__main__":
    raise SystemExit(main())

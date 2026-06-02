#!/usr/bin/env python3
"""Build a reviewed DrDuc Android Overlay Delta Manifest V2."""

from __future__ import annotations

import argparse
import hashlib
import json
from decimal import Decimal
from pathlib import Path
from typing import Any

SCHEMA_VERSION = 2
MAX_ENTRIES = 50_000
ENTRY_TYPES = {"term", "tm"}


def canonical_json(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        number = Decimal(str(value))
        if not number.is_finite():
            raise ValueError("Overlay delta numbers must be finite")
        text = format(number, "f")
        if "." in text:
            text = text.rstrip("0").rstrip(".")
        return "0" if text in {"", "-0"} else text
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, list):
        return "[" + ",".join(canonical_json(item) for item in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(
            f"{canonical_json(key)}:{canonical_json(value[key])}" for key in sorted(value)
        ) + "}"
    raise TypeError(f"Unsupported overlay delta value: {type(value).__name__}")


def delta_sha256(manifest: dict[str, Any]) -> str:
    payload = {
        "schemaVersion": manifest["schemaVersion"],
        "baseGraphVersion": manifest["baseGraphVersion"],
        "deltaVersion": manifest["deltaVersion"],
        "entries": manifest["entries"],
    }
    return hashlib.sha256(canonical_json(payload).encode("utf-8")).hexdigest()


def load_entries(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    entries = payload.get("entries") if isinstance(payload, dict) else payload
    if not isinstance(entries, list):
        raise ValueError("Input must be an entry array or an object with an entries array")
    if not 1 <= len(entries) <= MAX_ENTRIES:
        raise ValueError(f"Entries must contain 1..{MAX_ENTRIES} rows")
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise ValueError(f"Entry {index} must be an object")
        if entry.get("type") not in ENTRY_TYPES:
            raise ValueError(f"Entry {index} has unsupported type: {entry.get('type')}")
        if entry.get("reviewed") is not True:
            raise ValueError(f"Entry {index} is not reviewed")
        if not str(entry.get("source", "")).strip():
            raise ValueError(f"Entry {index} requires source")
        if not str(entry.get("target", "")).strip():
            raise ValueError(f"Entry {index} requires target")
    return entries


def build(entries: list[dict[str, Any]], base_graph_version: str, delta_version: str) -> dict[str, Any]:
    manifest = {
        "schemaVersion": SCHEMA_VERSION,
        "baseGraphVersion": base_graph_version,
        "deltaVersion": delta_version,
        "entries": entries,
    }
    manifest["sha256"] = delta_sha256(manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--entries", type=Path, required=True, help="Reviewed entry JSON array")
    parser.add_argument("--base-graph-version", required=True)
    parser.add_argument("--delta-version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest = build(
        entries=load_entries(args.entries.resolve()),
        base_graph_version=args.base_graph_version.strip(),
        delta_version=args.delta_version.strip(),
    )
    if not manifest["baseGraphVersion"]:
        raise ValueError("baseGraphVersion must not be blank")
    if not manifest["deltaVersion"]:
        raise ValueError("deltaVersion must not be blank")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()

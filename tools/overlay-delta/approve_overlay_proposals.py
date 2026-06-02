#!/usr/bin/env python3
"""Promote explicitly selected QA proposals into reviewed overlay entries."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

RUNTIME_FIELDS = {
    "type",
    "source",
    "target",
    "priority",
    "confidence",
    "universe",
    "domain",
    "scope",
    "entityType",
    "posTag",
    "posSub",
    "grammarRole",
    "contextMarkers",
    "negativeContextMarkers",
    "coOccurringEntities",
}


def read_ids(path: Path | None, values: list[str]) -> set[str]:
    approved = {value.strip() for value in values if value.strip()}
    if path:
        approved.update(line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
    if not approved:
        raise ValueError("Select at least one proposal ID through --approved-id or --approved-ids-file")
    return approved


def approve(queue: dict[str, Any], approved_ids: set[str]) -> list[dict[str, Any]]:
    rows = queue.get("entries")
    if queue.get("reviewRequired") is not True or not isinstance(rows, list):
        raise ValueError("Input is not a review-required QA proposal queue")
    available = {str(row.get("proposalId") or "") for row in rows}
    missing = sorted(approved_ids - available)
    if missing:
        raise ValueError(f"Unknown proposal IDs: {', '.join(missing)}")
    promoted = []
    for row in rows:
        if row.get("proposalId") not in approved_ids:
            continue
        draft = row.get("entry")
        if not isinstance(draft, dict) or draft.get("reviewed") is not False:
            raise ValueError(f"Proposal {row.get('proposalId')} is not an unreviewed draft")
        entry = {key: value for key, value in draft.items() if key in RUNTIME_FIELDS}
        entry["reviewed"] = True
        promoted.append(entry)
    return promoted


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proposals", type=Path, required=True)
    parser.add_argument("--approved-id", action="append", default=[])
    parser.add_argument("--approved-ids-file", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    queue = json.loads(args.proposals.read_text(encoding="utf-8"))
    promoted = approve(queue, read_ids(args.approved_ids_file, args.approved_id))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(promoted, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"reviewedEntries": len(promoted)}, ensure_ascii=True))


if __name__ == "__main__":
    main()

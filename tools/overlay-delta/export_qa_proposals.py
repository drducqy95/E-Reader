#!/usr/bin/env python3
"""Export review-only Android overlay proposals from a DrDuc QA summary."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

LOCKED_ENTITY = re.compile(r"Locked entity '(.+?)' expected '(.+?)'")


def stable_id(entry: dict[str, Any]) -> str:
    payload = "\0".join(str(entry.get(key) or "") for key in ("type", "source", "target"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()[:20]


def read_json(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"Expected an object: {path}")
    return payload


def proposal(entry: dict[str, Any], **evidence: Any) -> dict[str, Any]:
    if entry.get("reviewed") is not False:
        raise ValueError("QA proposals must remain reviewed=false")
    return {"proposalId": stable_id(entry), "entry": entry, "evidence": evidence}


def export(summary_path: Path, *, include_passed: bool = False, max_chapters: int = 100) -> dict[str, Any]:
    summary = read_json(summary_path)
    chapters = summary.get("chapters")
    if not isinstance(chapters, list):
        raise ValueError("QA summary requires a chapters array")
    chapter_proposals: list[dict[str, Any]] = []
    terminology: dict[tuple[str, str], dict[str, Any]] = defaultdict(
        lambda: {"chapterIds": set(), "messages": set(), "issueCount": 0}
    )
    selected = [row for row in chapters if include_passed or not row.get("passed")][:max_chapters]
    for chapter in selected:
        chapter_id = str(chapter.get("chapter_id") or "")
        pair = chapter.get("file_pair") if isinstance(chapter.get("file_pair"), dict) else {}
        source_path = Path(str(pair.get("source_path") or ""))
        reference_path = Path(str(chapter.get("reference_path") or pair.get("reference_path") or ""))
        if source_path.is_file() and reference_path.is_file():
            source = source_path.read_text(encoding="utf-8").strip()
            target = reference_path.read_text(encoding="utf-8").strip()
            if source and target:
                chapter_proposals.append(
                    proposal(
                        {
                            "type": "tm",
                            "reviewed": False,
                            "source": source,
                            "target": target,
                            "confidence": 1.0,
                            "scope": "project",
                        },
                        kind="chapter_tm",
                        chapterId=chapter_id,
                        sourcePath=str(source_path),
                        referencePath=str(reference_path),
                        labels=chapter.get("labels") or [],
                        wordSimilarity=chapter.get("word_similarity"),
                        charSimilarity=chapter.get("char_similarity"),
                    )
                )
        qa_path = ((chapter.get("artifact_paths") or {}).get("qa_json")) if isinstance(chapter.get("artifact_paths"), dict) else ""
        if qa_path and Path(str(qa_path)).is_file():
            for issue in read_json(Path(str(qa_path))).get("issues") or []:
                message = str(issue.get("message") or "")
                match = LOCKED_ENTITY.fullmatch(message)
                if not match:
                    continue
                source, target = match.groups()
                row = terminology[(source, target)]
                row["chapterIds"].add(chapter_id)
                row["messages"].add(message)
                row["issueCount"] += 1
    term_proposals = [
        proposal(
            {
                "type": "term",
                "reviewed": False,
                "source": source,
                "target": target,
                "priority": 200,
                "confidence": 1.0,
                "scope": "project",
                "entityType": "locked_entity",
            },
            kind="locked_entity",
            chapterIds=sorted(evidence["chapterIds"]),
            issueCount=evidence["issueCount"],
            messages=sorted(evidence["messages"]),
        )
        for (source, target), evidence in sorted(terminology.items())
    ]
    entries = term_proposals + chapter_proposals
    return {
        "schemaVersion": 1,
        "proposalType": "qa-overlay-review-queue",
        "sourceAudit": str(summary_path),
        "sourceRunId": summary.get("run_id"),
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "reviewRequired": True,
        "entries": entries,
        "stats": {
            "proposals": len(entries),
            "lockedEntityTerms": len(term_proposals),
            "chapterTm": len(chapter_proposals),
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include-passed", action="store_true")
    parser.add_argument("--max-chapters", type=int, default=100)
    args = parser.parse_args()
    queue = export(args.summary.resolve(), include_passed=args.include_passed, max_chapters=max(0, args.max_chapters))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(queue, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(queue["stats"], ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()

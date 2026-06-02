#!/usr/bin/env python3
"""Build the read-only Android runtime subset of a DrDuc graph database."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sqlite3
from pathlib import Path

RUNTIME_TABLES = (
    "graph_nodes",
    "graph_node_labels",
    "graph_term_index",
    "graph_context_index",
    "graph_node_ids",
    "graph_edges_compact",
    "graph_manifest",
    "graph_versions",
)

OPTIONAL_RUNTIME_TABLES = (
    "graph_pos_index",
    "graph_grammar_rules",
    "graph_cooccurrence",
    "graph_cooccurrence_index",
)
MAX_BUNDLED_GRAPH_BYTES = 250 * 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def scalar(db: sqlite3.Connection, query: str) -> int:
    return int(db.execute(query).fetchone()[0])


def profile(db: sqlite3.Connection) -> dict[str, object]:
    stats = {
        "nodes": scalar(db, "SELECT COUNT(*) FROM graph_nodes"),
        "terms": scalar(db, "SELECT COUNT(*) FROM graph_term_index"),
        "contextRows": scalar(db, "SELECT COUNT(*) FROM graph_context_index"),
        "grammarNodes": scalar(
            db,
            "SELECT COUNT(*) FROM graph_nodes WHERE primary_label = 'GrammarRule' OR labels_json LIKE '%GrammarRule%'",
        ),
        "universeTerms": scalar(db, "SELECT COUNT(*) FROM graph_term_index WHERE universe <> ''"),
        "cooccurrenceRows": scalar(db, "SELECT COUNT(*) FROM graph_cooccurrence") if table_exists(db, "graph_cooccurrence") else 0,
    }
    capabilities = {
        "grammar": stats["grammarNodes"] > 0,
        "contextUniverse": stats["contextRows"] > 0 and stats["universeTerms"] > 0,
        "cooccurrence": stats["cooccurrenceRows"] > 0,
    }
    warnings = []
    if not capabilities["grammar"]:
        warnings.append("grammar rules are unavailable")
    if not capabilities["contextUniverse"]:
        warnings.append("context-universe data is unavailable: graph_context_index and universe terms must both be populated")
    if not capabilities["cooccurrence"]:
        warnings.append("entity co-occurrence data is unavailable")
    return {"stats": stats, "capabilities": capabilities, "warnings": warnings}


def table_exists(db: sqlite3.Connection, table: str) -> bool:
    return db.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table,),
    ).fetchone() is not None


def build(source: Path, target: Path, *, require_context_universe: bool = False) -> dict[str, object]:
    if not source.is_file():
        raise ValueError(f"Source graph does not exist: {source}")
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        target.unlink()
    source_db = sqlite3.connect(f"file:{source}?mode=ro", uri=True)
    output = sqlite3.connect(target)
    try:
        source_tables = {row[0] for row in source_db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        missing = sorted(set(RUNTIME_TABLES) - source_tables)
        if missing:
            raise ValueError(f"Source graph is missing runtime tables: {', '.join(missing)}")
        if require_context_universe:
            source_context_rows = scalar(source_db, "SELECT COUNT(*) FROM graph_context_index")
            source_universe_terms = scalar(source_db, "SELECT COUNT(*) FROM graph_term_index WHERE universe <> ''")
            if source_context_rows == 0 or source_universe_terms == 0:
                raise ValueError(
                    "Context-universe release gate failed: populate graph_context_index and universe terms before packaging"
                )
        output.execute("PRAGMA journal_mode=OFF")
        output.execute("PRAGMA synchronous=OFF")
        source_db.backup(output)
        tables = {row[0] for row in output.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        retained_tables = set(RUNTIME_TABLES) | (tables & set(OPTIONAL_RUNTIME_TABLES))
        for table in sorted(tables - retained_tables):
            if table.startswith("sqlite_"):
                continue
            output.execute(f'DROP TABLE IF EXISTS "{table}"')
        output.executescript(
            """
            DROP VIEW IF EXISTS graph_edges;
            DROP TRIGGER IF EXISTS graph_edges_insert;
            DROP TRIGGER IF EXISTS graph_edges_update;
            DROP TRIGGER IF EXISTS graph_edges_delete;
            CREATE INDEX IF NOT EXISTS idx_mobile_term_lookup
              ON graph_term_index(normalized_source, lang, max_len DESC, priority DESC, confidence DESC);
            CREATE INDEX IF NOT EXISTS idx_mobile_context_node
              ON graph_context_index(node_id, marker_type);
            """
        )
        output.commit()
        output.execute("VACUUM")
        output.execute("PRAGMA journal_mode=DELETE")
    except Exception:
        output.close()
        source_db.close()
        target.unlink(missing_ok=True)
        raise
    finally:
        output.close()
        source_db.close()
    runtime_db = sqlite3.connect(f"file:{target}?mode=ro", uri=True)
    try:
        runtime_profile = profile(runtime_db)
    finally:
        runtime_db.close()
    if require_context_universe and not runtime_profile["capabilities"]["contextUniverse"]:
        target.unlink(missing_ok=True)
        raise ValueError("Context-universe release gate failed: populate graph_context_index and universe terms before packaging")
    bytes_count = target.stat().st_size
    return {
        "schemaVersion": 1,
        "source": str(source),
        "target": str(target),
        "bytes": bytes_count,
        "sha256": sha256(target),
        "packageMode": "externalExpansion" if bytes_count > MAX_BUNDLED_GRAPH_BYTES else "bundledAsset",
        "runtimeTables": sorted(retained_tables),
        **runtime_profile,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--require-context-universe", action="store_true")
    args = parser.parse_args()
    manifest = build(
        args.source.resolve(),
        args.target.resolve(),
        require_context_universe=args.require_context_universe,
    )
    text = json.dumps(manifest, ensure_ascii=False, indent=2)
    if args.manifest:
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(text + "\n", encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()

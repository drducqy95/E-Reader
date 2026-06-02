#!/usr/bin/env python3
"""Create a tiny DrDuc graph with grammar, context-universe and co-occurrence rows."""

from __future__ import annotations

import argparse
import json
import sqlite3
from contextlib import closing
from pathlib import Path


def create(target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.unlink(missing_ok=True)
    with closing(sqlite3.connect(target)) as db:
        db.executescript(
            """
            CREATE TABLE graph_nodes (
              id TEXT PRIMARY KEY, labels_json TEXT NOT NULL, primary_label TEXT NOT NULL, key TEXT NOT NULL,
              properties_json TEXT NOT NULL, confidence REAL NOT NULL, status TEXT NOT NULL, scope TEXT NOT NULL
            );
            CREATE TABLE graph_node_labels (label TEXT NOT NULL, node_id TEXT NOT NULL, is_primary INTEGER NOT NULL);
            CREATE TABLE graph_term_index (
              source TEXT NOT NULL, normalized_source TEXT NOT NULL, lang TEXT NOT NULL, target_lang TEXT NOT NULL,
              target TEXT NOT NULL, surface TEXT NOT NULL, node_id TEXT NOT NULL, priority INTEGER NOT NULL,
              max_len INTEGER NOT NULL, kind TEXT NOT NULL, scope TEXT NOT NULL, status TEXT NOT NULL,
              graph_layer TEXT NOT NULL, universe TEXT NOT NULL, work TEXT NOT NULL, domain TEXT NOT NULL,
              confidence REAL NOT NULL
            );
            CREATE TABLE graph_context_index (
              node_id TEXT NOT NULL, marker_type TEXT NOT NULL, marker TEXT NOT NULL, universe TEXT NOT NULL,
              work TEXT NOT NULL, domain TEXT NOT NULL, confidence REAL NOT NULL, status TEXT NOT NULL, scope TEXT NOT NULL
            );
            CREATE TABLE graph_node_ids (sid INTEGER PRIMARY KEY, node_id TEXT NOT NULL);
            CREATE TABLE graph_edges_compact (
              id TEXT PRIMARY KEY, type TEXT NOT NULL, from_sid INTEGER, to_sid INTEGER, weight REAL NOT NULL,
              properties_json TEXT NOT NULL, evidence_json TEXT NOT NULL, status TEXT NOT NULL,
              provenance_json TEXT NOT NULL, created_at TEXT, updated_at TEXT
            );
            CREATE TABLE graph_manifest (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE graph_versions (id INTEGER PRIMARY KEY, version INTEGER NOT NULL, updated_at TEXT);
            CREATE TABLE graph_cooccurrence (
              left_node_id TEXT NOT NULL, right_node_id TEXT NOT NULL, project_id TEXT NOT NULL,
              chapter_id TEXT NOT NULL, weight REAL NOT NULL, evidence_json TEXT NOT NULL
            );
            CREATE TABLE desktop_only_evidence (id INTEGER PRIMARY KEY);
            """
        )
        term_properties = json.dumps({"target": "giap", "universe": "fixture"}, ensure_ascii=False)
        grammar_properties = json.dumps({"pattern": "fixture", "context_markers": ["盟友"]}, ensure_ascii=False)
        db.execute("INSERT INTO graph_nodes VALUES (?, ?, ?, ?, ?, ?, ?, ?)", ("term-alpha", '["Lexeme"]', "Lexeme", "甲", term_properties, 1.0, "approved", "universe"))
        db.execute("INSERT INTO graph_nodes VALUES (?, ?, ?, ?, ?, ?, ?, ?)", ("grammar-fixture", '["GrammarRule"]', "GrammarRule", "fixture", grammar_properties, 1.0, "approved", "global"))
        db.execute("INSERT INTO graph_node_labels VALUES ('Lexeme', 'term-alpha', 1)")
        db.execute("INSERT INTO graph_node_labels VALUES ('GrammarRule', 'grammar-fixture', 1)")
        db.execute("INSERT INTO graph_node_ids VALUES (1, 'term-alpha')")
        db.execute("INSERT INTO graph_node_ids VALUES (2, 'grammar-fixture')")
        db.execute(
            "INSERT INTO graph_term_index VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ("甲", "甲", "zh", "vi", "giap", "giap", "term-alpha", 100, 1, "lexeme", "universe", "approved", "fixture", "fixture", "", "", 1.0),
        )
        db.execute("INSERT INTO graph_context_index VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", ("term-alpha", "context", "盟友", "fixture", "", "", 1.0, "approved", "universe"))
        db.execute("INSERT INTO graph_versions VALUES (1, 77, CURRENT_TIMESTAMP)")
        db.execute("INSERT INTO graph_manifest VALUES ('fixture', 'context-universe')")
        db.execute("INSERT INTO graph_cooccurrence VALUES ('term-alpha', 'grammar-fixture', 'fixture', 'chapter-001', 1.0, '{}')")
        db.commit()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", type=Path, required=True)
    args = parser.parse_args()
    create(args.target.resolve())
    print(args.target.resolve())


if __name__ == "__main__":
    main()

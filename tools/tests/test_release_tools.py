from __future__ import annotations

import importlib.util
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load_module(name: str, relative_path: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


mobile_graph = load_module("mobile_graph", "tools/mobile-graph/build_mobile_graph.py")
proposal_export = load_module("proposal_export", "tools/overlay-delta/export_qa_proposals.py")
proposal_approve = load_module("proposal_approve", "tools/overlay-delta/approve_overlay_proposals.py")
overlay_delta = load_module("overlay_delta", "tools/overlay-delta/build_overlay_delta.py")


class MobileGraphBuilderTest(unittest.TestCase):
    def test_profiles_capabilities_and_removes_desktop_tables(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root / "source.sqlite"
            target = root / "mobile.sqlite"
            create_graph(source, with_context=True)

            manifest = mobile_graph.build(source, target, require_context_universe=True)

            self.assertTrue(manifest["capabilities"]["grammar"])
            self.assertTrue(manifest["capabilities"]["contextUniverse"])
            self.assertTrue(manifest["capabilities"]["cooccurrence"])
            self.assertEqual(1, manifest["stats"]["contextRows"])
            db = sqlite3.connect(target)
            try:
                self.assertIsNone(db.execute("SELECT 1 FROM sqlite_master WHERE name='desktop_only'").fetchone())
            finally:
                db.close()

    def test_release_gate_rejects_empty_context_universe(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root / "source.sqlite"
            target = root / "mobile.sqlite"
            create_graph(source, with_context=False)

            manifest = mobile_graph.build(source, target)
            self.assertFalse(manifest["capabilities"]["contextUniverse"])
            self.assertTrue(any("context-universe" in row for row in manifest["warnings"]))

            with self.assertRaisesRegex(ValueError, "Context-universe release gate failed"):
                mobile_graph.build(source, target, require_context_universe=True)
            self.assertFalse(target.exists())


class OverlayProposalWorkflowTest(unittest.TestCase):
    def test_exports_unreviewed_queue_and_promotes_only_selected_ids(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            source = root / "chapter-011.txt"
            reference = root / "chapter_0011.md"
            qa = root / "qa.json"
            summary = root / "summary.json"
            source.write_text("迪奈尔来到神殿。", encoding="utf-8")
            reference.write_text("Deneir đi tới thần điện.", encoding="utf-8")
            qa.write_text(
                json.dumps(
                    {
                        "issues": [
                            {
                                "severity": "high",
                                "checker": "terminology",
                                "message": "Locked entity '迪奈尔' expected 'Deneir'",
                            }
                        ]
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            summary.write_text(
                json.dumps(
                    {
                        "run_id": "fixture-run",
                        "chapters": [
                            {
                                "chapter_id": "chapter-011",
                                "passed": False,
                                "labels": ["term_bank"],
                                "word_similarity": 0.3,
                                "char_similarity": 0.2,
                                "file_pair": {"source_path": str(source), "reference_path": str(reference)},
                                "reference_path": str(reference),
                                "artifact_paths": {"qa_json": str(qa)},
                            }
                        ],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )

            queue = proposal_export.export(summary)

            self.assertEqual({"proposals": 2, "lockedEntityTerms": 1, "chapterTm": 1}, queue["stats"])
            self.assertTrue(all(row["entry"]["reviewed"] is False for row in queue["entries"]))
            term = next(row for row in queue["entries"] if row["entry"]["type"] == "term")
            promoted = proposal_approve.approve(queue, {term["proposalId"]})
            self.assertEqual(
                [{"type": "term", "source": "迪奈尔", "target": "Deneir", "priority": 200, "confidence": 1.0, "scope": "project", "entityType": "locked_entity", "reviewed": True}],
                promoted,
            )
            self.assertEqual(promoted, overlay_delta.load_entries(write_json(root / "approved.json", promoted)))

    def test_requires_explicit_review_selection(self) -> None:
        with self.assertRaisesRegex(ValueError, "Select at least one proposal"):
            proposal_approve.read_ids(None, [])


def create_graph(path: Path, *, with_context: bool) -> None:
    db = sqlite3.connect(path)
    try:
        db.execute("CREATE TABLE graph_nodes (id INTEGER PRIMARY KEY, primary_label TEXT NOT NULL, labels_json TEXT NOT NULL)")
        db.execute("CREATE TABLE graph_node_labels (id INTEGER PRIMARY KEY)")
        db.execute(
            "CREATE TABLE graph_term_index "
            "(id INTEGER PRIMARY KEY, normalized_source TEXT NOT NULL, lang TEXT NOT NULL, "
            "max_len INTEGER NOT NULL, priority INTEGER NOT NULL, confidence REAL NOT NULL, universe TEXT NOT NULL)"
        )
        db.execute("CREATE TABLE graph_context_index (id INTEGER PRIMARY KEY, node_id TEXT NOT NULL, marker_type TEXT NOT NULL)")
        db.execute("CREATE TABLE graph_node_ids (id INTEGER PRIMARY KEY)")
        db.execute("CREATE TABLE graph_edges_compact (id INTEGER PRIMARY KEY)")
        db.execute("CREATE TABLE graph_manifest (id INTEGER PRIMARY KEY)")
        db.execute("CREATE TABLE graph_versions (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)")
        db.execute("INSERT INTO graph_versions VALUES (1, 1)")
        db.execute("CREATE TABLE graph_cooccurrence (id INTEGER PRIMARY KEY)")
        db.execute("CREATE TABLE desktop_only (id INTEGER PRIMARY KEY)")
        if with_context:
            db.execute("INSERT INTO graph_nodes VALUES (1, 'GrammarRule', '[\"GrammarRule\"]')")
            db.execute("INSERT INTO graph_term_index VALUES (1, 'fixture', 'zh', 7, 1, 1.0, 'fixture')")
            db.execute("INSERT INTO graph_context_index VALUES (1, 'node-1', 'universe')")
            db.execute("INSERT INTO graph_cooccurrence VALUES (1)")
        db.commit()
    finally:
        db.close()


def write_json(path: Path, payload):
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path


if __name__ == "__main__":
    unittest.main()

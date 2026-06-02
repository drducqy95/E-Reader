# DrDuc and Legado Integration

## Runtime packages

- Import `translation_graph.mobile.sqlite` from Android Settings through the
  Storage Access Framework, or enqueue a resumable HTTPS download through
  `POST /api/v1/graph/download`.
- `GraphPackageManager.importGraph()` writes a temporary file, verifies
  SHA-256 when provided, validates the mobile SQLite schema and graph version,
  then atomically replaces the active graph package.
- Import optional fallback dictionaries through
  `GraphPackageManager.importDictionary()`.
- The APK does not execute Python. Python is used only to build mobile graph
  packages and to run QA parity checks.

The generated DrDuc graph currently exceeds the `250 MiB` bundle threshold.
Ship it as an expansion download, not as an APK asset.

## Local API

The web service binds to the first available loopback port in `1122..1132`.
Call `/bootstrap` first to establish the HttpOnly session cookie.

| Route | Purpose |
|---|---|
| `POST /api/v1/translate` | Translate text through the Kotlin orchestrator. |
| `POST /api/v1/translation/trace` | Translate and return selected candidates, notes and alternatives. |
| `GET /api/v1/graph/status` | Report package SHA-256, graph version and overlay version. |
| `POST /api/v1/graph/download` | Enqueue a resumable WorkManager expansion download with `url` and `sha256`. |
| `GET /api/v1/overlay/status` | Report overlay version, reviewed term/TM counts and applied deltas. |
| `POST /api/v1/overlay/import` | Apply a reviewed Contract V2 overlay delta transactionally. |
| `GET /api/v1/downloads` | Report WorkManager state and byte progress. |
| `/admin/`, `/reader/` | Serve the local web clients. |

Production graph URLs must use HTTPS. Loopback HTTP is accepted only for local
development and emulator smoke tests.

## Reviewed overlay delta

Export a review queue from a strict QA audit. The queue contains only
`reviewed=false` proposals and cannot be imported by Android:

```powershell
python tools\overlay-delta\export_qa_proposals.py `
  --summary D:\Converter by DrDuc\artifacts\translation_quality_audits\<run>\summary.json `
  --output artifacts\overlay\project-001.proposals.json
```

After review, promote only explicitly selected proposal IDs and build the
signed Contract V2 manifest:

```powershell
python tools\overlay-delta\approve_overlay_proposals.py `
  --proposals artifacts\overlay\project-001.proposals.json `
  --approved-ids-file artifacts\overlay\project-001.approved-ids.txt `
  --output artifacts\overlay\project-001.reviewed.json

python tools\overlay-delta\build_overlay_delta.py `
  --entries artifacts\overlay\project-001.reviewed.json `
  --base-graph-version <installed graph version> `
  --delta-version project-001-20260601-01 `
  --output artifacts\overlay\project-001.delta.json
```

The schema is `tools/overlay-delta/overlay_delta.schema.json`. The checksum is
SHA-256 over canonical JSON containing `schemaVersion`, `baseGraphVersion`,
`deltaVersion` and `entries`; it intentionally excludes the `sha256` field.
Canonical JSON sorts object keys, keeps Unicode as UTF-8, escapes required JSON
characters and writes numbers in plain decimal form. Android rejects unreviewed
rows, checksum mismatches and incompatible base graphs. Applying the same delta
version and checksum again is idempotent.

## Translation cache

Reader translations are persisted in Room per chapter and mode. Cache identity
includes the source text, graph version, overlay version, translation config,
hook version and provider mode. Replacing a graph or applying an overlay
therefore invalidates affected entries without deleting raw chapter content.

`HYBRID_PRODUCTION` ports DrDuc surface-note stripping and prefers graph
function-word rows over dictionary gloss fallbacks. `DRDUC_PARITY` retains the
raw candidate surface for fixture comparison.

## Web clients

The GPL-compatible Legado Vue and React clients are vendored in
`web/legado-admin` and `web/reader-react`. Build each client independently,
then copy the resulting static assets into the web-service module release
resources. The server binds to `127.0.0.1` unless LAN mode is explicitly
enabled.

## Build mobile graph

```powershell
python tools\mobile-graph\build_mobile_graph.py `
  --source D:\TranslatorDrDuc\ConverterGraphDrDuc\data\graph\_compiled\translation_graph.sqlite `
  --target artifacts\graph\translation_graph.mobile.sqlite `
  --manifest artifacts\graph\translation_graph.mobile.json
```

The manifest reports `capabilities` and `warnings`. Use
`--require-context-universe` for production release gating after the desktop
graph has populated both `graph_context_index` and universe terms. Android can
still install a graph without those rows for baseline offline translation, but
Settings and `/api/v1/graph/status` report that context-universe scoring is
unavailable.

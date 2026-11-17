# Page Manager

DSL-driven page management for the ab_page_schema system table.

A platform-owned core plugin (declarative JSON only — see `plugin.json` and
`config/`). Counts as of 2026-11-14:

| Contribution | Count |
|---|---|
| models | 1 |
| commands | 6 |
| pages | 2 |
| bindings | 13 |

## Directory map

- `plugin.json` — manifest (namespace, dependencies, plugin type)
- `config/*.json` — declarative contributions (models, fields, commands,
  pages, menus, permissions, dicts, i18n, binding rules as present)
- `tests/` — manifest/config validation tests (where present)

Change semantics by editing the JSON and re-importing the plugin; no TSX
lives in core plugins by design (see the ESLint boundary rules).

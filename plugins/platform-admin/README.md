# Platform Admin

DSL-driven administration pages for AuraBoot platform capabilities.

A platform-owned core plugin (declarative JSON only — see `plugin.json` and
`config/`). Counts as of 2026-11-14:

| Contribution | Count |
|---|---|
| models | 7 |
| commands | 21 |
| pages | 21 |
| menus | 28 |
| permissions | 21 |
| capabilities | 103 |
| i18n | 1123 |

## Directory map

- `plugin.json` — manifest (namespace, dependencies, plugin type)
- `config/*.json` — declarative contributions (models, fields, commands,
  pages, menus, permissions, dicts, i18n, binding rules as present)
- `tests/` — manifest/config validation tests (where present)

Change semantics by editing the JSON and re-importing the plugin; no TSX
lives in core plugins by design (see the ESLint boundary rules).

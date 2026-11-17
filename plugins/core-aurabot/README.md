# AI Center Navigation

Navigation menus and permissions for the AI Center (dashboard, colleagues, knowledge, runs, providers).

A platform-owned core plugin (declarative JSON only — see `plugin.json` and
`config/`). Counts as of 2026-11-14:

| Contribution | Count |
|---|---|
| menus | 8 |
| i18n | 134 |
| permissions | 1 |

## Directory map

- `plugin.json` — manifest (namespace, dependencies, plugin type)
- `config/*.json` — declarative contributions (models, fields, commands,
  pages, menus, permissions, dicts, i18n, binding rules as present)
- `tests/` — manifest/config validation tests (where present)

Change semantics by editing the JSON and re-importing the plugin; no TSX
lives in core plugins by design (see the ESLint boundary rules).

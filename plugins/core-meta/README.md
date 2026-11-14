# Meta Management Navigation

Navigation menus and permissions for platform meta management (models, fields, dicts, named queries).

A platform-owned core plugin (declarative JSON only — see `plugin.json` and
`config/`). Counts as of 2026-11-14:

| Contribution | Count |
|---|---|
| menus | 10 |
| pages | 3 |
| permissions | 13 |

## Directory map

- `plugin.json` — manifest (namespace, dependencies, plugin type)
- `config/*.json` — declarative contributions (models, fields, commands,
  pages, menus, permissions, dicts, i18n, binding rules as present)
- `tests/` — manifest/config validation tests (where present)

Change semantics by editing the JSON and re-importing the plugin; no TSX
lives in core plugins by design (see the ESLint boundary rules).

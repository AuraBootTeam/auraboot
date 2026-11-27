# Agent Control Plane

AI agent orchestration: task management, execution tracking, governance, and the agent runtime surface.

A platform-owned core plugin (declarative JSON only — see `plugin.json` and
`config/`). Counts as of 2026-11-14:

| Contribution | Count |
|---|---|
| models | 16 |
| commands | 53 |
| pages | 39 |
| menus | 22 |
| permissions | 14 |
| named-queries | 12 |
| bindings | 201 |
| i18n | 369 |

## Directory map

- `plugin.json` — manifest (namespace, dependencies, plugin type)
- `config/*.json` — declarative contributions (models, fields, commands,
  pages, menus, permissions, dicts, i18n, binding rules as present)
- `tests/` — manifest/config validation tests (where present)

Change semantics by editing the JSON and re-importing the plugin; no TSX
lives in core plugins by design (see the ESLint boundary rules).

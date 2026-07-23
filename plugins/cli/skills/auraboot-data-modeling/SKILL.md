---
name: auraboot-data-modeling
description: Use when creating or changing AuraBoot data models — models, fields, bindings, dictionaries. Guides you through the aura CLI + MCP tools to design a model, avoid code/name collisions, and validate before importing.
---

# AuraBoot Data Modeling

Design and evolve dynamic models on an AuraBoot instance. **You navigate and author config; the `aura` CLI and MCP tools do the actual writes** — never hand-edit the database or hand-roll HTTP calls.

## Before you start

1. Confirm the instance is reachable and you are authenticated to the right tenant:
   ```bash
   aura status        # health + connection
   aura doctor        # skills + MCP + tenant pinned + command service reachable
   ```
2. First time using any command, read its help: `aura dsl --help`, `aura plugin --help`.

## Discover before you create (avoid collisions)

- `query_dsl_capabilities` (MCP) — the canonical map of supported kinds / block types / data types. **Call this before generating any schema** so you only use real capabilities.
- `query_existing_models` (MCP) — list models already in the tenant. **Call before `create_model`** so you don't collide with an existing model code.

## Author

- Scaffold a model + fields locally:
  ```bash
  aura dsl scaffold model <model_code> --fields "name:TEXT,status:SELECT,owner:REFERENCE:sys_user"
  aura dsl inspect model <model_code>     # cross-references
  aura dsl deps <model_code>              # dependency graph
  ```
- Or create directly on the instance with the `create_model` MCP tool (defaults to `dryRun:true` — inspect the preview, then set `dryRun:false`).
- Fields need a physical binding to become real columns. Keep `fields.json` and `bindings.json` in sync (a field with no binding has metadata but no column).

## Validate before import (fix a whole batch at once)

```bash
aura plugin validate . --agent-mode
```

Returns aggregated JSON `{ ok, errorCount, errors[] }`; each error carries `code`, `message`, and where available `path` / `expected` / `agentInstruction`. Fix every error in one pass, then re-run. Do not import while `ok:false`.

## Then

Hand off to `auraboot-ui-builder` to build pages over the model, or `auraboot-workflow` to add commands.

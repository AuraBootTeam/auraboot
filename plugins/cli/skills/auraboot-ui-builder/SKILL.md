---
name: auraboot-ui-builder
description: Use when building or editing AuraBoot low-code pages — list, form, detail, workbench, dashboard — via DSL page schemas. Guides you through the aura CLI + MCP tools to author pages that only use real block/data types and pass validation.
---

# AuraBoot UI Builder

Build DSL pages on an AuraBoot instance. AuraBoot is **DSL-first**: prefer a DSL page over any hand-written React. You author page schema config; the CLI / MCP tools write it.

## Before you start

```bash
aura status && aura doctor      # reachable + authenticated + right tenant
aura dsl --help
```

## Know the capability surface (do not invent block/data types)

- `query_dsl_capabilities` (MCP) — the **only** source of truth for supported page `kind`s, block types, and data types. Call it first; using a block/data type that isn't listed will fail validation.
- `query_page_schemas` (MCP) — list existing V2 page schemas; call before `create_page_schema` to avoid collisions.
- Each model gets three page kinds: `list` / `form` / `detail`. Workbench and dashboard are separate page kinds for metric-strip + table + drill-down layouts.

## Author

- Inspect / scaffold locally: `aura dsl list pages`, `aura dsl inspect page <key>`, `aura dsl scaffold page <key>`.
- Or create on the instance with the `create_page_schema` MCP tool (`dryRun:true` first, review, then `dryRun:false`).
- For cross-model or external data-source tables, use a `detail`/`workbench` page (a `list` page captures its own model and ignores a block's `dataSource`).

## Validate

```bash
aura plugin validate . --agent-mode
```

Fix every reported error (`code` / `path` / `expected` / `agentInstruction`) before import.

## Verify it renders

A green validator proves the DSL is legal, **not** that the page works. After import, open the page and confirm data renders and each action button does something. If a generic renderer silently ignores your config, that is a bug — surface it, don't paper over it.

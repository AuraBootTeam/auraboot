# @auraboot/dsl-types

> Strong types for AuraBoot DSL — page schemas, blocks, fields, columns, actions, data sources, expressions.

**Status**: 0.x — unstable.

## Why this package exists

AuraBoot's page DSL (stored in `ab_page_schema.blocks` JSONB) was historically untyped — `block.config` was a free-form `Record<string, any>`. This made it impossible for:

- The Designer to know what properties each block accepts
- Plugins to contribute new widgets/columns with declared prop schemas
- Backends to validate DSL on import
- TypeScript to provide IDE completion or compile-time checks

This package introduces **strong type contracts for the DSL without changing the JSON shape on disk** — zero data migration, full type safety.

## Core concepts

| Type | Purpose |
|------|---------|
| `PageSchema` | `{ kind, blocks, layout, profile }` — the top-level page document |
| `BlockSchema` | `{ blockType, config }` — a unit of content within a page |
| `FieldSchema` | A single editable field (used in `form-section`, `filters`, `sub-table`) |
| `ColumnSchema` | A single table column (used in `table`, `sub-table`) |
| `ActionSchema` | A button/menu action (toolbar, row action, form action) |
| `DataSourceRef` | Reference to a query/api/dictionary/relation |
| `Expression` | Declarative or evaluated expression for `visibleWhen`/`disabledWhen` |
| `LocalizedText` | i18n text — string or `{ en, zh, ... }` |

## Field/Column reuse across blocks

`FieldSchema` is intentionally shared between `form-section.fields`, `filters.fields`, and `sub-table.editableColumns`. Same for `ColumnSchema` between `table.columns` and `sub-table.columns`. This avoids parallel protocols.

## DetailSchema?

There is no separate `DetailSchema`. Detail pages use `kind: 'detail'` with the same blocks (`stat-card`, `form-section` in readonly mode, `tabs`, `sub-table`, etc).

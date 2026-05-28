# BlockRenderer Runtime Registry — Design

**Status:** ✅ implemented (2026-04-26 — Phase 1 + Phase 2)
**Owner:** rendering team
**Closes:** BACKLOG-DESIGNER-001 (P2)
**Date:** 2026-04-25 (design) / 2026-04-26 (implementation)
**Implementation PRs:** OSS PR #38 (commits `5b277dbd` Phase 1, `0219c0c9` Phase 2) — merged into `main` at `37e3e959`

## ⚠️ Implementation correction (2026-04-26)

The original design listed three "runtime switch sites" intended for unification. During Phase 1 implementation we discovered all three dispatch on **different keys**, not `blockType`:

| File | Switch key (actual) | Status |
|------|---------------------|--------|
| `web-admin/app/ui/schema-renderer/SchemaRenderer.tsx:117-141` | `region.type` (3 values: filters/action/table) | NOT a `blockType` switch — left untouched, file later deleted in PR #39 (dead code) |
| `web-admin/app/framework/meta/runtime/data-pipeline/DataSourceManager.ts:390+` | `config.adaptor` (optionList/dictData/table) | NOT a `blockType` switch — left untouched |
| `web-admin/app/framework/smart/components/view/SmartViewRenderer.tsx:127+` | `viewType` (kanban/calendar/gallery/gantt/...) | NOT `blockType`; heterogeneous view-callback signature; out-of-scope for Phase 2 |

The **real** `blockType` runtime dispatch lived in a fourth file the original design didn't list:
- `web-admin/app/framework/meta/rendering/BlockRenderer.tsx` — held a `_fallbackRenderers` Map (13 entries)

Phase 2 swapped that Map's lookup for `BlockRegistry.get(blockType)?.component` (`framework/meta/rendering/BlockRenderer.tsx` 202 → 125 LOC). 14 block types now register through `initBlockRegistry()`: 13 originals + `sub-table` (the 13 + sub-table covers all production paths; `monthly-grid` is intentionally not registered — it's a structural marker handled by the enclosing detail page renderer's `directMonthlyGridBlocks` branch, not a generic block).

## Context (original design)

The runtime path that decides "which React component / data loader to use for a given `blockType`" is currently a hand-maintained `switch (blockType)` chain in three independent files:

- `web-admin/app/ui/schema-renderer/SchemaRenderer.tsx:117,141`
- `web-admin/app/framework/smart/components/view/SmartViewRenderer.tsx:127`
- `web-admin/app/framework/meta/runtime/data-pipeline/DataSourceManager.ts:390`

Adding a new `blockType` requires editing every site, and missing one results in a silent fallthrough that renders `null` (see also: G7 fallback dispatch added in 2026-04 to mitigate this).

> Note (2026-04-26): see "Implementation correction" above. The premise that all three sites switched on `blockType` turned out to be incorrect — only `framework/meta/rendering/BlockRenderer.tsx` did.

This document describes a single registry that the three runtime sites consult, scoped narrowly to the runtime layer to avoid premature unification with the seven designer-internal switches (palette, drop-zone, settings panel, preview, exporter — each with distinct semantics).

## Out of scope

The following sites also `switch (blockType)` but are intentionally **not** unified by this design:

- `core-designer/components/report-designer/components/BlockPalette.tsx` (palette icons / labels)
- `core-designer/components/studio/workbench/designers/areas/previews/BlockPreview.tsx` (designer canvas thumbnail)
- `core-designer/components/studio/workbench/panels/preview/PreviewModal.tsx` (preview-modal dispatch)
- `core-designer/components/studio/workbench/designers/areas/hooks/useBlockDropZone.ts` (drag-drop drop-rules)
- `core-designer/components/studio/workbench/designers/BlocksDesigner.tsx` (designer-tree dispatch)
- `core-designer/components/studio/workbench/designers/areas/editors/BlockSettingsEditor.tsx` (settings panel)
- `core-designer/components/report-designer/services/reportToHtml.ts` (HTML export)

These have heterogeneous responsibilities. Forcing them into one `BlockRegistry.register(type, spec)` would inflate `spec` to seven optional fields and recreate the today’s coupling under a new name. Track separately if the value/cost ratio later changes.

## Target API

```ts
// web-admin/app/ui/schema-renderer/BlockRegistry.ts

export interface BlockRendererSpec {
  /** React component used for end-user rendering of the block */
  component: React.ComponentType<BlockRenderProps>;
  /** Optional data loader if the block needs custom data shaping (table, monthly-grid, ...) */
  dataLoader?: (block: Block, context: BlockContext) => Promise<unknown>;
  /** Set when the block is also a Smart-view variant (list/kanban/calendar/...) */
  smartViewVariant?: SmartViewKind;
}

export const BlockRegistry = {
  register(type: string, spec: BlockRendererSpec): void { ... },
  get(type: string): BlockRendererSpec | undefined { ... },
  has(type: string): boolean { ... },
  size(): number { ... },
};
```

Consumers receive a single dispatching component:

```tsx
<BlockRenderer block={block} context={context} />
```

If `BlockRegistry.get(block.blockType)` is undefined, `BlockRenderer` logs `console.warn('[BlockRenderer] unknown blockType:', block.blockType)` and renders a small placeholder so the failure is visible in dev (today the `switch` falls through to `null`).

## Migration steps

1. **Land registry skeleton** — `BlockRegistry.ts` + `BlockRenderer.tsx`, no consumers yet. Vitest suite covers register/get/has/size + fallback warning.
2. **Bootstrap registration** — call `initBlockRegistry()` from the existing `widget` bootstrap path (`app/main.tsx` or equivalent) so registration is eager. This mirrors the `WidgetRegistry.initRegistry()` pattern (memory: `feedback_g1_init_registry_bootstrap`); lazy registration silently breaks schema-driven panels.
3. **Replace switch in `SchemaRenderer.tsx`** — keep the file’s top-level structure, swap the inner `switch` for `BlockRenderer.get(...).component` lookup.
4. **Replace switch in `SmartViewRenderer.tsx`** — same shape. The `smartViewVariant` field on the spec drives Smart-view dispatch.
5. **Replace switch in `DataSourceManager.ts`** — uses `dataLoader` field; if absent, fall back to the default `findByPage` loader.
6. **Verification** — re-run `pnpm test:page-designer` + the showcase 14-spec set; no behavior changes expected.

## Risks

- **`profile=admin` vs `profile=report`** — both runtime paths today go through the same three switches. Grep confirms no profile-keyed branches in the three target files; the registry is profile-agnostic. Re-validate after step 3-5.
- **`dataLoader` return type mismatch** — DataSourceManager today returns shapes that vary per blockType. The spec lets each block ship its own loader; the default loader is unchanged. New blocks without a loader inherit the default behavior.
- **Init order** — if `BlockRegistry.register` runs before bootstrap, callers get `undefined`. Mitigated by step 2 (eager bootstrap) plus a `console.warn` in `BlockRenderer` so the failure is loud.

## Test strategy

- **vitest** — `BlockRegistry.test.ts` covers register/get/has/size, double-register guard, and the fallback warning. Coverage target: 100% of registry surface.
- **vitest** — `BlockRegistry.bootstrap.test.ts` asserts `BlockRegistry.size() === 10` after `initBlockRegistry()` and that all currently-handled blockTypes (`table` / `filters` / `toolbar` / `form-section` / `chart` / `tabs` / `sub-table` / `stat-card` / `rich-text` / `divider`) resolve. This is the registry analogue of BACKLOG-WIDGET-001.
- **E2E (no new specs)** — page-designer main regression + showcase 14 specs must pass unchanged. Behavior parity is the bar.

## Decision log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-04-25 | Scope to runtime 3 sites only | Designer-internal 7 sites have heterogeneous semantics; folding them in inflates the API and re-creates today’s coupling under a new name. |
| 2026-04-25 | Eager bootstrap registration | The G1 widget-registry incident showed lazy init silently breaks schema-driven panels; mirror that pattern. |
| 2026-04-25 | Fallback warns + placeholder, not throws | Today silent `null` masks unknown blockTypes; warning makes the failure visible in dev without breaking prod. |

## Out of this PR

This design doc lands ahead of the implementation so reviewers can scope the API before the diff. The implementation PR will reference this doc and is expected to add ~250 lines net.

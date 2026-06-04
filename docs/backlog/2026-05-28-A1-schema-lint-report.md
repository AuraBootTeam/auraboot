---
type: backlog
status: active
created: 2026-05-28
---

# A1 — flow-designer-sdk JSON Schema validator + CLI lint — report

> Date: 2026-05-28 · Author: subagent · Branch: `wt/sdk-schema-lint` · Spec: [`2026-05-23-unified-graph-grammar-spec.md`](./2026-05-23-unified-graph-grammar-spec.md) · DDR: [`DDR-2026-05-23-automation-bpm-designer-convergence.md`](./DDR-2026-05-23-automation-bpm-designer-convergence.md)

## Deliverables (this slice)

1. **JSON Schema** for the unified GraphDocument grammar — `web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/graphDocumentSchema.ts`.
2. **Structural validator** — `validateGraphDocument(doc)` returns `{ valid, errors[] }`. Runs (a) ajv schema check + (b) the 4 semantic rules from spec §6 (ID uniqueness, edge endpoint integrity, exactly-one start, gateway out-edge condition/default).
3. **Divergence diff utility** — `diffGraphDocuments(a, b)` returns a typed report of the 4 known automation↔bpmn grammar divergences (`D1` envelope / `D2` `data.type` discriminator / `D3` bare-string `condition` / `D4` root-level meta fields).
4. **CLI lint tool** — `web-admin/scripts/validate-flow.mjs` (Node + tsx wrapper) supporting:
   - `validate-flow <file.json> [...]` — lint one or more docs (exit 0/1).
   - `validate-flow --diff <a.json> <b.json>` — audit a pair for the 4 divergences (exit 0 if conformant, 2 otherwise).
5. **Tests** — 17 unit cases in `validation/__tests__/validateGraphDocument.test.ts` covering happy path, schema-layer failures, all 4 semantic rules, and the divergence diff. All 17 pass.

## Test run

```
$ pnpm install --prefer-offline
$ ./node_modules/.bin/vitest run \
    app/plugins/core-designer/components/flow-designer-sdk/validation/__tests__/validateGraphDocument.test.ts
Test Files  1 passed (1)
Tests       17 passed (17)
Duration    644ms
```

## Lint-confirmed divergences (real-shape fixtures)

Two fixtures emit the JSON shape the **current** Automation editor and BPMN designer produce today (sourced from the live TS types in `web-admin/app/framework/smart/automation/components/AutomationEditor.tsx` and `web-admin/app/plugins/core-designer/components/bpmn-designer/types/index.ts`):

- `docs/backlog/a1-fixtures/automation-current.json`
- `docs/backlog/a1-fixtures/bpmn-current.json`
- `docs/backlog/a1-fixtures/automation-conformant.json` (positive control — already conforms to grammar 1.0)

Running `node scripts/validate-flow.mjs --diff automation-current.json bpmn-current.json` from `web-admin/` reports **19 divergences across all 4 buckets**:

| Code | Count | Side(s) | Evidence (excerpt) |
|------|-------|---------|--------------------|
| `D1` envelope missing | 6 | both | `/schemaVersion`, `/kind`, `/meta` absent on each input |
| `D2` `data.type` discriminator | 7 | both | automation: `"trigger"`/`"control"`/`"action"`; bpmn: `"startEvent"`/`"userTask"`/`"exclusiveGateway"`/`"endEvent"` |
| `D3` bare-string condition | 1 | automation | `edges[1].data.condition: "amount > 100"` (string, not `ConditionExpression`) |
| `D4` root meta fields | 5 | bpmn | `/key="leave-request"`, `/name="Leave Request"`, `/description=...`, `/category="hr"`, `/version=1` |

Validate-mode on the same automation fixture surfaces the same set as schema errors (3× `MISSING_REQUIRED`, 3× `DEPRECATED_FIELD`, 3× condition-type mismatches under `oneOf`). The `automation-conformant.json` fixture passes with `OK (0 errors)`, confirming the schema is satisfiable.

## What this slice does **not** do

- Does **not** modify the Automation or BPMN designer code — they still emit their legacy shapes. This SDK addition is a non-breaking lint/audit capability, intended to be wired into save gates and migration tooling in a follow-up.
- Does **not** validate per-node-type `data.config` payloads beyond "is an object". Field-level required-config validation continues to live in `validateFlow.ts` (driven by each domain's `NodeRegistry`).
- Does **not** cover spec §6.5 (per-node required-config) or §6.6 (i18n on user-facing text) — those are domain-layer concerns; the JSON-Schema layer intentionally stops at the structural envelope.

## Suggested follow-ups (out of scope here)

1. Wire `validateGraphDocument` into a designer save path once the migration to `GraphDocument 1.0` actually begins (T4 BPMN→SDK).
2. Add CI job that runs `validate-flow` against committed `*.flow.json` / `*.bpmn.json` fixtures (none exist today; would need to land alongside T4 migration).
3. Author migration script `migrate-flow-to-graph-document.mjs` that fixes D1–D4 mechanically — current diff output already enumerates everything that needs touching.

## Files changed

- `web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/graphDocumentSchema.ts` (new)
- `web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/validateGraphDocument.ts` (new)
- `web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/diffGraphDocuments.ts` (new)
- `web-admin/app/plugins/core-designer/components/flow-designer-sdk/validation/__tests__/validateGraphDocument.test.ts` (new)
- `web-admin/app/plugins/core-designer/components/flow-designer-sdk/index.ts` (export new API)
- `web-admin/scripts/validate-flow.mjs` + `web-admin/scripts/validate-flow.driver.ts` (new CLI)
- `docs/backlog/a1-fixtures/{automation-current,bpmn-current,automation-conformant}.json` (new)
- `docs/backlog/2026-05-28-A1-schema-lint-report.md` (this file)

---
type: retro
status: shipped
created: 2026-06-18
related:
  - docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md
  - docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md
---

# Testing Gate Acceptance Report — S6 Workbench + S5 Chart Dashboard browser golden (2026-06-18)

`allowed_claim`: **golden UI pass** for S6 (lead workbench real-data loop) and S5
(chart-widget dashboard render), on an isolated host-first stack. ChatBI ad-hoc
chart render is **did not run / no-UI-path** (no route mounts the component).

## Scope (current SOT)

- SOT: `docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md` (§ "S6 工作台
  浏览器 golden" + "S5 图表渲染浏览器 golden") and the upstream scenario design
  `docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md`
  (S5 ChatBI/chart, S6 workbench).
- `business_scope`: OSS `crm-starter` demo plugin over the seeded `crm_lead` model
  (90 leads across new/contacted/qualified/converted/lost). In scope: the workbench
  block family real-data loop (metric-strip → table re-query → review-drawer →
  status command) and the dashboard chart-widget render path (bar/pie/number-card).
  Explicit non-goal: ChatBI ad-hoc UI (component is unmounted — see findings).
- `historical_or_superseded_rules`: the scenario doc marked these as "无 golden";
  verification (§15) found the incumbent coverage was partial, not absent —
  `web-admin/tests/e2e/page-designer/workbench-blocks-runtime.spec.ts` already
  proves the workbench blocks on **static** data, and
  `crm-starter-demo-dashboard.spec.ts` covers **smart-table-chart** widgets. The
  genuine remaining gaps (real seeded-data filter loop; bar/pie/number chart
  render) are what this work closes.

## Why the gaps were real (not phantom)

- S6: the incumbent workbench runtime spec uses `type:'static'` mock dataSources and
  metric clicks only flip button visibility — it never proves a metric chip
  re-queries a table (row count change) over a real model, nor a status mutation.
- S5: no OSS dashboard used `smart-bar-chart`/`smart-pie-chart`/`smart-number-card`;
  chart rendering with real aggregated data was untested. The ChatBI spec
  (`tests/e2e/ai/chat-bi.spec.ts`) is API-only (`page.request`), and `ChatBIPanel`
  is exported but never imported by any route → no browser UI to golden.

## Test layer matrix

| Layer | Status | Evidence |
|---|---|---|
| Web E2E (S6 workbench) | tested | `tests/e2e/crm-starter-lead-workbench.spec.ts` (4 tests) — host Vite+Playwright |
| Web E2E (S5 chart dashboard) | tested | `tests/e2e/crm-starter-lead-analytics.spec.ts` (3 tests) |
| Backend integration (S5 skill shape) | tested | `DashboardGeneratorSkillIT` (1 test) — asserts emitted ChartDataSource shape |
| Runtime seam (plugin import) | tested | platform validator `import-directory-sync` → `success:true` (NAMED_QUERY CREATE 1, PAGE CREATE 1, DASHBOARD registered) |
| Permission negative | did_not_run | admin-only golden; lead row-action `permissionCode: crm.lead.manage` carried but deny path not separately exercised |
| Artifact/export | n/a | no export/download in scope |

## Evidence

### S6 — CRM Lead Console workbench (`/p/c/crm_lead_workbench`)
New artifacts (shipped in `crm-starter`):
`config/pages/crm_lead_workbench.json`, `config/named-queries/crm_lead_status_stats.json`,
`plugin.json` (+`namedQueries` resourceDir), `config/menus.json` (+menu).

- **S6-1 KPI real values**: metric-strip renders `total=90, new=26, contacted=26,
  qualified=18, converted=11, lost=9` (buckets sum to total); no `-` placeholder. ✓
- **S6-2 metric click → table re-query**: clicking `qualified` filters the table to
  18 rows, `lost` → 9 rows, `total` → back to first-page (50) — real
  `${state.statusFilter}` + `dependOn` re-fetch, row count actually changes. ✓
- **S6-3 review-drawer**: selecting a row opens the drawer (deferred
  `selectedLeadDetail` dataSource) with the lead summary; empty-state clears. ✓
- **S6-4 status command → DB + reflect**: `crm:contact_lead` row action transitions
  a `new` lead to `contacted` (asserted via the dynamic-list API), and after
  refetch the KPIs move (new −1, contacted +1). ✓
- 0 product console errors (Vite dep-optimize 504 noise filtered as dev-server
  artifact, handled with a one-reload guard).

`browser_evidence`: 4/4 pass, list reporter, re-run stable.
`backend_evidence`: lead status transition confirmed via `/api/dynamic/crm_lead_list/list`.

### S5 — Lead Analytics chart dashboard (`/dashboards/view/crm_lead_analytics`)
New artifact: `config/dashboards/crm_lead_analytics.json` (smart-number-card +
smart-bar-chart + smart-pie-chart over `crm_lead` aggregates) + menu.

- **S5-1**: all 3 widgets mount (`dashboard-block-*`), **0** `chart-empty-state`, ≥2
  ECharts canvases (bar+pie render real geometry). ✓
- **S5-2**: number card shows the real total (>0, not "No data yet"). ✓
- **S5-3**: bar widget config is `type:aggregate, modelCode:crm_lead`; canvas visible
  inside the bar block. ✓

`browser_evidence`: 3/3 pass.

## Findings (green-but-broken surfaced by the goldens)

1. **Row-action `visibleWhen` must use `row.` not `record.`** — `record` in a table
   block resolves to the page detail-record (undefined on a custom workbench), so
   `record.<status> == 'x'` is always false and the row action is silently hidden.
   The renderer (`TableBlockRenderer.tsx:360`) passes `row`. Mirroring the
   enterprise `mfg_andon_workbench` (which uses `record.`) reproduced the hidden
   button; switching to `row.` fixed it. **Latent-bug candidate**: 6+ enterprise
   pages use `record.<field>` in table row actions — worth an audit (backlog).
2. **`DashboardGeneratorSkill` emitted an unrenderable dataSource shape** — it wrote
   `config.dataSource:{type, code}`, but the widget renderer
   (`web-admin/.../hooks/useChartData.ts`) reads `queryCode` (namedQuery) and
   `modelCode`+`metrics` (aggregate); `{type,code}` fails `isDataSourceComplete()` →
   generated charts render empty ("No data yet"). The backend `DashboardGenerationLiveIT`
   only checked persistence, never render, so this shipped green. **Fixed**:
   `buildWidgets` now emits `{type:'namedQuery', queryCode}` / `{type:'aggregate',
   modelCode, dimensions, metrics}` (+ optional `dimension`/`metricField`/`aggregation`
   schema fields); `DashboardGeneratorSkillIT` now asserts the renderer-correct shape
   (and that the dead `code` key is gone).
3. **ChatBI ad-hoc chart render has no browser surface** — `ChatBIPanel.tsx` is
   exported from `index.ts` but imported by no route; the standalone page was
   "absorbed into AuraBot" but never wired into the chat UI. A UI golden would
   require building a mount first (a feature change, out of scope). Intent layer is
   covered by the backend `ChatBiIntentLiveIT`. Recorded as did_not_run/no-UI-path.

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-06-18-s5s6-workbench-dashboard-golden-testing-gate-acceptance-report.md
claim_level: golden-candidate
current_sot: docs/backlog/2026-06-18-platform-and-test-gaps-resolution.md; docs/backlog/2026-06-17-platform-capability-map-and-test-scenario-design.md
business_scope: crm-starter seeded crm_lead (90 rows) — workbench real-data loop + dashboard chart render; ChatBI ad-hoc UI is a non-goal (unmounted)
integration_tests: DashboardGeneratorSkillIT (1/1 pass, asserts emitted ChartDataSource shape)
integration_coverage: coverage_not_measured (targeted IT only; render path covered by E2E)
e2e_specs: tests/e2e/crm-starter-lead-workbench.spec.ts (4); tests/e2e/crm-starter-lead-analytics.spec.ts (3) — both added to oss-scope.json ([oss]+[oss-deep])
feature_action_matrix: S6 {KPI real values, metric→table re-query, review-drawer, status command→DB+reflect} = closed; S5 {bar render, pie render, number-card value, no empty-state} = closed
browser_evidence: 7/7 pass on isolated host-first stack (backend 6452 / Vite 5152 / BFF 6152, DB auraboot_52), re-run stable
backend_evidence: platform validator import-directory-sync success:true; lead status transition via dynamic-list API; namedQuery counts == filtered row counts
artifact_evidence: n/a
permission_negative: did_not_run (admin-only golden; row-action permissionCode carried)
visual_feedback: metric chip active filter, table row-count change, review-drawer open/close, KPI refresh after command, chart canvases
skip_fixme_threshold_retry_audit: no skip/fixme/threshold/retry in the new specs; 1 reload-guard for Vite dep-optimize 504 (dev-server artifact, classified)
did_not_run: ChatBI ad-hoc chart browser render (no route mounts ChatBIPanel); full OSS gate (46 specs) not run — targeted only
remaining_blockers: none for S5/S6; follow-up backlog: audit enterprise pages using record.<field> in table row actions (finding 1)
allowed_claim: golden UI pass (S6 workbench real-data loop; S5 chart dashboard render); ChatBI ad-hoc = did not run / no-UI-path
```

## Operational state

- Branch `feat/s5s6-workbench-dashboard-golden`; isolated worktree
  `/Users/ghj/work/auraboot/auraboot-s5s6-golden`.
- Runtime `s5s6-workbench-dashboard-golden-52` (auraboot slot 52): backend 6452,
  Vite 5152, BFF 6152, isolated DB `auraboot_52`. Host-first, zero docker. No
  DeepSeek key used (all deterministic) → nothing to redact.
- Concurrency-safe bringup: port-scoped stop + `reset-db` honoring `POSTGRES_DB`
  (never touched shared `aura_boot`); shared gradle/pnpm caches + per-runtime m2
  seeded with the SmartEngine fork (proxy flaked on fresh per-runtime caches).

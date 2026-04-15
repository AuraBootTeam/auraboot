# Widget Inventory Audit — 2026-04-15

Baseline audit for OSS/Enterprise tier boundary split.

## Registry file

`web-admin/app/plugins/core-dashboard/widgets/widgetRegistry.ts` — 1602 lines

## All widget types found (37 unique `type:` values)

- smart-activities
- smart-announcement
- smart-area-chart
- smart-bar-chart
- smart-calendar
- smart-combo-chart
- smart-countdown
- smart-funnel-chart
- smart-gallery
- smart-gauge-chart
- smart-heatmap-chart
- smart-iframe
- smart-image
- smart-inbox
- smart-kanban
- smart-leaderboard
- smart-leads
- smart-line-chart
- smart-map-chart
- smart-my-process
- smart-nps-chart
- smart-number-card
- smart-pie-chart
- smart-pipeline
- smart-process-stats
- smart-progress
- smart-quick-note
- smart-radar-chart
- smart-recent
- smart-rich-text
- smart-scatter-chart
- smart-shortcuts
- smart-stats-card
- smart-stats-row
- smart-table-chart
- smart-treemap-chart
- smart-wordcloud-chart

## File counts

| Location | Count |
|----------|-------|
| `web-admin/app/plugins/core-dashboard/widgets/` | 2 items (widgetRegistry.ts + workbench/) — NOT 34 files |
| `web-admin/app/plugins/core-dashboard/widgets/workbench/` | 13 .tsx files (individual widget components) |
| `web-admin/app/framework/smart/components/charts/` | 30 items (28 .tsx + index.ts + shared/) |

## OSS-tier types verification

Planned OSS-tier list (11 types) vs registry:

| Type | In Registry |
|------|-------------|
| smart-number-card | YES |
| smart-bar-chart | YES |
| smart-line-chart | YES |
| smart-pie-chart | YES |
| smart-area-chart | YES |
| smart-table-chart | YES |
| smart-progress | YES |
| smart-rich-text | YES |
| smart-image | YES |
| smart-iframe | YES |
| smart-countdown | YES |

All 11 OSS-tier types are present in the registry.

## WidgetDefinition interface

File: `web-admin/app/plugins/core-dashboard/types/index.ts` line 248

## Discrepancies vs. plan audit baseline

| Claim in plan | Ground truth | Impact |
|---------------|-------------|--------|
| "34 widgets at `widgets/`" | `widgets/` contains 2 items (widgetRegistry.ts + workbench/); workbench/ has 13 .tsx widget components | Minor path confusion — widget components are in `widgets/workbench/`, not `widgets/` directly |
| "29 chart components at `charts/`" | 30 items total (28 .tsx components + index.ts + shared/); SmartGanttChart.tsx and SmartParetoChart.tsx and SmartSPCChart.tsx present; also shared/ subdirectory | 3 chart files not reflected in registry types (GanttChart, ParetoChart, SPCChart) — likely enterprise-only or pending registration |
| "11 widgets classified as OSS" | All 11 OSS-tier types confirmed present | No issue |
| "24 as Enterprise-tier" | Actual count: 37 total - 11 OSS = 26 unclassified as enterprise | 2 more types than expected (total is 37, not 35) |

### Unregistered chart components (in `charts/` but no matching registry type)

- `SmartGanttChart.tsx` — no `smart-gantt-chart` type in registry
- `SmartParetoChart.tsx` — no `smart-pareto-chart` type in registry
- `SmartSPCChart.tsx` — no `smart-spc-chart` type in registry

These 3 components exist in the charts directory but have no corresponding registry entries.

### Registry type count vs. plan

- Plan assumed ~35 widget types (11 OSS + 24 Enterprise)
- Actual: **37 registered types**
- Difference: +2 (smart-stats-card and smart-stats-row appear to be the extra types, or two enterprise types were uncounted)

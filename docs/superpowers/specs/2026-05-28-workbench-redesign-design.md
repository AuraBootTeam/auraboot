# Workbench Homepage + Top Bar Visual Redesign

**Date:** 2026-05-28
**Status:** Spec — pending user review
**Scope:** OSS `auraboot/web-admin` only (enterprise overlay unaffected unless it overrides the same widgets)

## Goal

Replace the dated 2018-admin-template look of `/home` (Workbench) and the global top bar with a Stripe-Dashboard-style visual treatment: neutral whites, single brand color, sparkline-augmented KPIs, table-driven Tasks. Sidebar untouched.

The redesign is **visual only** in this round — no information architecture changes beyond the table swap and removing the gradient KPI cards. The Workbench remains a `Dashboard`-config-driven page rendered by `DashboardViewer`; the widgets it composes get restyled, and the default workbench dashboard JSON gets updated to use the new layout.

## Non-goals (this round)

- Global design-token system / shadcn migration / sidebar redesign — deferred (option C from brainstorming, will be its own future spec)
- Restyling business CRUD pages, Page Designer output, enterprise plugin overlay pages
- Adding new widget types — only restyling existing ones
- Task priority field (rejected: not in current Inbox data contract)
- Recent Visits widget redesign (visual will follow from the new card style automatically)

## What changes

### 1. Workbench KPI cards — `StatsCardWidget` / `StatsRowWidget`

**Before:** 8 vibrant gradient backgrounds (`bg-gradient-to-br from-blue-500 to-blue-700` etc.), white text, oversized.

**After:** white card, 1px neutral border (`#e3e8ee`), neutral-900 text, brand-purple sparkline (`#635bff`), green delta text for positive trend, red delta for negative trend, muted "— no change" for flat.

Per-card structure:
```
┌──────────────────────────────────┐
│ PENDING TASKS                    │  ← 11px uppercase, neutral-500
│                                  │
│ 241                              │  ← 28-32px semibold, neutral-900
│                                  │
│ ▲ 5.2% vs last week    ╱╲╱      │  ← delta + sparkline
└──────────────────────────────────┘
```

- Card height fixed (~128px) so 0-value cards keep visual rhythm
- 7-day sparkline rendered with SVG `polyline` (no chart lib needed)
- Flat / no-data state: thin horizontal line on the sparkline area + "— no change" delta

**`GRADIENT_MAP` constant** in `StatsCardWidget.tsx` is removed. The `gradient` prop becomes a no-op (kept for backwards compat with existing dashboard JSON, deprecated with a comment; safe to drop in a follow-up once OSS + enterprise dashboards are migrated).

### 2. Workbench Tasks — `InboxWidget`

**Before:** vertical list of gray-avatar rows with single-line title and "command · 1d ago" meta.

**After:** compact table:
| Task                  | Type      | Due       |
|-----------------------|-----------|-----------|
| Close Capa            | Approval  | Today     |
| Verify Capa           | Task      | Tomorrow  |

- Header row: `#fafbfc` bg, 11px uppercase neutral-500 labels
- Type rendered as colored Badge (Approval = amber, Task = blue, Alert = red)
- Due rendered as relative-time text ("Today", "Tomorrow", "Fri", "Next week")
- No avatar column, no priority column (we chose option c)
- Tabs (All / Approvals / Tasks / Alerts) remain, restyled as underline tabs

### 3. Workbench Quick Actions — `ShortcutsWidget`

**Before:** colored sticker tiles, each with a different pastel background (rose / mint / amber / etc.).

**After:** clean list-card with consistent icon tiles:
- 32px square icon tile, neutral `#f0f3f7` background, brand-color glyph
- Single-line action name
- Subtle `›` chevron on the right
- Hover: row bg goes `#fafbfc`
- "Click Edit to customize shortcuts" footer text removed (already discoverable via existing edit affordance)

### 4. Page header

Add a `Workbench` title + dated subline ("Tuesday, May 28 · Overview") above the KPI row, and a top-right action cluster (`Export`, `+ New` primary button). This is rendered by the Workbench page wrapper (`plugins/core-dashboard/pages/home/index.tsx`), not by a widget.

### 5. Top bar polish — `AdminLayout` Header region

| Item                    | Before                          | After                              |
|-------------------------|---------------------------------|------------------------------------|
| Header height           | 64px                            | 56px                               |
| Logo lockup             | `[AB] AuraBoot · AuraBoot Dev`  | `[AB] AuraBoot` + grey "Dev" chip  |
| Search box              | Fills middle, light-gray fill   | 360px fixed width, white + 1px border, ⌘K hint pill |
| Right icon size         | 36px                            | 32px                               |
| Notification badge      | Solid red `99+` round pill      | Small brand-purple `99` corner badge |
| Avatar                  | 38px, no border                 | 30px with 1px neutral border       |
| Right cluster grouping  | Flat row                        | Tool icons + 1px vertical divider + avatar |

Padding, icon spacing, and hover treatments tightened to match Stripe-dashboard rhythm.

## Architecture context (why this is contained)

The Workbench at `/home` is rendered by `DashboardViewer` consuming a `Dashboard` config returned by `dashboardService.getWorkbench()`. The page itself (`plugins/core-dashboard/pages/home/index.tsx`) is thin — loading state + error state + `<DashboardViewer />`. The visible widgets live under `plugins/core-dashboard/widgets/workbench/`:

```
widgets/workbench/
  StatsCardWidget.tsx      ← restyle (drop gradients)
  StatsRowWidget.tsx       ← restyle (same)
  InboxWidget.tsx          ← restyle (list → table)
  ShortcutsWidget.tsx      ← restyle (sticker tiles → list)
  RecentWidget.tsx         ← inherits new card style (no dedicated change)
  useWorkbenchStats.ts     ← extend (consume 7-day series)
```

**Default workbench dashboard JSON** (the seed Dashboard config) likely needs touch-ups to drop per-card `gradient` overrides and to use the new Quick Actions layout slot. Owner: locate the seed in `core-dashboard` resources or its bootstrap fixture during implementation; no schema change, just data.

Top bar lives in `routes/AdminLayout.tsx` (or its Header subcomponent — confirmed during implementation).

## Data contract changes

### Backend: 7-day time series on KPI stats endpoint

`/api/workbench/stats?keys=...` currently returns per-stat:
```jsonc
{ "key": "inbox_pending", "value": 241, "trend": { "direction": "up", "value": 5.2, "period": "week", "unit": "percent" } }
```

Extend each `StatItem` with:
```jsonc
"series": {
  "period": "day",
  "points": [220, 225, 223, 232, 235, 240, 241]   // 7 daily snapshots, oldest → newest
}
```

- Backend owner produces this for every stat the workbench renders. For stats currently zero (`opportunities_active`, `processes_running`), `series` may be `null` — frontend renders the flat line + "— no change" treatment.
- Daily granularity, count = 7. No need for hourly / per-tenant overrides this round.
- Cache: 5-minute TTL is fine; sparkline is informational, not analytical.

### Frontend: no breaking change

`useWorkbenchStats` hook surfaces the new `series` field on each `StatItem`. Existing callers ignore it. `StatsCardWidget` becomes the new consumer.

## Visual tokens used in this round

These are component-local, **not** a global token system. A future Option-C spec would lift them to a real token layer.

| Token              | Value      | Use                                  |
|--------------------|------------|--------------------------------------|
| `bg-base`          | `#fafbfc`  | Page background, table header row    |
| `bg-card`          | `#ffffff`  | Card surfaces                        |
| `border-card`      | `#e3e8ee`  | Card / table 1px borders             |
| `text-primary`     | `#0a2540`  | Headings, KPI numbers                |
| `text-muted`       | `#697386`  | Labels, meta text                    |
| `text-subtle`      | `#a3acb9`  | Placeholder, timestamps              |
| `brand`            | `#635bff`  | Sparkline, primary button, focus     |
| `delta-positive`   | `#067647`  | "+5.2% vs last week"                 |
| `delta-negative`   | `#b91c1c`  | "−3.1% vs last week"                 |
| `radius-card`      | `10px`     | Cards                                |
| `radius-button`    | `6-8px`    | Buttons, badges                      |

Defined inline as Tailwind utilities for now; lifted to CSS vars / shadcn theme in the future global-token round.

## Testing

- **Unit (vitest):** snapshot tests on restyled widgets (`StatsCardWidget`, `InboxWidget`, `ShortcutsWidget`); a small render test asserting sparkline `polyline` renders the correct point count when `series` is provided and is omitted when `null`.
- **E2E (Playwright, OSS suite):** add / update a `web-admin` spec under `e2e/specs/workbench/` covering: Workbench loads, 4 KPI cards visible with correct values, sparkline svg renders, Tasks table shows ≥1 row with Type badge, Quick Actions list renders, search bar opens on click, ⌘K shortcut triggers search. Reuses existing seed.
- **Visual review (manual):** screenshot Workbench at default 1440×900 viewport in both light and dark mode; check against the option-B / topbar-compare mockups in `.superpowers/brainstorm/`. (Dark mode: AuraBoot already has a theme toggle; redesign uses neutral classes that should adapt — verify and adjust only if regressions appear.)

## Out of scope / explicit follow-ups

- **Global design-token system (option C)** — separate future spec; this round leaves component-local Tailwind utilities.
- **Backend 7-day series for non-workbench dashboards** — if other Dashboards consume the same endpoint, they get the new field for free; restyling other Dashboards is a separate effort.
- **Enterprise overlay parity** — if `web-admin-ext` overrides any of the four widgets restyled here, it needs a follow-up PR. To be confirmed during implementation by `grep -rn "StatsCardWidget\|InboxWidget\|ShortcutsWidget\|RecentWidget" auraboot-enterprise/web-admin-ext/`.
- **Tasks priority** — rejected this round; revisit when Inbox data contract exposes a priority field.
- **Dropping the `gradient` prop on `StatsCardWidget`** — kept as no-op this round; remove once OSS + enterprise dashboards no longer set it.

## Risks

1. **Default workbench dashboard JSON has hardcoded gradient overrides** — visual still renders but as flat cards (no functional break). Fix during implementation by editing the seed.
2. **Enterprise overlay duplicates one of the widgets** — visual divergence between OSS and enterprise. Detect early with grep; if duplicated, file an enterprise follow-up.
3. **Dark mode regression** — current widgets use solid color classes (`from-blue-500` etc.). Replacing with neutral whites means dark mode must explicitly handle the new surfaces. Test in dark mode before merge.
4. **E2E baseline diff** — any visual-regression baseline screenshots will need re-capture. Cost: low (Workbench is one page); flag for the E2E reviewer.

## Success criteria

- `/home` renders without gradient backgrounds; 4 KPI cards in white + sparkline; Tasks shows as a table; Quick Actions as a consistent list.
- Top bar height 56px; search box fixed-width with ⌘K hint; brand-purple notification badge; 30px avatar with border.
- All existing Workbench unit + E2E tests pass after baseline update.
- Manual screenshot review (light + dark) shows no obvious regression on other pages that share the layout shell.

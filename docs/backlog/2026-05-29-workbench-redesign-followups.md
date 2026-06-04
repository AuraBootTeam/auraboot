---
type: backlog
status: active
created: 2026-05-29
---

# Workbench Redesign — Follow-up Backlog

**Source PR:** [#336](https://github.com/AuraBootTeam/auraboot/pull/336)
**Source spec:** `docs/superpowers/specs/2026-05-28-workbench-redesign-design.md`
**Date:** 2026-05-29

## P1 — should land in the next round

### W-FU-1 · Manual visual verification (light + dark)
- **Why:** PR landed with this step unchecked because no full local stack was available during implementation.
- **Owner:** PR reviewer or next session that owns `web-admin`.
- **Action:** `cd /Users/ghj/work/auraboot-wt/workbench-redesign && (start backend + BFF + vite) → open /home → screenshot 1440×900 in light and dark → save under docs/superpowers/screenshots/2026-05-29-workbench-redesign/` → if any dark-mode regression, follow-up PR with adjusted `dark:` Tailwind classes.

### W-FU-2 · 7-day series for the other 3 KPI stats
- **Scope:** `crm_opportunity_amount`, `bpm_running`, `crm_account_active` all currently emit `series: null`. Frontend renders the flat-baseline state. Once cheap historical queries exist per stat, populate.
- **Files:** `platform/.../WorkbenchStatsServiceImpl.java`, sibling mappers per stat.
- **Note:** Mirror the pattern used for `inbox_pending` (Postgres `generate_series` LEFT JOIN), tenant scoping must match each stat's existing count query.

### W-FU-3 · Inbox quick-approve action restoration
- **Why:** In the new table layout we dropped the inline approve button that existed on urgent approval rows in the old list layout. Implementer flagged this as DONE_WITH_CONCERNS at Task 7.
- **Options:** (a) row-hover action column with Approve/View buttons; (b) right-side detail panel on row click; (c) keep dropped and rely on the dedicated `/inbox` page.
- **Owner:** product decision needed before implementation.

### W-FU-4 · Drop deprecated `gradient` prop from `StatsCardWidget`
- **Why:** Kept as a typed no-op for backward compatibility with any dashboard JSON that still sets it. Plan recommended removing once OSS + enterprise overlay dashboards are migrated.
- **Pre-req:** confirm no dashboard JSON in `platform/src/main/resources/seed/` or `auraboot-enterprise/` sets `gradient` on a workbench Stats widget. (Initial audit found none — re-confirm before removal.)

## P2 — nice-to-have

### W-FU-5 · Workbench page header "Export" / "+ New" button behaviors
- **Current state:** styled placeholders, no `onClick`. Buttons render fine; pressing does nothing.
- **Suggestions:** "Export" → trigger an existing dashboard-export-excel flow if applicable; "+ New" → open a command palette filtered to "create" actions.

### W-FU-6 · Sparkline tooltip on hover
- **Current state:** SVG `polyline` with no interactivity.
- **Cost vs value:** small, but only worth adding if users actually inspect 7-day numbers (probably not in the workbench summary context). Defer until asked.

### W-FU-7 · i18n parity audit for new workbench keys
- **New keys added this round:** `workbench.title`, `workbench.subline`, `workbench.export`, `workbench.new`, `workbench.inbox.col.task`, `workbench.inbox.col.type`, `workbench.inbox.col.due`.
- **Verify:** `node scripts/validate-i18n-parity.mjs` (if it exists) — confirm all 7 keys have non-empty `zh-CN` and `en-US` values.

### W-FU-8 · Run the new Playwright smoke against a full local stack
- **Spec:** `web-admin/tests/e2e/workbench/workbench-redesign.spec.ts`
- **Status:** committed but not executed (no live backend during implementation). Run it in the next E2E full-suite session and confirm baseline.

## P3 — track but don't implement now

### W-FU-9 · Stale `cmd-k-trigger` testid name
- The CommandPalette trigger keeps its 1.x-era testid `cmd-k-trigger`. The Header redesign almost renamed it (caught in code review). Long-term we could rename to `header-search-trigger` for clarity, but it requires updating 4 existing E2E specs:
  - `web-admin/tests/e2e/search/global-search.spec.ts` (4×)
  - `web-admin/tests/e2e/search/command-palette-docs.spec.ts` (2×)
  - `web-admin/tests/e2e/showcase/showcase-smoke.spec.ts` (1×)
- Wait until one of those specs is being touched anyway for a different reason — opportunistic.

### W-FU-10 · Enterprise overlay parity re-check
- The Task 14 audit on 2026-05-29 found **no** enterprise overlay overrides of `StatsCardWidget` / `StatsRowWidget` / `InboxWidget` / `ShortcutsWidget`. Re-run the grep if any of those widgets is forked into enterprise in the future.

## Process backlog (not code)

### W-FU-11 · Lift retro lessons G-W1..G-W5 into canonical AGENTS.md
- **Owner:** done in a sibling PR against `auraboot-enterprise` (see retro doc §3).

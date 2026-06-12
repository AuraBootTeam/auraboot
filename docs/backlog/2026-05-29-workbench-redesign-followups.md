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

## P0 — regressions found in live visual verification (2026-05-29)

Discovered during Item 1 (manual visual verification of `/home` at 1440×900, light + dark). Top bar redesign renders as designed, but 3 regressions need follow-up:

### W-FU-12 · Top-bar "Dev" chip is hardcoded
- `web-admin/app/routes/Header.tsx` renders a literal `"Dev"` chip next to the AuraBoot wordmark.
- Will display `"Dev"` in production. Should read `import.meta.env.MODE` (or equivalent) and render env-appropriate label, or hide entirely in `production`.

### W-FU-13 · Tenant indicator duplicates with Dev chip
- `Header.tsx` lines ~170-175 still render `· {user.tenantName}` as a sibling of the brand link.
- When tenant is named e.g. "AuraBoot Dev", visible output becomes: `[logo] AuraBoot [Dev chip] · AuraBoot Dev` — visually duplicated.
- Decide: remove the `· {tenantName}` indicator (spec direction — chip replaces it), or hide chip when tenantName already encodes env.

### W-FU-14 · Empty-state branch bypasses the new header band
- `web-admin/app/plugins/core-dashboard/pages/home/index.tsx` lines 61-69 early-return a centered empty state when `!dashboard || !dashboard.widgets?.length`.
- This early return skips the new `<div className="px-8 py-6 ..."><header>...</header>...` chrome added in this round, so tenants without a seeded workbench config see the old bare empty state, not the redesigned page shell.
- Fix: wrap the empty state inside the new page chrome (`px-8 py-6 bg-[#fafbfc] dark:bg-gray-900 min-h-full` + `<header>` title/subline) and keep the centered "no widgets" CTA as the body.

### W-FU-15 · Re-screenshot after W-FU-12/13/14 land
- Screenshots taken on 2026-05-29 captured the regressions above; re-take light+dark after fixes merge and update success-criteria evidence.

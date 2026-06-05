# Session Handover - 2026-06-05 — smart-mfg golden, next = gap #4 (empty detail pages)

> Focused on the **smart-mfg PCBA golden** task line only. Full prior-session detail (OEE + SPC) is in
> `HANDOVER-2026-06-04-smartmfg-oee-dashboard-golden.md` (same dir) and the recipe in
> `~/.claude/plans/2026-06-04-smartmfg-golden-build.md`. memory line: `智能制造/PCBA` in MEMORY.md.

## Session Summary

Completed **two golden slices end-to-end + merged 5 PRs**: the OEE 大屏 downtime fix and the SPC
control-chart live golden (gap #3). Both verified on a real hybrid-jar stack with real-browser golden.
**Next HEAVY task = gap #4: ~40 empty auto-stub `pe_` detail pages need real field layouts.**

## Tasks Completed (this session — all MERGED)

- **OEE 大屏 downtime bug** — `EndDowntimeHandler` failed to persist `pe_dt_duration_hours` (two causes:
  `(String)` cast on a `timestamptz`→`java.sql.Timestamp` read; field was `computed_readonly` → stripped
  from every write by `filterVirtualFields`). Fixed → availability is real (89%, was stuck at 100%).
  **plugins #12 `41d2d4b`**.
- **SPC import unblock** — `qc_spc_chart_points` named-query shipped invalid `status:"active"` → crashed
  the whole quality→pcba import chain. `active`→`published`. **plugins #13 `10b6c1b`**.
- **SPC control-chart golden (gap #3)** — surfaced + fixed 2 more gaps:
  - **plugins #17 `d86153d`** — USL/LSL settable (were in no inputFields/form → Cp/Cpk uncomputable) +
    control-chart param `${record.id}`→`${record.pid}`.
  - **OSS #428 `0bc2fe189`** (platform, reusable) — detail-page `chart` blocks fed by a namedQuery now
    forward record-scoped `params` + resolve `${record.*}` (`ChartBlockRenderer.resolveRecordParams` +
    `DetailPageContent`). Benefits *all* detail-page namedQuery charts.
  - Golden PASS: list + form-section (UCL/CL/LCL/USL/LSL/Cp/Cpk all real) + control chart renders 23
    points + limits + 3 WE-Rule-1 violations.

## Tasks In Progress / Next (gap #4 — NOT started)

**gap #4: author real field layouts for the ~40 empty auto-stub `pe_` detail pages.**
- Per the plan: **52 `pe_` detail pages exist, ~40 are EMPTY auto-stubs** (the platform's
  `MetaModelServiceImpl.autoCreateDefaultPages` generates `blocks:[{form-section with NO fields},{tabs}]`
  on model publish). 12 detail pages are real; forms 41/51 real; lists 54/54 real (§2.2 空壳红线).
- These live in `auraboot-plugins` (mostly `pcba-manufacturing/config/pages/*_detail.json`, plus other
  pcba-* plugins). They are config (DSL), not code — no jar rebuild needed for the page edits.

### Recommended gap #4 approach
1. **Identify the stubs precisely** (don't trust the "~40"): for each `pe_*_detail.json`, a stub has
   form-section blocks with empty `fields:[]` (or only `{form-section},{tabs}` with no real fields).
   Build a coverage matrix: `model | detail page | stub? | source for fields (form page / list columns / binding)`.
2. **Author fields per model** — mirror the model's `*_form.json` field set or the list columns, grouped
   into form-section(s). Use only `DslRegistry` whitelisted blockTypes/dataTypes (**§21** — list the
   whitelist in any subagent prompt: blockType `form/form-section/form-buttons/table/filters/toolbar/
   description/chart/tabs/sub-table/monthly-grid/stat-card/...`; dataType `string/date/json/number/array/
   boolean` — note model field dataTypes also allow `datetime`/`decimal`). Detail pages use V2 flat format
   (`schemaVersion:2`, `kind:"detail"`) — see the real ones (e.g. `qc_spc_chart_detail.json`,
   `pe_oee_dashboard`-adjacent, or the 12 already-real detail pages) for the pattern.
3. **Parallelize** — these are independent pages → ideal for `dispatching-parallel-agents` (a few pages
   per sonnet agent, main loop verifies). Each agent: author + run the platform validator gate.
4. **Gate + golden** — per plugin: `import-directory-sync` returns `success:true` (the authoritative
   §2.2 gate) + real-browser golden on a representative sample (navigate to the detail page, assert the
   fields render with real labels/values, no raw-code leak, no "empty form-section").

### How to bring up the golden stack (proven recipe — needed for gap #4 golden)
```
# 1. worktree off latest origin/main (both repos), build hybrid jars from the plugins worktree
git -C /Users/ghj/work/auraboot/auraboot worktree add --detach <ossWT> origin/main
git -C /Users/ghj/work/auraboot/plugins  worktree add -b fix/<slug> <pluginsWT> origin/main
cd <pluginsWT> && gradle :buildAllPluginJars -x test          # → build/plugin-jars/*.jar (17)
# 2. isolated stack from the OSS worktree, mount plugins config + jars
cd <ossWT>
ENTERPRISE_PLUGINS_DIR=<pluginsWT> ENTERPRISE_PLUGIN_JARS_DIR=<pluginsWT>/build/plugin-jars \
  scripts/dev/start-isolated.sh --slug=<slug> --rebuild --wait --skip-pull
# 3. bootstrap admin (BE_PORT is in <ossWT>/.aura-stack/<slug>.env — NOT the canonical .aura-stack!)
cd <ossWT>; source scripts/lib/reset-init-common.sh
aura_bootstrap_setup_if_needed "http://localhost:<BE_PORT>" "AuraBoot Dev" admin@auraboot.com Test2026x "Admin User" single "[<slug>]"
# 4. import — run FROM <ossWT>; do NOT pass host ENTERPRISE_PLUGINS_DIR (see Pitfall 1)
scripts/import-plugins.sh --slug=<slug> --profile=pcba-agent --edition=enterprise
```

## Key Decisions

| Decision | Chosen | Rationale |
|---|---|---|
| downtime fix | drop `computed_readonly` + coerce `Object→Instant` | field is system-written by the handler, not formula-computed; `computed_readonly` (no expression) is neither user-writable nor engine-materialized → always null |
| chart params (Gap A) | resolve `${record.*}` in `DetailPageContent` (has the record) before dispatch | the chart block's own runtime context does NOT carry the detail record; backend already binds `request.parameters` |
| 5 separate PRs | one concern per PR across the 2 repos | downtime / SPC-import / SPC-config / chart-platform are independent; reviewable in isolation |

## Pitfalls & Workarounds

1. **`import-plugins.sh` "plugin.json not found / missing"** — in `--slug` docker mode it resolves plugins
   at the **container** path `/app/plugins-enterprise` via `docker exec`. Passing `ENTERPRISE_PLUGINS_DIR=<host
   path>` to `import-plugins.sh` makes it `docker exec [ -f <host-path> ]` → never exists → all business/pcba
   plugins "missing". Only pass it to `start-isolated.sh` (for the mount), NOT to the import.
2. **`set -e` + `| tail` defeats failure detection** — piping each bringup step to `tail` makes the pipe exit
   0 even on failure (BE_PORT read from the wrong `.aura-stack` path silently produced an empty URL). Read
   BE_PORT from `<ossWT>/.aura-stack/<slug>.env` (the worktree, where start-isolated wrote it).
3. **Stale handover diagnosis** — the prior handover blamed only the downtime cast bug + proposed
   `.toString()`; that would NOT have worked (`.toString()` on Timestamp isn't ISO → `Instant.parse` throws;
   and the `computed_readonly` strip is a second, separate cause). Re-verify diagnoses against real code.

## Lessons Learned

- **Network-request取证 beats guessing** for "renders empty" UI bugs: the `/api/meta/chart-data` request
  body (`parameters` missing → empty `chartId` → record context empty) walked straight to the root cause.
- **`computed_readonly` without a `computeExpression`** = a field that's neither writable nor materialized
  → permanently null. Same class of bug bit both downtime (`pe_dt_duration_hours`) and would bite any
  handler-populated "read-only" field. For handler-written, user-read-only fields: regular field + binding
  `editable:false` + absent from command inputFields.
- **§2.2 golden surfaces real gaps even when gates are green** — engine/handlers/validator all passed; only
  the real-browser golden exposed the chart-render + Cp/Cpk gaps.

## Current State

### Git (both mains moved forward via concurrent merges; my PRs are in history)
- OSS `auraboot` origin/main = `8b67b564d` (my #428 `0bc2fe189` in history). Canonical checkout has only an
  uncommitted edit to the prior handover doc (informational, 留盘 — do NOT commit to main, §20).
- `auraboot-plugins` origin/main = `c5b1eb2` (my #12/#13/#17 in history).

### Running services
The `smartmfg-dtfix` and `spc-golden` stacks I used are **torn down + pruned**. Other tasks' stacks
(bom-mvp / crm-gap / iot-* / etc., ~16 containers) are untouched — do NOT touch them. Disk ~27 GB free.

### Artifacts (untracked, this session)
- `auraboot/test-results/oee-downtime-fix-golden-PASS.png`
- `auraboot/test-results/spc-golden-chart-{AWAITING-DATA(before),RENDERS-PASS,viewport}.png`

## Next Steps (prioritized)

1. **gap #4** — bring up a fresh golden stack (recipe above), enumerate the empty `pe_*_detail.json`
   stubs into a coverage matrix, author real field layouts (parallel subagents), gate each with the
   platform validator, golden a representative sample in a real browser.
2. **gap #6** — full multi-domain action-point golden (depends on #4 detail pages being filled first).
3. **M2–M5** — untouched.
4. **Minor follow-up (cosmetic)** — SmartLineChart plots all returned namedQuery metrics (incl.
   `seq`/`is_violation`) as series rather than honoring `yField`; constrain it for the SPC chart.

## Context for Next Session
- Concurrency check before starting: `git -C /Users/ghj/work/auraboot/plugins log --oneline origin/main`
  (other agents merge to plugins/OSS main frequently) + `git worktree list` + `docker ps`.
- §21 DslRegistry whitelist + §2.2 page golden + `docs/agent-rules/page-golden-verification.md` are the
  governing rules for authoring detail pages.
- Isolated `m2` (if reused): `/Users/ghj/work/m2-smartmfg/repository` (per §11 if ≥2 worktrees on m2).
- Full prior detail: `HANDOVER-2026-06-04-smartmfg-oee-dashboard-golden.md`.

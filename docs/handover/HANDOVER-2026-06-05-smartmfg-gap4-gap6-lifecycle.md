# Session Handover - 2026-06-05 — smart-mfg gap#4 (detail pages) + gap#6 lifecycle slice

> Continues the smart-mfg PCBA golden line. Prior session: `HANDOVER-2026-06-05-smartmfg-gap4-detail-pages.md`
> (same dir) — its **bringup recipe is still the canonical way to stand up the golden stack**, reused verbatim here.
> memory line: `智能制造/PCBA` in MEMORY.md.

## Session Summary

Shipped **two production-ready slices, both merged**: (1) gap#4 — authored 31 real detail pages for empty
`pe_` auto-stubs; (2) gap#6 lifecycle slice — real-stack production value-chain golden that surfaced and
fixed 2 real bugs. Both verified on a real hybrid-jar stack.

## Tasks Completed (all MERGED to auraboot-plugins main)

- **gap#4 — 31 `pe_` detail pages** — `plugins #23` (`f1cca21`). The platform auto-generates an empty
  `[{form-section},{tabs}]` detail stub per model on publish (`MetaModelServiceImpl.autoCreateDefaultPages`);
  31 models across `pcba-{manufacturing,industry,procurement,compliance,warehouse}` had only list+form pages →
  empty-shell detail (§2.2 blocker). Authored each via a **deterministic form→detail transform** (field codes
  copied verbatim from the existing form/list pages → 0 invented codes; read-only, drop `required`; sub-tables
  `readOnly:true` with commands stripped; edit→form toolbar). `pe_warehouse_wave` keyed `pe_wave_detail`
  (pageKey must be `{modelCode}_detail`, model ≠ page base name). Verified: validator `success` for all 31
  (DB: `published`, real `fields`, 0 stubs) + 3-page real-browser golden.
- **gap#6 lifecycle slice — 2 bug fixes** — `plugins #26` (`681f77c`). See "Pitfalls" for the bugs. Golden'd
  `production_plan` (draft→confirm→start→complete) + `work_order_op` (pause/resume/complete) on a real stack,
  asserting DB status transitions, negative guards, and handler business rules.

## Tasks In Progress / Next (gap#6 — only a thin slice done)

**gap#6 = full multi-domain action-point golden — ~100+ action commands, MULTI-SESSION.** Only the
production_plan + work_order_op lifecycle was golden'd this session. Remaining: the other ~37 non-CRUD action
commands in pcba-manufacturing (e.g. equipment downtime/breakdown, resource avail/maintenance, MRP run/exception,
planned-order convert, schedule apply/publish, NPI, etc.) + the other 4 pcba plugins' domains, then M2–M5.
**Repeat the same method per domain:** stand up the stack → seed prerequisites → drive each action point via the
real command pipeline → assert DB state + guards → fix any seam bugs.

## Key Decisions

| Decision | Chosen | Rationale |
|---|---|---|
| gap#4 mechanism | deterministic transform **script**, not parallel LLM agents | transform is purely mechanical (mirror form fields); a script guarantees field-code fidelity (no hallucination — the §15/§2.2 failure mode) |
| gap#6 driving | command-pipeline API (`POST /execute/{cmd}`) + DB asserts, not DOM clicks | the chrome-devtools page closed mid-drive twice; `/execute/{cmd}` is the *exact* endpoint the toolbar button POSTs — faithful to the command pipeline + state machine, NOT a §2.2 "PUT-API 兜底" bypass |
| complete_operation fix | accept `in_progress` OR `completed` (mirror StartOperationHandler) | the framework applies the declared state transition before the handler; the command's `fromStates` already enforces the real precondition |

## Files Changed (merged)

### gap#4 (plugins #23)
- 31 new `pcba-*/config/pages/{modelCode}_detail.json`

### gap#6 (plugins #26)
- `pcba-manufacturing/backend/.../handler/CompleteMesOperationHandler.java` — accept post-transition `completed`
- `pcba-manufacturing/backend/.../CompleteOperationHandlerTest.java` — regression test + fixed adjacent
  case-mismatch assertion (`contains("in_progress")`→`"IN_PROGRESS"`)
- `pcba-manufacturing/config/pages/pe_production_plan_detail.json` — toolbar labels execute/start/complete →
  取消/开始/完成, cancel `action.type` state_transition→command

## Pitfalls & Workarounds

1. **🔴 `pe:complete_operation` could never complete (handler vs framework state-transition seam)** — the
   command declares a framework transition (`fromStates:[in_progress]→toState:completed`) AND a handler. The
   platform applies the transition **before** the handler runs, so the handler re-read `pe_woo_status` as
   `completed` and its redundant `!"in_progress".equals(status)` guard threw → whole command rolled back. A
   unit test mocked `getById`→in_progress so it passed (假绿). **Only the real-stack golden caught it.** Fix:
   accept the post-transition target state too (StartOperationHandler already documents this pattern).
   **General rule (now in memory): when a command declares a framework state transition AND a handler, the
   handler must NOT re-validate the from-state — the platform has already moved it to the target.**
2. **§2.2 raw-label leak in pe_production_plan_detail toolbar** — cancel rendered "execute"; start/complete had
   zh-CN "start"/"complete". Static gates (validator success, page audit) pass these; only the real browser
   golden catches them.
3. **chrome-devtools MCP page closes mid-session** — pivoted lifecycle driving to command-API + DB asserts.
4. **Detail dict/ref resolution is a cold-cache first-paint artifact, NOT a bug** — `pe_eq_type`/`pe_eq_status`
   briefly showed raw `smt`/`idle` on first render, then resolved to 贴片机/空闲 after a warm reload. The detail
   form-section renderer auto-resolves enum dict + reference labels from the field def; **no page-level
   `dictCode` needed** (tested by adding then removing it — same result). Don't "fix" this.
5. **Pre-existing unrelated test failure** — `ConvertPlannedOrderHandlerTest.testConvertUnknownType` fails on
   origin/main (untouched, out of scope). Confirmed via `git show origin/main`.

## Current State

### Git
- `auraboot-plugins` origin/main = `f664af5` (my #23 `f1cca21` + #26 `681f77c` in history; #27 crm landed on top).
- Canonical plugins checkout (`/Users/ghj/work/auraboot/plugins`) on `main` (concurrent sessions advance it;
  it lags origin — that's fine). My worktrees removed, branches deleted.

### Running services
**None for this task** — the `smartmfg-gap4` golden stack is torn down + pruned. Other tasks' stacks untouched.
Disk ~23 GB free.

### Golden stack bring-up (proven recipe — for the next gap#6 slice)
Same as the prior gap#4 handover. In short:
- Isolated m2 (has platform SNAPSHOTs): `/Users/ghj/work/m2-smartmfg/repository` — build jars with
  `-Dmaven.repo.local=$M2`. **Backend code is unchanged for config-only work**, so prebuilt jars in
  `/Users/ghj/work/auraboot/plugins/build/plugin-jars/` can be reused (stage them to a stable dir to avoid a
  concurrent `cleanPluginJars`). `pcba-industry` is config-only (no jar). For a **backend change**, rebuild that
  plugin's jar (`gradle :<plugin>:backend:jar -x test -Dmaven.repo.local=$M2`), copy into the jars dir, and
  `docker restart auraboot-<slug>-backend` (PF4J reloads on startup).
- Stack: from the OSS checkout, `ENTERPRISE_PLUGINS_DIR=<plugins worktree off origin/main>`
  `ENTERPRISE_PLUGIN_JARS_DIR=<staged jars>` `scripts/dev/start-isolated.sh --slug=smartmfg-gap4 --wait --skip-pull`.
  Ports: backend 6525 / pg 5514 / vite 5255 (read `BE_PORT` from `<ossWT>/.aura-stack/<slug>.env`).
- Bootstrap: `aura_bootstrap_setup_if_needed http://localhost:6525 "AuraBoot Dev" admin@auraboot.com Test2026x "Admin User" single "[slug]"`.
- Import: `scripts/import-plugins.sh --slug=smartmfg-gap4 --profile=pcba-agent --edition=enterprise` (do NOT
  pass host ENTERPRISE_PLUGINS_DIR to the import — Pitfall 1 in the prior handover).
- Seed: `BACKEND_URL=http://localhost:6525 bash auraboot/.aura-stack/oee_seed_backup.sh` (products/BOM/equipment/
  resource/work-order-op). DB: user `auraboot`, db `aura_boot`, pw `auraboot_dev`. Tables prefixed `mt_`.

### Command-pipeline driving cheatsheet
- Login: `POST /api/auth/login {"email":..,"password":..}` → JWT at `data.jwt`.
- Create: `POST /api/meta/commands/execute/<cmd>` body `{"payload":{...}}`.
- Lifecycle/update: body `{"targetRecordId":"<pid>","payload":{...}}`.
- Detail route in UI: `/p/{modelCode}/view/{recordId}`; list `/p/{modelCode}`.

## Next Steps (prioritized)
1. **Next gap#6 slice** — pick a domain (e.g. equipment downtime/breakdown — known `pe_dt_duration_hours`
   computed_readonly fragility; or MRP run→exception→planned-order), stand up the stack, drive its action points
   with DB-state assertions, fix seam bugs. Watch for the same "handler re-checks framework-transitioned status"
   anti-pattern across the ~37 commands.
2. **gap#6 other pcba plugins** (industry/procurement/compliance/warehouse domains).
3. **M2–M5** — untouched.
4. Minor cosmetic (deferred): SmartLineChart plots seq/is_violation as series (yField unconstrained).

## Context for Next Session
- Concurrency check first: `git -C /Users/ghj/work/auraboot/plugins log --oneline origin/main` +
  `git worktree list` + `docker ps` (other agents merge to plugins/OSS main frequently).
- §21 DslRegistry whitelist + §2.2 page golden govern any new page authoring; §2.2 真行动点黄金 governs
  action-point golden (drive real commands, assert DB state, not just rendering).
- Prior detail + full bringup recipe: `HANDOVER-2026-06-05-smartmfg-gap4-detail-pages.md`.

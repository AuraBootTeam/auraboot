---
type: handover
status: closed
created: 2026-06-06
---
<!-- no-precipitation: session handover; all 5 slices merged into auraboot-plugins PRs; no independent reusable lesson -->

# Session Handover - 2026-06-06 — smart-mfg gap#6 golden (5 slices) + plugin-repo health

> Continues the smart-mfg PCBA gap#6 action-point golden line. Entry point for the prior session was
> `HANDOVER-2026-06-05-smartmfg-gap4-gap6-lifecycle.md`; the **bring-up recipe + command-pipeline cheatsheet
> in `HANDOVER-2026-06-05-smartmfg-gap6-downtime-maintenance.md` is still canonical** and is reused verbatim
> for every slice below. memory line: `智能制造/PCBA` in MEMORY.md.

## Session Summary

Shipped **5 production-ready PRs, all merged to `auraboot-plugins` main** — 3 gap#6 action-point golden slices,
1 systemic bug-class cleanup, and 1 test-health alignment. Each golden ran on a real isolated hybrid-jar stack
(drive the action via the real command pipeline → assert DB state + guards) and caught real seam bugs that
`compileJava` + unit tests + the platform validator all passed green.

## Tasks Completed (all MERGED to auraboot-plugins main)

- **PR #30 `fde56cc` — equipment downtime/breakdown + maintenance golden.** Converted `pe:equipment_breakdown`
  + `pe:end_equipment_downtime` from `custom` → `state_transition + handler`; wired 报故障/结束停机 + 开始/完成维保
  action points; authored the empty `pe_equipment_maintenance` pages. 8/8 golden. Bugs: stale prebuilt jar
  (pre-#12 EndDowntime) + immutable-`Map.of`→`db.update` crash. Doc: `pcba-manufacturing/EQ-DOWNTIME-MAINTENANCE-GOLDEN.md`.
- **PR #32 `3eccbd1` — immutable-map cross-plugin cleanup.** Swept the `Map.of(...)`/`List.of(...)` → db-write
  bug class across **41 sites in 13 plugins** (paren-aware transform; strictly-safe). 1195 unit tests, 0 new failures.
- **PR #33 `26c9497` — fix 15 pre-existing test failures → full repo green (17 plugins / 1376 tests / 0).**
  Test rot from handler refactors (uncaught, CI billing off): 13 case-mismatch (`*_throws` asserted lowercase
  status words; handlers emit uppercase), asset-dispose (relies on framework state_transition for status),
  finance zero-net (handler skips, test wrongly expected throw). All test-only.
- **PR #34 `45923eb` — MRP run + planned-order convert golden.** Wired the two unreachable core actions
  `execute_mrp` + `convert_planned_order`; full confirm/convert/cancel on the detail page; fixed `"label":"execute"`
  leak. 6/6 golden. Bug: **cross-plugin convert seam** — `ConvertPlannedOrderHandler` created downstream records
  missing required fields → convert always rolled back. PRODUCTION needed `pe_pp_name` + `pe_pp_bom_id` (resolve
  the material's BOM via `db.query("pe_bom")`); PURCHASE needed `pr_po_supplier` → made it optional (draft-PO
  semantics). Doc: `pcba-manufacturing/MRP-PLANNED-ORDER-GOLDEN.md`.
- **PR #35 `917b7d6` — workstation assignment (assign/unassign) golden.** Wired the unreachable `assign_workstation`
  (form had **no buttons at all**; list had no create entry + `"label":"execute"` leak). 5/5 golden incl. the
  overlap-conflict seam. Bug: **date fields `(String)`-cast** but arrive as `LocalDate`/`java.sql.Date` →
  ClassCastException → assign always rolled back. Fix: defensive `toLocalDate()`. Doc:
  `pcba-manufacturing/WORKSTATION-ASSIGNMENT-GOLDEN.md`.

## Key Decisions

| Decision | Chosen | Rationale |
|---|---|---|
| breakdown/end-downtime command shape | `state_transition + handler` (not "handler sets status") | framework applies the transition atomically before the handler → free from-state guards; handlers stay side-effect-only, so the gap#6 "handler re-validates from-state" bug class can't reappear |
| golden driving | real command pipeline `POST /api/meta/commands/execute/{cmd}` + DB asserts | the exact endpoint the toolbar button POSTs — faithful, not a §2.2 PUT-API bypass; robust when chrome-devtools is unavailable |
| immutable-map cleanup verification | reviewed diff + strictly-safe property + #30 golden proof + zero-regression stash baseline | a 2nd stack golden-sample would be gilding for a strictly-safe mechanical fix |
| 15 test failures | align stale tests to current handlers (test-only) | the handlers are the shipped/correct behavior; changing them risks production regressions |
| PURCHASE convert supplier | make `pr_po_supplier` optional (draft) | an auto-generated draft PO legitimately has no supplier yet; buyer assigns before approve |

## Pitfalls & Lessons (generalizable — these recur across gap#6 domains)

1. **Assembled-stack golden is the only thing that catches seam bugs.** Every bug this session passed
   `compileJava` + unit tests + the platform validator. The unit tests mocked `db.create`/`db.update` (so they
   never validated required fields / never mutated the map / used String dates), so they were **假绿**. Drive the
   real command pipeline + assert real DB state.
2. **Date fields arrive TYPED, never assume String.** `LocalDate` (framework payload processing) / `java.sql.Date`
   / `java.sql.Timestamp` (dynamic-data reads). A raw `(String)` cast throws ClassCastException at runtime. Use a
   defensive coercion (`toLocalDate` / `toInstant`). (EndDowntime/#12, AssignWorkstation/#35.)
3. **Immutable `Map.of(...)`/`List.of(...)` must never be passed to `db.update`/`db.create`** — the dynamic-data
   layer mutates the map (injects audit fields) → `UnsupportedOperationException`. Use `new HashMap<>(Map.of(...))`.
4. **Cross-plugin create seam:** a handler creating a downstream model's record must populate ALL of that model's
   required fields (resolve references like BOM via a query); the validator only checks the page DSL, not the
   handler's create payload.
5. **`state_transition + handler`: the handler must NOT re-validate the from-state** — the framework already moved
   it to the target before the handler runs (else permanent rollback).
6. **Rebuild plugin jars from source for any golden touching a changed handler** — the prebuilt
   `plugins/build/plugin-jars/` are not guaranteed current (verify with `javap`). And the isolated build m2
   (`/Users/ghj/work/m2-smartmfg`) can be stale vs the current `platform-plugin-api` — republish it with
   `gradle :platform-plugin-api:publishToMavenLocal -Dmaven.repo.local=/Users/ghj/work/m2-smartmfg/repository`
   from the OSS checkout if you hit a `chainsAfterPrimary`-style compile error.

## Current State

### Git
- `auraboot-plugins` origin/main = `05f0082`. My 5 PRs (#30/#32/#33/#34/#35) are in history.
  ⚠️ **A concurrent session merged #36 (`05f0082`) — page `schemaVersion` 2→4 + DSL V4 Phase A migration**, which
  bumped my schemaVersion-2 pages. The next slice should author pages at **schemaVersion 4** and re-check that the
  V4 migration didn't disturb the action-point wiring I added (spot-check the workstation/MRP/equipment pages).
- Canonical checkout `/Users/ghj/work/auraboot/plugins` on `main` (lags origin/main — concurrent sessions; fine).
  Untracked `bom-standardization/docs/...` are a concurrent session's, **not mine** — don't touch.
- My worktrees all removed, branches deleted (MERGED_AND_DELETED).

### Running services / disk
- **No smart-mfg / mrp / wsa stacks running** — all torn down + pruned.
- **⚠️ Disk ~9 GB free** (was 33 GB at session start; each isolated stack build consumes ~2-3 GB and the
  per-slug backend images + build cache accumulate). **Before the next stack-based slice, reclaim disk** —
  `docker image prune -f` + `docker builder prune -f` recovers ~2 GB of MY dangling images/cache without touching
  other sessions' running stacks (crm-gap / bom-mvp / iot-ui / auraqr-it / crawler-ui are live — do NOT prune those).

## Next Steps (prioritized)

1. **Reclaim docker disk first** (see above) — 9 GB is too tight to safely build a fresh stack.
2. **Next gap#6 domain** (~34 non-CRUD action commands remain). Strong candidates (handler-backed + likely
   gate-gaps): `generate_calendar` (custom+handler, unwired — resource calendar batch generation),
   `calculate_atp` (update+handler), the outsource chain (Send/Receive/Complete handlers), production-version
   activate/deactivate. Same method: rebuild jar → stand up stack → seed → drive each action via the command
   pipeline → assert DB + guards → wire any missing UI entry → fix seam bugs.
3. **Documented follow-ups from this session** (small, can batch):
   - `pr:approve_purchase_order` should enforce supplier now that `pr_po_supplier` is optional at create
     (currently a pure state_transition, no guard) — procurement domain.
   - **9 more `"label":"execute"` + 2 `"label":"view"` raw-label leaks** remain across other pcba-manufacturing
     pages (`grep -rn '"label": "execute"' pcba-manufacturing/config/pages`). A quick sweep.

## Context for Next Session

- **Concurrency check first** (gap#6 domains all live in `pcba-manufacturing`, frequently touched by concurrent
  sessions): `git -C /Users/ghj/work/auraboot/plugins log --oneline origin/main` + `git worktree list` +
  `docker ps`. origin/main moves fast.
- **Bring-up recipe + command-pipeline cheatsheet**: `HANDOVER-2026-06-05-smartmfg-gap6-downtime-maintenance.md`
  (same dir) — jar-rebuild-from-source step is mandatory; isolated m2 `/Users/ghj/work/m2-smartmfg/repository`;
  bootstrap helper `source auraboot/scripts/lib/reset-init-common.sh; aura_bootstrap_setup_if_needed`;
  `scripts/import-plugins.sh --slug=<slug> --profile=pcba-agent --edition=enterprise`; seed
  `BACKEND_URL=... bash auraboot/.aura-stack/oee_seed_backup.sh`; DB user/db/pw `auraboot`/`aura_boot`/`auraboot_dev`,
  tables prefixed `mt_`.
- **Per-slice golden docs** (in the plugins repo, with full coverage matrices + bug write-ups):
  `pcba-manufacturing/{EQ-DOWNTIME-MAINTENANCE,MRP-PLANNED-ORDER,WORKSTATION-ASSIGNMENT}-GOLDEN.md`.
- **schemaVersion is now 4** (DSL V4, PR #36) — author new pages at v4 and verify the V4 migration preserved this
  session's wiring.

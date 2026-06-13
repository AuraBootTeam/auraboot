---
type: handover
status: closed
created: 2026-06-05
---
<!-- no-precipitation: session handover; all slices merged into PRs; bring-up recipe is referenced by follow-on sessions -->

# Session Handover — 2026-06-05 — smart-mfg gap#6 slice: equipment downtime/breakdown + maintenance

> Continues the smart-mfg PCBA gap#6 action-point golden line.
> Prior: `HANDOVER-2026-06-05-smartmfg-gap4-gap6-lifecycle.md` (its bring-up recipe is still canonical).
> memory line: `智能制造/PCBA` in MEMORY.md.

## Shipped (MERGED to auraboot-plugins main)

**PR #30 (squash `fde56cc`)** — equipment downtime/breakdown + maintenance action-point golden. The handlers
existed + were unit-tested but the four lifecycle commands were **referenced by no page and wired as no
sideEffect** (§2.2 gate-gap). This wires + golden-verifies them. Worktree/branch收口 = MERGED_AND_DELETED.

- **Command model**: `pe:equipment_breakdown` + `pe:end_equipment_downtime` converted `custom` →
  `state_transition + handler` (mirrors `pe:complete_eq_maintenance`). Framework applies the `pe_eq_status`
  transition (running/idle→breakdown, breakdown→idle) atomically before the handler → free from-state guards;
  handlers stay side-effect-only. **Zero handler logic change** for breakdown (its `status=="breakdown"` check
  passes post-transition).
- **UI wiring**: `pe_equipment_list` += 报故障 / 结束停机 row actions (visibleWhen-gated); authored
  `pe_equipment_maintenance` list/form/detail (was empty platform auto-stub) + 开始维保 / 完成维保 actions.
  Completes the dangling `pcba-industry > 设备管理 > 维保管理` menu link.
- **Golden 8/8** on a real hybrid-jar stack (`POST /api/meta/commands/execute/{cmd}` + DB asserts): breakdown
  happy+guard, end-downtime happy (`pe_dt_duration_hours` persists = 0.06) + guard, start-maint,
  complete-maint ×3 (preventive +90 / calibration +180 / corrective none). UI config-verified vs published
  `ab_page_schema`. Detail: `pcba-manufacturing/EQ-DOWNTIME-MAINTENANCE-GOLDEN.md` (in the plugins repo).

## Two real bugs the golden caught (compileJava + unit tests + platform validator all green)

1. **🔴 Immutable map → mutating db write (source, systemic).** `CompleteMaintenanceAutoScheduleHandler` passed
   `Map.of(...)` to `db.update`; the dynamic-data layer **mutates the supplied map** (injects audit fields) →
   `UnsupportedOperationException`, whole command rolled back. Unit test mocked `db.update` (no mutation) →
   假绿. Fixed `Map.of(...)` → `new HashMap<>(Map.of(...))` at **all 8 sites** across 6 handlers
   (CompleteMaintenanceAutoSchedule / CompleteMesOperation / StartProduction / CompleteProduction /
   ImplementEco / ReportException). Added regression test `testAutoSchedule_dataLayerMutatesUpdateMap` (mock
   mutates the map → fails on immutable, passes on mutable). **General rule: never pass an immutable
   `Map.of(...)`/`List.of(...)` to `db.update`/`db.create` — the dynamic-data layer mutates it.**
2. **🔴 Stale prebuilt plugin jar (NOT a source bug — a DEPLOY trap).** `plugins/build/plugin-jars/
   pcba-manufacturing-…jar` predated fix #12 (`41d2d4b`): its `EndDowntimeHandler` still had the raw
   `(String)` cast on the timestamptz start_time → `end_equipment_downtime` threw
   `java.sql.Timestamp cannot be cast to String`. Source was already fixed; **the reused jar was stale**
   (its mtime was newer than #12 but its bytecode was older — don't trust mtime, check bytecode/`javap`).
   Fix: rebuild the jar from source (`gradle :<plugin>:backend:jar -x test -Dmaven.repo.local=$M2`), restage,
   then `docker restart <slug>-backend`.

## 🚨 Process lessons (carry forward — these generalize beyond this slice)

- **The prior handover's "reuse prebuilt jars for config-only work" is UNSAFE.** `plugins/build/plugin-jars/`
  are not guaranteed current vs origin/main source. **For ANY golden that exercises a changed handler, rebuild
  that plugin's jar from source first** (and verify with `javap -p` that the expected method/fix is present).
  Corollary: the OEE-availability fix #12 is effectively **undeployed** in any stack reusing those stale jars —
  prior OEE goldens that claimed real availability either built fresh jars or used seed data with pre-set
  durations; worth re-checking if availability accuracy matters.
- **Unit tests that mock `db.update`/`db.create` cannot catch the immutable-map or any data-layer-contract
  bug** — only the assembled real-stack golden does (the recurring gap#6 lesson). Drive the real command
  pipeline + assert DB state.

## Follow-ups (open)

- **✅ DONE — Cross-plugin immutable-map cleanup (PR #32, squash `3eccbd1`).** Swept the bug class across the
  rest of the repo: 41 write-call sites in 13 plugins (asset-management, bom-standardization, crm,
  crm-incentive, finance, inventory, pcba-compliance, pcba-finance, pcba-procurement, pcba-sales,
  pcba-solution, pcba-warehouse, quality), each immutable data-arg `Map.of`/`List.of` → mutable wrapper via a
  paren-aware transform (full diff reviewed). Verified: all 13 compile; 1195 unit tests with **zero new
  failures** (stash baseline identical = 14 pre-existing failures from origin/main-vs-current-`platform-plugin-api`
  drift + pre-existing `*_throws`, NOT mine); strictly-safe; mechanism golden-proven in #30. **Note:** had to
  republish the current `platform-plugin-api` into the isolated m2 (`gradle :platform-plugin-api:publishToMavenLocal
  -Dmaven.repo.local=/Users/ghj/work/m2-smartmfg/repository` from the OSS checkout) — the m2 was stale (predated
  the `chainsAfterPrimary` SPI), same staleness class as the jar.
- **✅ DONE — Pre-existing test failures fixed (PR #33, squash `26c9497`).** The plugins repo had 15 unit tests
  failing on origin/main against the current platform-api (test rot from handler refactors). Fixed all
  (test-only): 13 case-mismatch (`*_throws` tests asserted lowercase status words; handlers emit uppercase →
  made only the failing assertions `toLowerCase().contains(...)`), asset-dispose (handler relies on framework
  state_transition for `asset_status`; unit test was stale), finance zero-net (handler intentionally skips
  zero-net; test wrongly expected a throw → aligned to skip). **Full repo now green: 17 plugins, 1376 tests,
  0 failures.**
- **(original note, now resolved) Cross-plugin grep for the immutable-map bug class.** A read-only
  sweep of auraboot-plugins found the `db.update/create(..., Map.of(...))` write-arg pattern in `src/main`
  handlers of **~12 plugins**: asset-management, bom-standardization, crm-incentive, finance, inventory,
  pcba-compliance, pcba-finance, pcba-procurement, pcba-sales, pcba-solution, pcba-warehouse, quality (e.g.
  `pcba-sales/.../ConvertRfqToQuotationHandler.java:96`, `crm-incentive/.../PayPayoutHandler.java:127`,
  `inventory/.../ReleaseWaveHandler.java`). **Only `src/main` matters** — the many `src/test`
  `thenReturn(Map.of())` hits are mock *return* values and are fine. Each is a latent
  `UnsupportedOperationException` whenever that write path + a map-mutating model meet. The fix
  (`Map.of` → `new HashMap<>(Map.of)`) is strictly safe, but each handler should be golden-verified per domain
  (NOT a blind mass-edit). Sweep command per plugin:
  `grep -rnE 'db\.(update|create|insert|save)\([^;]*Map\.of' <plugin>/backend/src/main/java` (+ multiline form
  where `Map.of` is on the following line). Not done this slice (scope = downtime/maintenance).
- **Platform hardening (OSS).** `DynamicDataServiceImpl.update` mutates the caller's input map — it should
  defensively copy so handlers can't trip on an immutable arg. Would make the whole bug class impossible.
- **`pe_eq_status` lifecycle hardening.** Status stays form-editable this slice (making it read-only would
  strand the running/maintenance transitions, which no command wires yet). Make it lifecycle-controlled once
  all equipment-status transitions are wired.
- **Browser pixel-click** for these action points was env-blocked (a concurrent session held the
  chrome-devtools profile). Config + commands are verified; a future run with a free browser can drive the
  literal buttons.
- **OEE seed.** The 6 seed downtime rows have NULL `pe_dt_duration_hours` (seed bypasses the handler) → OEE
  availability 1.0 for them. Backfill or re-seed via the handler if a downtime-driven OEE golden is needed.

## Next gap#6 slices (unchanged from prior handover)

~37 more non-CRUD action commands in pcba-manufacturing (MRP run→exception→planned-order, scheduling
apply/publish, NPI, etc.) + the other pcba plugins (industry/procurement/compliance/warehouse), then M2–M5.
**Same method per domain:** rebuild jar from source → stand up isolated stack → seed prerequisites → drive each
action via the real command pipeline → assert DB state + guards → wire any missing UI action points → fix seam
bugs.

## Bring-up recipe (this slice, proven 2026-06-05)

Identical to the gap#4 handover, with the **jar-rebuild step now mandatory**:
- Isolated m2: `/Users/ghj/work/m2-smartmfg/repository` (has platform SNAPSHOTs).
- **Rebuild the changed plugin jar** from a worktree off origin/main:
  `gradle :pcba-manufacturing:backend:jar -x test -Dmaven.repo.local=$M2 --no-daemon`, stage to a stable dir,
  `docker restart auraboot-<slug>-backend` (PF4J reloads on startup). Verify with `javap -p <Handler>.class`.
- Stack: from the OSS checkout, `ENTERPRISE_PLUGINS_DIR=<plugins worktree>
  ENTERPRISE_PLUGIN_JARS_DIR=<staged jars> scripts/dev/start-isolated.sh --slug=<slug> --wait --skip-pull`.
- Bootstrap: `source scripts/lib/reset-init-common.sh; aura_bootstrap_setup_if_needed http://localhost:<BE>
  "AuraBoot Dev" admin@auraboot.com Test2026x "Admin User" single "[slug]"`.
- Import: `scripts/import-plugins.sh --slug=<slug> --profile=pcba-agent --edition=enterprise` (do NOT pass host
  ENTERPRISE_PLUGINS_DIR to the import).
- Seed: `BACKEND_URL=http://localhost:<BE> bash .aura-stack/oee_seed_backup.sh`.
- DB: user `auraboot`, db `aura_boot`, pw `auraboot_dev`; dynamic tables prefixed `mt_`. Login → JWT at
  `data.jwt`. State-transition driving: `POST /execute/<cmd>` body `{"targetRecordId":"<pid>"}`.

## Concurrency note
origin/main advanced during this session (CRM #28/#29 from a concurrent session; my #30 landed on top =
`fde56cc`). Always recalibrate with live `git -C plugins log origin/main` + `git worktree list` + `docker ps`
before the next slice.

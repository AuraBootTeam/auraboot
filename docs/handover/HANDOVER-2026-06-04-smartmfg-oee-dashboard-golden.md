---
type: handover
status: active
created: 2026-06-04
---

# Session Handover - 2026-06-04

> Source-of-truth detail + reusable bringup recipe: `~/.claude/plans/2026-06-04-smartmfg-golden-build.md` (see UPDATE + UPDATE 2 sections — the body below them is STALE).

## ⬆️ UPDATE 2026-06-04 (later session) — Downtime-duration bug RESOLVED + merged; 大屏 availability now real

The "Downtime-duration bug" listed below as Next-Step #1 is **DONE + MERGED**, and the OEE 大屏 real-browser golden re-ran with **real availability (89%, not 100%)**.

**Root cause was TWO bugs (the diagnosis below only caught the first):**
1. `EndDowntimeHandler` `(String)` cast on `pe_dt_start_time` — that column is `datetime`→`timestamptz`, so the dynamic-data layer returns `java.sql.Timestamp`, never a String. The cast threw `ClassCastException` *before* the try/catch, failing the whole command. (The proposed `.toString()` fix would NOT have worked — `Timestamp`/`LocalDateTime`.toString() isn't ISO-instant, so `Instant.parse` then throws and the catch swallows it → duration still null.)
2. **(missed below)** `pe_dt_duration_hours` was `virtualType: computed_readonly` with **no `computeExpression`** → `DynamicDataService.filterVirtualFields` stripped it from **every** create AND update, and `VirtualFieldEngine.materialize` only runs for `materialized` fields. So the handler's duration write was silently discarded regardless of the cast → availability was *structurally* always 100%.

**Fix (both MERGED to auraboot-plugins `origin/main`):**
- **PR #12 `41d2d4b`** `fix(pcba-mfg)`: `EndDowntimeHandler` → deterministic `toInstant()` coercion (Timestamp/Instant/OffsetDateTime/ZonedDateTime/LocalDateTime/Date/ISO-String); `pe_dt_duration_hours.json` drop `computed_readonly` (handler write now lands; UI stays read-only via binding `editable:false` + absent from command inputFields); +2 regression tests using the real Timestamp/LocalDateTime types. Unit 14/14.
- **PR #13 `10b6c1b`** `fix(qc-spc)`: SPC #11 shipped `qc_spc_chart_points` named-query with invalid `status:"active"` → `No enum constant NamedQueryStatus.ACTIVE` → broke the **whole** quality→procurement→pcba import chain (this was the never-run gap #3 latent bug). Fixed `active`→`published`. **Both PRs are needed together** for a working pcba-agent import.

**Golden evidence (isolated stack `smartmfg-dtfix`, since torn down):** 25/25 plugins imported + reference-integrity clean; DB 3/3 downtime rows `pe_dt_duration_hours` = 2.00/5.00/4.00 (exact, no TZ skew); `/fleet/summary` availability 88.6%; OEE 大屏 browser 可用率 **89%**, per-eq 93.8/84.4/87.5%, 故障时长 2/5/4h. Screenshot `auraboot/test-results/oee-downtime-fix-golden-PASS.png`.

**Bringup gotcha (cost ~10 min):** `import-plugins.sh --slug=... --edition=enterprise` resolves plugin configs at the **container** path `/app/plugins-enterprise` via `docker exec`. Do **NOT** pass `ENTERPRISE_PLUGINS_DIR=<host worktree>` to `import-plugins.sh` (only to `start-isolated.sh` for the mount) — doing so makes it `docker exec [ -f <host-path> ]` which never exists in the container → every business/pcba plugin reports "missing". The recipe in the plan was already correct; the deviation was the error.

**Remaining smart-mfg golden (still TODO):** gap #3 SPC **live** golden is now **UNBLOCKED** (quality imports) — drive `qc_spc_chart` in a browser; gap #4 ~40 empty stub detail pages; gap #6 full multi-domain action-point golden; M2-M5.

---

## Session Summary

Started from "smart-mfg golden build". Verified the **Phase 0 gating infra** (the previously-blocked point), then built + golden-verified the **OEE 大屏 headline** (golden gap #2 + #5) end-to-end with 3 parallel agents, and rebased the SPC feature. **3 PRs merged + closed.** The *broader* smart-mfg golden is NOT complete — only the OEE 大屏 slice (§2.4: precise).

## Tasks Completed

- [x] **Phase 0 — golden infra reachable (gating)**. Corrected a very stale plan: the PCBA suite is now **10 plugins** (7 hybrid w/ PF4J backends + config), in **auraboot-plugins** repo. OEE is a **platform** feature (`platform/.../module/oee/`), not a plugin handler. Established the working bringup: build hybrid jars (`cd /Users/ghj/work/auraboot/plugins && gradle :buildAllPluginJars`) → `start-isolated.sh` with `ENTERPRISE_PLUGINS_DIR`(auraboot-plugins config)+`ENTERPRISE_PLUGIN_JARS_DIR`(jars) → bootstrap admin → `import-plugins.sh --profile=pcba-agent --edition=enterprise`. Verified: 17 PF4J plugins loaded, 25/25 imported OK, reference-integrity `valid:true`, 51 pe_ models published.
- [x] **OEE 大屏 headline (gap #2 + #5) — real-browser golden PASS**. Merged.
  - **Backend (OSS PR #411, squash `202d9cd87`)**: `GET /api/manufacturing/oee/fleet` + `/fleet/summary` → `{records:[...]}`; reuse `OeeCalculationEngine`+`OeeDataQueryPort` per equipment (no formula dup); `OeeFleetService` rollup; `OeeDataQueryPort.listEquipment`+adapter. Unit 3/3 green; IT extended.
  - **Dashboard (plugins PR #10, squash `558a3122`)**: `pcba-manufacturing/config/dashboards/pe_oee_dashboard.json` — 6 KPI cards + 13-col equipment table, all `dataSource.type:"api"`.
  - **Seed**: 3 equipment via command API (§4.1, no SQL); reusable `auraboot/.aura-stack/oee_seed_backup.sh`.
  - **Golden** (stack `smartmfg-oee`, since torn down): KPI cards OEE 79% / Avail 100% / Perf 88% / Quality 90% / TEEP 79% / 设备数 3; table SMT 95 / Reflow 74.30 / AOI 68. Screenshot `auraboot/test-results/oee-golden-PASS.png`.
- [x] **SPC rebase (gap #3, plugins PR #11, squash `d5fb15d4`)** — `feat/smartmfg-spc` rebased clean onto plugins main; quality backend 51 tests / 0 fail. (Live golden import = follow-up.)
- [x] All 3 PRs MERGED + worktrees/branches/stack cleaned up → MERGED_AND_DELETED (§20).

## Tasks In Progress / Not Done (smart-mfg golden backlog — all HEAVY, next session)

- [ ] **Downtime-duration bug fix** (real bug surfaced by golden). availability stuck at 100% because `pe_dt_duration_hours` (computed_readonly) is never populated. Two root causes: (1) `pe:create/update_eq_downtime.inputFields` exclude it (correct — it's computed); (2) `pcba-manufacturing/backend/.../handler/EndDowntimeHandler.java:73` does `(String) openRecord.get("pe_dt_start_time")` which throws `ClassCastException` (it's a `Timestamp`). **Fix** = `.toString()` at line 73 + seed downtime as 2-step open→end (so duration computes). pcba-manufacturing backend change → jar rebuild + re-golden.
- [ ] **gap #3 SPC live golden** — SPC merged but not imported/verified in a stack; drive `qc_spc_chart` in a real browser.
- [ ] **gap #4 detail pages** — **40 of 52 pe_ detail pages are empty auto-stubs** (`blocks:[{form-section},{tabs}]` no fields, §2.2 空壳). Author real field layouts. (12 detail real; forms 41/51 real; lists 54/54 real.)
- [ ] **gap #6 full multi-domain action-point golden** + M2-M5.

## Key Decisions

| Decision | Chosen | Rationale | Alternatives |
|---|---|---|---|
| Surface computed OEE on a config dashboard | Thin platform `/fleet[/summary]` endpoints returning `{records:[...]}` + `type:api` widgets | Runs in caller's tenant context (correct multi-tenant); precedented `type:api`; frontend stays config-only (§7) | ApiConnector+NamedQuery (internal-loopback HTTP + token/tenant juggling, no precedent) ✗; custom React block (more code, §7 prefers config) ✗ |
| Golden deploy | Fresh isolated stack from the feature-branch worktree | §2.2 clean-deploy (avoid stale-deploy false pass/fail) | in-place backend-only rebuild (faster but drift risk) |
| Downtime bug | Document as follow-up, do NOT hack `pe_dt_duration_hours` into inputFields | Field is `computed_readonly` by design; hacking violates the model contract (§7/§8). Real fix = handler cast bug. | inputFields hack ✗ |
| Parallelism | main-loop did backend (S1); 3 sonnet agents (dashboard / seed / SPC) in separate worktrees | All independent; only 1 shared-stack writer (seed) → within §agent-collaboration cap 2 | — |

## Files Changed (all MERGED to respective mains)

### OSS auraboot (PR #411 → main `202d9cd87`)
- `platform/.../module/oee/dto/{OeeEquipmentRef,OeeFleetRow,OeeFleetSummary}.java` — new
- `platform/.../module/oee/service/OeeFleetService.java` — new (rollup)
- `platform/.../module/oee/port/OeeDataQueryPort.java` — +`listEquipment`
- `platform/.../module/oee/adapter/DynamicTableOeeAdapter.java` — +`listEquipment` impl (NO deleted_flag filter)
- `platform/.../module/oee/controller/OeeController.java` — +`/fleet` +`/fleet/summary`
- `platform/.../test/.../oee/service/OeeFleetServiceTest.java` — new; `...adapter/DynamicTableOeeAdapterIntegrationTest.java` — +listEquipment coverage

### auraboot-plugins (PR #10 `558a3122`, PR #11 `d5fb15d4`)
- `pcba-manufacturing/config/dashboards/pe_oee_dashboard.json` — new OEE 大屏
- quality plugin SPC (5 commits): Cp/Cpk + USL/LSL fields, `CalculateProcessCapabilityHandler`, `RecordSpcDataHandler`, WE rules 5-8, `qc:calculate_capability`, `qc_spc_chart_*` pages, named-queries, i18n

### Untracked (not committed — session artifacts)
- `auraboot/test-results/oee-golden-{PASS,cards-broken}.png` — golden screenshots
- `auraboot/.aura-stack/oee_seed_backup.sh` — reusable seed script

## Pitfalls & Workarounds

1. **listEquipment returned empty (fleet showed 0) despite seeded equipment**
   - Root cause: my SQL filtered `deleted_flag` but `mt_pe_equipment` has no such column → SQL threw → the defensive adapter `query()` swallowed it → empty. Per-equipment endpoint worked (no such filter) so it hid the bug until the live golden.
   - Solution: drop the filter (matches the other OEE adapter queries). FIXED in #411.
   - Prevention: the unit test used a fake port (didn't exercise SQL); the IT needs an external `oee-adaptertest-pg:5501` (didn't run locally). **Real-stack golden is what caught it** — §2.2.
2. **KPI number cards rendered ALL record fields with raw-code labels** (`availabilityPct` etc.)
   - Root cause: `SmartNumberCard` ignores `valueField`; with no `cards[]` it falls back to `Object.keys(firstRow).slice(0,6).map(f=>({field:f,label:f}))` (raw codes). Agent B used `valueField` (not honored).
   - Solution: give each card a single-element `cards:[{field,label:{zh-CN,en},suffix}]`. FIXED in #10 (`b148ae9`→squashed).
   - Prevention: §16 source-read concluded valueField works but missed the api-branch render path; **only the live browser render exposed it** — §2.2.
3. **Stale chrome-devtools-mcp browser held the shared profile** (9h-old orphan server pid 66632, no watchdog) → blocked my browser. Killed the orphan server + its chrome (verified ppid = the stale server), then launched fresh.
4. **plugins main moved mid-session** (concurrent bom task: `631e74e`→`cab9785`→…). Worktrees isolated us; rebased SPC onto latest; never touched the canonical plugins checkout (has bom WIP).

## Lessons Learned

- **§2.2 real-browser golden is non-negotiable**: API-correct + contract-aligned + §16-source-verified ALL passed, yet the live render exposed 2 distinct bugs (SQL deleted_flag + card raw-code leak). Smoke/API/source review is not a substitute.
- **`SmartNumberCard` (dashboard widget) uses `cards:[{field,label,suffix}]`, NOT `valueField`**; with no cards it dumps the first 6 record fields with raw labels. `smart-number-card` + `smart-table-chart` support `dataSource.type:"api"`; `smart-bar/line/pie-chart` do NOT.
- **Computed OEE on a config dashboard** → thin row-shaped platform endpoint (`{records:[...]}`) + `type:api`, run in request tenant context. The ApiConnector path is wrong for internal computed endpoints (tenant/token).
- Stale plans drift hard — re-establish ground truth with live git/psql before trusting (the plan was wrong on plugin count, types, OEE ownership, and detail-page coverage).

## Current State

### Git
- OSS `auraboot` origin/main = `202d9cd87` (#411). Local canonical checkout clean-ish (untracked crm specs from another task + the 2 session artifacts above).
- `auraboot-plugins` origin/main has #10 (`558a3122`) + #11 (`d5fb15d4`) merged (local origin ref may be stale until fetch). **Canonical plugins checkout has another task's uncommitted bom WIP — do not pull/touch it.**
- All session worktrees removed; branches deleted; remote PR branches deleted on merge.

### Running services (other tasks — DO NOT touch)
`bom-mvp`, `crm-gap`, `mobile-e2e`, `iot-*`, `crawler-szlcsc-l2` stacks still up. My `smartmfg-oee` stack was torn down (purged).

### Reusable bringup recipe
In `~/.claude/plans/2026-06-04-smartmfg-golden-build.md` (UPDATE section). Jars at `/Users/ghj/work/auraboot/plugins/build/plugin-jars/` (17 jars).

## Next Steps (prioritized)

1. **Downtime bug** — fix `EndDowntimeHandler:73` cast + 2-step downtime seed → availability becomes real. (pcba-manufacturing backend → jar rebuild → re-golden the 大屏.)
2. **gap #3 SPC live golden** — import SPC (already on main) into a stack + real-browser verify `qc_spc_chart`.
3. **gap #4** — author the ~40 empty stub detail pages (§2.2 covers list/form/detail).
4. **gap #6** — full multi-domain action-point golden; then M2-M5.

## Context for Next Session

- Rebuild golden stack from a worktree of OSS main (`202d9cd87`) using the recipe; `--profile=pcba-agent --edition=enterprise` with `ENTERPRISE_PLUGINS_DIR=<auraboot-plugins worktree>`.
- The OEE 大屏 lives at `/dashboards/view/pe_oee_dashboard` (admin@auraboot.com / Test2026x).
- OEE endpoints: `/api/manufacturing/oee/fleet[/summary]?start=<ISO-local>&end=<ISO-local>` (`@RequirePermission(MANUFACTURING_OEE)`), return `{data:{records:[...]}}` with 0-100 pct fields.
- Actions disabled on all repos (billing off) → no CI; merge via local gates (`check-oss-boundary.sh`, `validate-permission-codes.mjs`, etc.).

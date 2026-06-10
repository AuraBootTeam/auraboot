---
type: handover
status: closed
created: 2026-06-05
---
<!-- no-precipitation: session handover; conclusions and code changes merged into PRs; no independent reusable canonical lesson -->

# Session Handover — 2026-06-05 · automation-designer-golden-e2e (Phase 1 ✅ + Phase 2 ✅)

## Session Summary
**Phase 1 (Layer A real-drag golden) and Phase 2 (Layer B behavioral matrix) are both COMPLETE + verified against a clean GA E2E stack.** The Layer-B behavioral golden found **2 real production bugs** (both passed every static/unit gate — only an assembled-runtime behavioral test catches them, §2.2); both are fixed + verified. **Phase 3 (18-node-type back coverage) and Phase 4 (acceptance + PR) are the remaining work.**

Branch `feat/automation-designer-golden-e2e` (pushed, tip `564df53fb`).

## Tasks Completed (verified) this session
- **Phase 1 (Layer A) — ✅ 6/6 green, 3× flake-free.** Fixed 2 committed defects in the spec: (1) tsc error line 341 (`r.request().method()` → `r.method()`); (2) cold-vite flake — `openNewDesigner` now waits 30s for the editor name input before palette/pane (the heavy @xyflow chunk compiles >5s on a cold stack). Reviewed the 4 product changes from the prior subagent (PB-2 toggle→deploy, PB-3 ?logId overlay wiring, PB-4 field-error chrome, FlowDesigner test global) — all legitimate, no make-it-pass hack. Screenshots eyeballed (H1 4-node flow, H3 green-check badge, S1 field-level error). Commits `cf7e29a0a` / `5814d90f2`.
- **Phase 2 (Layer B) — ✅ 10 passed + 1 honest skip, 6× flake-free.** `web-admin/tests/e2e/automation/automation-golden.spec.ts` extended: E6/S4/C3/S3/S5/E1/E2/C2/C1 (+ E3 honest skip). Commits `368339e25` / `dfd62b33f` / `1da77f4b9` / `564df53fb`.
- **2 real product bugs fixed (platform):**
  - **PB-5 BPM re-deploy → 500.** `ProcessDeploymentService.createNewVersion` inserted `bpmnContent` verbatim (null from the automation re-deploy caller) instead of compiling from `designerJson` like `create()`/`update()` → the SECOND deploy of any automation (E6 re-enable, edit+re-deploy) hit `null value in column "bpmn_content" violates not-null constraint`. Fixed: compile from designerJson, fall back to "". Verified by E6.
  - **PB-6 empty-flowConfig create → 500.** `AutomationServiceImpl.validateCreateRequest` treated `{nodes:[],edges:[]}` as visual-designer mode (non-empty Map) → skipped the flat `modelCode` required check → `ab_automation.model_code` NOT NULL crash. Fixed: `isFlowConfigOnly` now checks for actual nodes (mirrors `enable()`) → clean 400. Verified by C3.
  - Both rebuilt into the GA E2E backend; regress neither Layer A (6/6) nor Layer B B1.

## Current verified state (re-run anytime)
- Layer A: `automation-designer-golden.spec.ts` → **6/6** (H1/H2/H3/S1/S2/E5).
- Layer B: `automation-golden.spec.ts` → **10 passed + 1 skip** (B1 + E6/S4/C3/S3/S5/E1/E2/C2/C1; E3 skip).
- Flake: Layer A 3/3, Layer B 6/6 clean.

## Stack + run recipe (IMPORTANT — used by every run above)
- **Disk gate:** keep `/` ≥ 30GB (`df -h /`). Prune with `docker image prune -a -f` + `docker volume prune -a -f` (offset-stack leftovers are safe).
- **Stack:** GA E2E docker stack on canonical ports backend 6444 / vite 5174 / BFF 3501 / pg 5433. Bring up: `scripts/docker-ga-e2e-up.sh` then `scripts/docker-ga-e2e-bootstrap.sh` (seeds admin + test-fixtures → e2et_order + `e2eto:create_e2et_order`).
  - **⚠️ `--rebuild` offsets ports** (to 6445/5175/3502/5434) when a stack is already up. To rebuild a backend change AND stay on canonical ports: `docker-ga-e2e-up.sh --rebuild` (lands on +1) → `docker-ga-e2e-down.sh` → `docker-ga-e2e-up.sh` (reuses the freshly-built image, claims offset 0). The pg volume persists across down/up (no `--purge`), so bootstrap data survives.
- **storageState:** `tests/storage/ga/admin.json` (gitignored, JWT not tracked). Regenerate against a fresh stack: `… npx playwright test --project=auth --no-deps` (setup project's invariant-7 "Business Tenant" assertion fails harmlessly because the bash bootstrap only seeds the System tenant — irrelevant to these specs; `--no-deps` runs auth alone). `__session` cookies are port-agnostic.
- **Run env (every spec):** `PLAYWRIGHT_BASE_URL=http://localhost:5174 BACKEND_URL=http://localhost:6444 BE_PORT=6444 BFF_PORT=3501 PW_SKIP_WEBSERVER=1 PW_STORAGE_DIR=tests/storage/ga npx playwright test <spec> --no-deps`. For specs whose afterAll reads the default storage path, also pass `PW_ADMIN_STORAGE_STATE=tests/storage/ga/admin.json`.
- **Gotchas baked into the spec:** (a) the fire endpoint `…/execute/e2eto:create_e2et_order` carries a colon → from `about:blank` the `e2eto:` segment mis-parses as a URL scheme and hits the SPA fallback HTML → the Phase-2 `beforeEach` lands on `/automations` first (real origin). (b) Layer-B `data.config` node shapes are grounded in the verified Layer A H1 + the node `configSchema` source (condition reads `record['field']`; gateway out-edges carry `sourceHandle` true/false + `data.condition.content`; update-record `fields` is an OBJECT — `UpdateRecordExecutor` casts `config.get("fields")` to Map).

## Remaining work (next session)

### Phase 3 — full 18-node-type back coverage (the large remaining body; several cases need test infra)
Plan tasks 3.1–3.4. Grounded config shapes are in `nodes/triggers.ts` / `actions.ts` / `controls.ts` and the verified Layer-A H1. Cheapest-first:
- **Cheap (extend Layer B as-is):** action-create-record (new-row side effect), action-execute-command (run another e2eto command + assert), trigger-record-update / -field-change / -state-change (fire via a record UPDATE — find/confirm the e2et_order update command path).
- **Infra-needed (ground before writing):** trigger-webhook (needs a webhook POST endpoint + signature, ties #415), trigger-bpm-event (needs a BPM event publish), action-call-api / action-send-webhook (need outbound-HTTP interception), action-llm-call (needs an LLM stub/mock), action-start-process (needs a deployable BPM process to assert an instance started).
- **Honest skips (already decided):** trigger-scheduled (heavy cron-realtime; IT `AutomationSchedulerTest`), control-delay (SmartEngine timer suspended, roadmap #8), control-loop fire-path (E3 — multi-iteration covered by `AutomationProcessRuntimeIntegrationTest`; e2et_order has no collection field).
- **3.4 property-panel render:** a vitest component test asserting each configSchema field type renders (separate from the E2E; pairs with palette-coverage Task 0.6 which is done).

### Phase 4 — acceptance + PR
4.1 coverage matrix doc, 4.2 screenshot review (done for Layer A; redo if Phase 3 adds UI), 4.3 `/e2e-truth` self-audit, 4.4 ≥3× flake (done for P1+P2), **4.5 open the PR**.

## Key files
- Plan (status blocks inline, source of truth): `docs/superpowers/plans/2026-06-05-automation-designer-golden-e2e.md`
- Spec: `docs/superpowers/specs/2026-06-05-automation-designer-golden-e2e-design.md`
- Layer A: `web-admin/tests/e2e/automation/automation-designer-golden.spec.ts` + harness `web-admin/tests/e2e/_helpers/flow-designer-harness.ts`
- Layer B: `web-admin/tests/e2e/automation/automation-golden.spec.ts`
- Backend fixes: `platform/.../bpm/service/ProcessDeploymentService.java` + `platform/.../automation/service/impl/AutomationServiceImpl.java`

## Commits this session (all pushed)
`cf7e29a0a` Phase-1 verified (tsc + cold-vite) · `5814d90f2` plan P1 close · `368339e25` 2 backend fixes + P2 batch1 · `dfd62b33f` P2 batch2 · `1da77f4b9` P2 batch3 · `dd16107bb` plan P2 close · `564df53fb` flake fix.

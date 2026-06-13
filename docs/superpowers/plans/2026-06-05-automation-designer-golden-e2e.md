---
type: run-log
status: closed
created: 2026-06-05
---
<!-- no-precipitation: session run-log for automation designer golden E2E plan execution; all phases completed and merged into PRs #438/#448; no standalone reusable lesson -->

# Automation Designer Golden E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Layered golden E2E for the automation visual designer — a real-browser drag-drop user journey plus a runtime behavioral matrix — with full coverage of all 18 palette node types (front + back) and honest skips for the two suspended capabilities.

**Architecture:** Layer A drives the real @xyflow designer UI (drag nodes, connect edges, configure, save, reload, enable, fire, assert side effect + node-status overlay). Layer B builds flowConfig via API then fires real triggers and asserts backend behavior. A shared E2E harness (drag/connect/config/poll/fire helpers) is built in Phase 0 and reused by the follow-up bpmn slice.

**Tech Stack:** Playwright (real browser), AuraBoot web-admin (React + @xyflow flow-designer-sdk), SmartEngine-backed automation runtime, isolated docker E2E stack, PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-06-05-automation-designer-golden-e2e-design.md`

---

## File Structure

- **New** `web-admin/tests/e2e/_helpers/flow-designer-harness.ts` — reusable @xyflow designer drag/connect/config/poll/fire helpers (also used by bpmn slice). Interface defined in Phase 0; bodies implemented after the drag mechanism is verified.
- **New** `web-admin/tests/e2e/automation/automation-designer-golden.spec.ts` — Layer A: real drag-drop user journey (happy + UI-layer sad/edge).
- **Modify** `web-admin/tests/e2e/automation/automation-golden.spec.ts` — Layer B: runtime behavioral matrix + per-trigger fire + per-action side-effect + per-control behavior (API-setup + real fire/assert).
- **New** `web-admin/app/framework/smart/automation/nodes/__tests__/palette-coverage.test.ts` — front: assert all 18 node types present with correct category/i18n key/configSchema shape.
- **Possibly modify** designer components to add stable `data-testid` (only if Phase-0 audit finds them missing) — exact files determined in Phase 0.
- **New** `docs/superpowers/specs/2026-06-05-automation-designer-golden-coverage-matrix.md` — the executed coverage matrix (filled during Phase 4).

---

## Phase 0 — Infra preflight, mechanism verification, harness interface (GATE)

> This phase is a hard gate (§2.1). Do not write Layer A case code until Phase 0 resolves the drag mechanism, the seed model reachability, and the testid availability. Its outputs parameterize Phases 1–3.
> **Phase-0 findings (Task 0.1 + 0.2 resolved 2026-06-05, read-only):**
> - **Drag mechanism = HTML5 native drag** (NOT @dnd-kit). `flow-designer-sdk/core/FlowCanvas.tsx:148` `onDrop` reads `event.dataTransfer.getData('application/flow-node')`; `:179` `onDragOver` sets `dropEffect='move'`; canvas is `<ReactFlow>` (@xyflow). Palette items set `dataTransfer['application/flow-node'] = nodeType` on dragstart. → `dragNodeToCanvas` dispatches a synthetic `drop` (DataTransfer with key `application/flow-node`) at the canvas position, or try Playwright `dragTo` (HTML5 drag is dragTo-compatible; the §20 dragTo failure is @dnd-kit-specific). `connectEdge` still uses @xyflow handle pointer-drag.
> - **testid status:** present on `AutomationList` (rows/create/toggle/edit/delete) + `AutomationEditor` shell (name/description/export) + `ExecutionLogDialog`/`TemplateGallery`. **MISSING on the SDK canvas/palette/@xyflow nodes/node handles/property-panel fields/designer Save+Enable** (rg on `flow-designer-sdk` returned 0 testids). → **Task 0.2b IS required**: add `palette-node-<type>`, `flow-node-<id>`, `node-handle-source-<id>`/`-target-<id>`, `prop-field-<key>`, `designer-save`, `designer-enable` to `FlowPalette.tsx`, the @xyflow node wrapper, `FlowPropertyPanel.tsx`, and the designer toolbar.

### Task 0.1: Verify the @xyflow palette→canvas drop mechanism

**Files:**
- Read: `web-admin/app/plugins/core-designer/components/flow-designer-sdk/core/FlowPalette.tsx`, `.../core/FlowCanvas.tsx` (or the canvas component), and the automation `FlowDesigner` usage.

- [ ] **Step 1: Read the palette + canvas drop wiring**

Run: `rg -n "onDragStart|onDrop|dataTransfer|onDragOver|useDraggable|DndContext|@dnd-kit|reactflow|onNodesChange|screenToFlowPosition" web-admin/app/plugins/core-designer/components/flow-designer-sdk -g '*.tsx' -g '*.ts'`

Expected: identify whether nodes are added via (a) HTML5 drag — `onDragStart` setting `dataTransfer` + canvas `onDrop` reading it + `screenToFlowPosition`, or (b) @dnd-kit `DndContext`/`useDraggable`. Record the answer + the exact handler names/data keys.

- [ ] **Step 2: Record the verified mechanism**

Write the finding (mechanism + handler names + the dataTransfer key or dnd sensor) into the coverage-matrix doc's "Phase 0 findings" section. This determines `dragNodeToCanvas` in Task 0.5.

### Task 0.2: Audit data-testid on designer surfaces

- [ ] **Step 1: Grep for testids on the designer elements we must drive**

Run: `rg -n "data-testid" web-admin/app/plugins/core-designer/components/flow-designer-sdk web-admin/app/framework/smart/automation/components`

Expected: a list of existing testids on palette items, canvas, nodes, node handles, property-panel fields, save/enable buttons. Note which of these are MISSING (palette item per node type, node by id, source/target handle, property field by key, save button, enable toggle).

- [ ] **Step 2: Decide testid additions**

If required selectors are missing, list the exact component files + the testid strings to add as Task 0.2b. Stable, semantic testids only (e.g. `data-testid="palette-node-trigger-record-create"`, `flow-node-<id>`, `node-handle-source-<id>`, `prop-field-<key>`, `designer-save`, `designer-enable`). Record in findings.

### Task 0.2b (conditional): Add missing data-testid

**Files:** the component files identified in 0.2 (e.g. `FlowPalette.tsx`, the @xyflow node wrapper, `FlowPropertyPanel.tsx`, the toolbar).

- [ ] **Step 1: Add the testids** (only those Task 0.2 found missing; show the exact JSX edits when executing).
- [ ] **Step 2: Run frontend typecheck** — Run: `cd web-admin && npx tsc --noEmit` — Expected: 0 new errors.
- [ ] **Step 3: Run the touched components' existing vitest** — Expected: still green.
- [ ] **Step 4: Commit** — `git commit -m "test(automation): add stable data-testid for designer golden E2E"`

### Task 0.3: Stand up the isolated E2E stack + verify infra reachable

> Use the GA E2E docker stack pattern (`up → bootstrap → down`), NOT start-isolated (see feedback_check_docs_on_task_type_switch). Check disk ≥ 30GB first (§11 — multiple stacks may be running).

- [ ] **Step 1: Preflight host** — Run: `df -h / | tail -1` (≥30GB free) + `docker ps` (note running stacks/ports to avoid collision).
- [ ] **Step 2: Bring up the isolated stack** — use the project's Web E2E stack bring-up (check `scripts/` for the GA E2E up command + `auraboot/scripts/oss-test.sh`); set a unique `COMPOSE_PROJECT_NAME` + port offset.
- [ ] **Step 3: Health gate** — Run: `curl -s <backend>/actuator/health` (UP) + login (seed JWT or the E2E login) + BFF proxy reachable + designer route loads.

### Task 0.4: Verify e2et_order seed reachability (GATE)

- [ ] **Step 1: Find who seeds e2et_order**

Run: `rg -rln "e2et_order|e2eto" platform auraboot-enterprise --type java --type json --type yaml | head` and `rg -rn "e2et_order" web-admin/tests/e2e` — determine whether it comes from an E2E bootstrap, a `test-fixtures` plugin, or a spec setup step.

- [ ] **Step 2: Confirm it exists in the running stack**

Run (against the stack): `curl -s -H "Authorization: Bearer <jwt>" <backend>/api/meta/models?modelCode=e2et_order` (or the correct endpoint — verify the param name against the controller) → model present. And confirm the create command `e2eto:create_e2et_order` (or the real code) is registered.

- [ ] **Step 3: If absent, fix infra first** — ensure the e2e bootstrap/fixture seeds it; do NOT build cases against a missing model. Record the seed source in findings.

### Task 0.5: Define + implement the shared harness

**Files:** Create `web-admin/tests/e2e/_helpers/flow-designer-harness.ts`

- [ ] **Step 1: Define the harness interface** (signatures Phases 1–3 call; concrete now even before bodies):

```ts
// All helpers take the Playwright `page`. Selectors use the testids confirmed/added in 0.2.
export async function dragNodeToCanvas(page: Page, paletteType: string, pos: { x: number; y: number }): Promise<string /* nodeId */>;
export async function connectEdge(page: Page, sourceNodeId: string, targetNodeId: string): Promise<void>;
export async function fillNodeConfig(page: Page, nodeId: string, fields: Record<string, unknown>): Promise<void>;
export async function saveAutomation(page: Page): Promise<{ pid: string }>;
export async function enableAutomation(page: Page, pid: string): Promise<void>;
export async function pollNodeStatuses(page: Page, logId: string, timeoutMs?: number): Promise<Array<{ nodeId: string; status: string; errorMessage?: string }>>;
export async function fireRecordTrigger(page: Page, modelCode: string, record: Record<string, unknown>): Promise<{ recordId: string }>;
```

- [ ] **Step 2: Implement `dragNodeToCanvas` per the verified mechanism (Task 0.1)**

If HTML5 drag: dispatch `dragstart` on the palette item, `dragover`+`drop` on the canvas at `pos` (set the verified `dataTransfer` key). If @dnd-kit: multi-step `mouse.down` + injected `pointermove`/`pointerup` (NOT single `dragTo` — red line §20 / dnd-conventions). Return the new node's id (read from the @xyflow node testid or store).

- [ ] **Step 3: Implement `connectEdge`** — multi-step pointer drag from `node-handle-source-<src>` to `node-handle-target-<tgt>` (@xyflow connection drag is pointer-based; single dragTo unreliable).

- [ ] **Step 4: Implement remaining helpers** — `fillNodeConfig` (select the node → property panel → set each field by its type), `saveAutomation` (click `designer-save`, capture the POST response pid), `enableAutomation`, `pollNodeStatuses` (lift from the existing automation-golden.spec), `fireRecordTrigger` (POST the create command).

- [ ] **Step 5: Smoke the harness** — a throwaway spec that drags ONE trigger node + asserts it appears on canvas; run it against the stack to prove the drag mechanism works end-to-end. Expected: node appears. (This is the §20 "single-step-drag-not-working may hide a real bug" guard — if it won't drag, debug the mechanism before proceeding.)

- [ ] **Step 6: Commit** — `git commit -m "test(automation): flow-designer E2E harness (drag/connect/config/fire)"`

### Task 0.6: Palette front-coverage test (all 18 node types)

**Files:** Create `web-admin/app/framework/smart/automation/nodes/__tests__/palette-coverage.test.ts`

- [ ] **Step 1: Write the test** — import `automationNodes`; assert exactly these 18 `type`s present, each with a `category` in {trigger,action,control}, an `$i18n:`-prefixed `label`+`description`, and a `configSchema` array. List the 18 explicitly: trigger-record-create/-record-update/-field-change/-state-change/-scheduled/-webhook/-bpm-event; action-update-record/-create-record/-send-notification/-execute-command/-call-api/-send-webhook/-start-process/-llm-call; control-condition/-loop/-delay.
- [ ] **Step 2: Run** — `cd web-admin && npx vitest run app/framework/smart/automation/nodes/__tests__/palette-coverage.test.ts` — Expected: PASS (proves no node type silently dropped from the palette).
- [ ] **Step 3: Commit.**

---

> **Phase-0 progress (2026-06-05, in-session):**
> - ✅ 0.1 drag mechanism = HTML5 native drag (key `application/flow-node`).
> - ✅ 0.2/0.2b testids added (commit 7a4d632fc): `palette-node-<type>`, `flow-node-<id>` (pre-existing), `node-handle-source/target-<id>` (+ condition `-true`/`-false`), `prop-field-<key>`, `designer-save`. **No in-designer enable control** → enable via list `btn-toggle-<pid>` (UI) or `/enable` API.
> - ✅ 0.6 palette-coverage test (110 assertions, 18 types) green.
> - ✅ 0.5 harness first cut committed (`flow-designer-harness.ts`, db26f9923) — UI helpers smoke-PENDING.
> - ✅ 0.3 GA E2E stack UP + healthy (`docker-ga-e2e-up.sh`; backend 6444 / vite 5174 / BFF 3501 / pg 5433; disk held at ~26Gi — accepted risk did not bite). **CORRECTION (verified via `docker inspect`): the ga-e2e-frontend container mounts THIS worktree `/Users/ghj/work/auraboot/.automation-golden-wt → /repo`. So the running frontend IS the worktree with A's testids — NO HMR-cp needed. (The earlier "rsync from canonical" note was a misread of an unrelated process.)** Stack persists across sessions until `docker-ga-e2e-down.sh`.
> - ✅ 0.4 bootstrap done (`docker-ga-e2e-bootstrap.sh`, exit 0): admin@auraboot.com/Test2026x + platform_admin verified; `Importing test-fixtures OK` → e2et_order/e2et_order_item/e2et_order_log models + `e2eto:create_e2et_order` command all reachable (verified via API). Login token field = `data.jwt`. NOTE: the bootstrap's optional Playwright `auth.setup` step (generates `tests/storage/admin.json`) FAILED on missing env — to run any Playwright against this stack, set `PLAYWRIGHT_BASE_URL=http://localhost:5174 BACKEND_URL=http://localhost:6444 BE_PORT=6444 BFF_PORT=3501 PW_SKIP_WEBSERVER=1` and regenerate the admin storageState (or log in within the test).
> - ✅ 0.5 harness smoke PASS (main-loop re-verified 2/2, subagent 4/4): `_harness-smoke.spec.ts` drags trigger-record-create (0→1) + action (1→2) on the real canvas. Run with the env above + `--no-deps` (the default global setup loads enterprise pcba demo-data which is absent → ENOENT; `--no-deps` skips it). Auth: `tests/storage/ga/admin.json` via `auth.setup.ts --no-deps` + `PW_STORAGE_DIR=tests/storage/ga` (gitignored — JWT not tracked).
> - 🐛 **§20 guard paid off — golden found + fixed 2 REAL FlowCanvas bugs** (commit e1d16259c, shared by automation AND bpmn designers): (1) `useReactFlow()`/`screenToFlowPosition` was called outside the ReactFlow provider → drop coords wrong → dropped node landed off-screen. Fixed via a `ScreenToFlowPositionCapture` null child rendered inside `<ReactFlow>` that captures the fn into a ref. (2) `fitView` re-ran on every node drop on an empty canvas → zoomed and pushed the fresh node below the viewport. Fixed by enabling `fitView` only when the canvas mounted with pre-existing nodes (edit-mode/bpmn load); empty canvases keep 1:1 zoom. Regression: 254 flow-designer-sdk + bpmn + bpm-designer-sdk vitest pass, tsc clean (the fitView conditional preserves edit-mode/bpmn behavior).
>
> **PHASE 0 COMPLETE.** Infra GREEN + harness smoke-verified. The GA E2E stack stays UP (until `docker-ga-e2e-down.sh`) for Phases 1–4. A fresh session continues from Phase 1 against the up stack: `git checkout feat/automation-designer-golden-e2e`, env `PLAYWRIGHT_BASE_URL=http://localhost:5174 BACKEND_URL=http://localhost:6444 BE_PORT=6444 BFF_PORT=3501 PW_SKIP_WEBSERVER=1 PW_STORAGE_DIR=tests/storage/ga`, run with `--no-deps`.
> **Phase-1 status (2026-06-05) — ✅ VERIFIED + CLOSED (commit cf7e29a0a):**
> - All 6 Layer A cases (H1/H2/H3/S1/S2/E5) run **GREEN against a clean fresh GA E2E stack**, proven cold-robust (re-run on a cold-restarted vite: 6/6 pass, H1 7.9s/H2 1.8s/H3 0.8s/S1 2.2s/S2 3.7s/E5 2.1s).
> - **Fixed two committed defects:** (1) spec tsc error line 341 — `r.request().method()` → `r.method()` (Playwright `Request` already exposes `.method()`); `tsc --noEmit` now 0 errors. (2) cold-vite flake — `openNewDesigner` now waits 30s for the editor name input before palette/pane, so the first fresh-stack hit absorbs the heavy @xyflow chunk compile (the original H1 failure was a cold-compile timeout, NOT a product bug — confirmed by warm re-run passing).
> - **4 product changes REVIEWED (§14/§20) — all legitimate, no make-it-pass hack:** PB-2 `AutomationServiceImpl.toggle()` delegates to `enable()`/`disable()` so the list "Enable" button shares the SmartEngine deploy (real gate-gap bug: enabled-but-never-deployed → "Process definition version not found"); sound self-invocation, identical toggle semantics. PB-3 `AutomationEditPageImpl` wires `?logId=`→`useAutomationNodeStatuses`→`nodeStatuses` (verified threading all the way to `NodeRuntimeStatusProvider`); gated on `!isNew`. PB-4 `PropertyFieldRenderer` surfaces field-level validation error (red ring + required `*` + error text) mirroring sibling renderers — additive, what §2.2 requires. `FlowDesigner.__flowDesignerStore` is a PROD-guarded test affordance mirroring `__bpmnDesignerStore`.
> - **Screenshots reviewed (real UI):** H1 = 4-node drag-built flow renders; H3 = G5 green-check badge on executed branch + none on not-taken; S1 = "This field is required" red error + red ring + "存在错误(1)" + Unsaved (gate blocked the write).
> - Backend change is now in the running backend (clean rebuild at stack-up). Stack stays UP for Phases 2–4.

## Phase 1 — Layer A: real drag-drop user journey (happy + UI sad/edge)

> All tasks use the Phase-0 harness. Each is a Playwright test in `automation-designer-golden.spec.ts`. TDD here = write the test (it fails until the flow works), run against the stack, make green, screenshot-review, commit.

- [x] **Task 1.1 (H1) — full happy journey** — drag trigger-record-create → control-condition → action-update-record, connect edges, configure (modelCode=e2et_order, condition `amount>1000`, update field mapping), fill required, save (assert POST flowConfig{3 nodes,edges}), reload (assert canvas re-renders), enable, fire record amount=2000, poll node-statuses → trigger/condition/action all completed, no failed, assert target record field updated + status badges. Screenshot-review. Commit.
- [x] **Task 1.2 (H2)** — save → navigate away → return → assert canvas re-renders the saved 3-node graph (persistence roundtrip). Commit.
- [x] **Task 1.3 (H3)** — after fire, assert the G5 node-status overlay badges render in the canvas (completed states visible). Commit.
- [x] **Task 1.4 (S1)** — build a flow with trigger missing required `modelCode` → click save → assert save blocked + field-level error on `modelCode` (G4 gate), NOT a generic toast. Commit.
- [x] **Task 1.5 (S2)** — set an oversized/dangerous SpEL condition on control-condition → assert it is rejected (SpelSafetyGuard / validation), surfaced to the user. Commit.
- [x] **Task 1.6 (E5)** — open an existing enabled automation, change the action config via the UI, save, re-fire → assert the NEW behavior takes effect (re-derive + re-deploy). Commit.

---

## Phase 2 — Layer B: runtime behavioral matrix

> Tests in `automation-golden.spec.ts` (extend). Setup builds flowConfig via API (`POST /api/automations`), enables, fires a real trigger, asserts backend behavior. Clearly labeled "behavioral (non-UI-golden)".

- [x] **Task 2.1 (S3)** — action configured to fail at runtime (e.g. update-record targeting a nonexistent field) → fire → assert node-status=failed + errorMessage present + AutomationLog status FAILED. Commit.
- [x] **Task 2.2 (S4)** — attempt to create/enable a flowConfig with no trigger node, and separately with two trigger nodes → assert `AutomationFlowTriggerDeriver` rejects with ValidationException (HTTP 4xx). Commit.
- [x] **Task 2.3 (S5)** — condition false branch → fire with amount below threshold → assert the downstream action did NOT run (P0-2 gating); node-status shows condition completed, action not executed. Commit.
- [x] **Task 2.4 (E1)** — trigger → a1 → a2 (two sequential actions) → fire → assert both executed in order. Commit.
- [x] **Task 2.5 (E2)** — condition with both true and false branches wired to different actions → fire true-matching → assert only true-branch action ran; fire false-matching → only false-branch. Commit.
- [~] **Task 2.6 (E3) — HONEST SKIP** — multi-iteration control-loop is covered by the platform `AutomationProcessRuntimeIntegrationTest` (3-element / empty / absent-collection assertions via `runtime.run`). The E2E fire path cannot supply a multi-element collection: the `on_record_create` trigger context is built from the e2et_order record, which has no collection/array field for the loop to iterate. Deferred (counted skip in the suite), not faked.
- [x] **Task 2.7 (E6)** — enable → fire → runs; disable → fire → does not run; re-enable → fire → runs. Commit.
- [x] **Task 2.8 (C1)** — tenant isolation: as tenant B, attempt to read/trigger tenant A's automation by pid → 404/denied (IDOR, #264). Commit.
- [x] **Task 2.9 (C2)** — fire N concurrent trigger events for one rule → assert the per-rule semaphore bounds concurrency (no error storm / thread exhaustion; all eventually run). Commit.
- [x] **Task 2.10 (C3)** — empty flowConfig (no nodes) create → assert rejected or no-op (no crash). Commit.

> **Phase-2 status (2026-06-05) — ✅ COMPLETE + VERIFIED** (commits 368339e25 / dfd62b33f / 1da77f4b9). `automation-golden.spec.ts` = **10 passed + 1 honest skip** against the real engine + DB (B1 + E6/S4/C3/S3/S5/E1/E2/C2/C1, E3 = counted skip). Layer A designer golden still 6/6 (no regression).
> **2 real product bugs the Layer-B golden surfaced (both gate-green before — only an assembled-runtime behavioral test catches them, §2.2), fixed + verified:**
> - **PB-5 BPM re-deploy → 500.** `ProcessDeploymentService.createNewVersion` inserted `bpmnContent` verbatim (null from the automation re-deploy caller) instead of compiling it from `designerJson` like `create()`/`update()` → the SECOND deploy of any automation (E6 re-enable, edit+re-deploy) hit `null value in column "bpmn_content" violates not-null constraint`. Fixed: compile from designerJson (fall back to "" like create()). Verified by E6.
> - **PB-6 empty-flowConfig create → 500.** `AutomationServiceImpl.validateCreateRequest` treated `{nodes:[],edges:[]}` as visual-designer mode (non-empty Map) and skipped the flat `modelCode` required check → `ab_automation.model_code` NOT NULL crash. Fixed: `isFlowConfigOnly` now checks for actual nodes (mirrors `enable()`) → clean 400. Verified by C3.
> Both fixes are platform changes (rebuilt into the GA E2E backend) and regress neither Layer A (6/6) nor Layer B B1.

---

## Phase 3 — Full node-type coverage (all 18)

> front: palette-coverage test (Task 0.6, done) + property-panel render. back: per-type behavior. Each task fires a real trigger / asserts the action's specific side effect / asserts the control behavior. delay-runtime + scheduled-realtime are HONEST SKIPS.

- [ ] **Task 3.1 — triggers ×7 fire coverage** (record-create [covered by 1.1], record-update [watched vs non-watched], field-change, state-change, webhook [valid sig fires + bad sig rejected, ties #415], bpm-event [matching eventType fires, non-matching not], scheduled [HONEST SKIP — heavy cron-realtime; note IT coverage AutomationSchedulerTest]). One behavioral test per trigger; record skip reason for scheduled. Commit per trigger or grouped.
- [ ] **Task 3.2 — actions ×8 side-effect coverage** (update-record [covered], create-record [new row], send-notification [notification row], execute-command [command ran], call-api [outbound HTTP intercepted], send-webhook [outbound sent], start-process [BPM instance started — assert instance exists; downstream execution deferred to bpmn slice], llm-call [LLM invoked via stub-llm/mock]). One behavioral test per action asserting its specific side effect. Commit per action or grouped.
- [ ] **Task 3.3 — controls ×3** (condition [covered 2.3/2.5], loop [covered 2.6], delay [HONEST SKIP — SmartEngine timer suspended roadmap #8; front render verified by palette-coverage]). Record delay skip reason. Commit.
- [ ] **Task 3.4 — property-panel render coverage** — component test(s) asserting each configSchema field type renders for representative nodes (model-select, field-select, expression, json, select, multiselect, process-select, command-select, number, boolean, text, textarea). Commit.

---

## Phase 4 — Acceptance (golden gate)

- [ ] **Task 4.1** — fill `docs/superpowers/specs/2026-06-05-automation-designer-golden-coverage-matrix.md`: every case from §C/§C2 with 3 columns (executed/skip+reason/did-not-run | evidence link | pass-or-fail). No silent folding of unexecuted rows.
- [ ] **Task 4.2** — export + human-review screenshots for the Layer A journey (eyeball real UI states).
- [ ] **Task 4.3** — run `/e2e-truth` self-audit; restate results precisely ("X golden-UI pass / Y behavioral pass / Z skip(real gap) / W did-not-run, node-type coverage 18/18 front, 16/18 back + 2 honest skip").
- [ ] **Task 4.4** — run the Layer A journey ≥3× consecutively; assert 0 flakes (characterize, don't trust single green).
- [ ] **Task 4.5** — final report layered: golden UI pass / behavioral pass / skip+reason / did-not-run / remaining gaps. Open PR.

---

## Self-Review (against spec)

- **Spec coverage:** §A→Phases (Layer A=P1, Layer B=P2). §B→Task 1.1. §C all cases→P1/P2 tasks. §C2 18 types→Task 0.6 (front) + 3.1/3.2/3.3 (back). §D→Phase 0 (0.3/0.4 infra gate, 0.1 mechanism). §E harness→0.5. §F acceptance→Phase 4. No spec section without a task. ✅
- **Placeholder scan:** No "TBD/TODO". The Phase-0 findings (drag mechanism, seed source, testids) are produced by concrete investigation tasks, not placeholders; harness bodies are gated on 0.1 by design (correct sequencing, not a cop-out). Layer A/B case tasks have concrete acceptance criteria + named harness calls. ✅
- **Type consistency:** Harness signatures defined once in Task 0.5 and called by name in Phases 1–3 (`dragNodeToCanvas`/`connectEdge`/`fillNodeConfig`/`saveAutomation`/`enableAutomation`/`pollNodeStatuses`/`fireRecordTrigger`). The 18 node-type strings match the grep-verified palette inventory. ✅
- **Note on TDD complete-code:** Layer A/B step-level gesture/selector code is intentionally produced at execution time from Phase-0 outputs (mechanism + testids). This is the only honest sequencing — writing gesture code before verifying the @xyflow drop mechanism would be guessing (red line §15/§20). Phase 0 makes those facts concrete first.

# Automation Golden E2E — Phase 3 findings (2026-06-05)

Phase 3 (18-node-type back coverage) extended the Layer B behavioral matrix in
`web-admin/tests/e2e/automation/automation-golden.spec.ts`. The assembled-runtime
behavioral golden (§2.2) surfaced **four real production bugs** that passed every
static / unit / validator gate, plus closed one missing-executor gap. All are fixed +
verified against a fresh GA E2E stack (`community,test`); honest skips are counted with
the exact blocker recorded.

**Final state:** `automation-golden.spec.ts` = **18 passed + 3 skipped** (E3 loop,
action-llm-call [FINDING-5], action-start-process E2E [FINDING-7]).

Node-type back coverage (fire-verified ✅ / honest-skip ⏭ / executor-only 🟦):
- triggers ×7: record-create ✅ · record-update ✅ · field-change ✅ · state-change ✅ ·
  webhook ✅ · bpm-event ⏭(BPM stub, FINDING-7) · scheduled ⏭(cron-realtime, IT-covered)
- actions ×8: update-record ✅ · create-record ✅ · send-notification ✅ ·
  execute-command ✅(denial, FINDING-3) · call-api ✅(FINDING-6 fixed) · send-webhook ✅ ·
  start-process 🟦(executor added + unit-tested; E2E ⏭ BPM stub, FINDING-7) ·
  llm-call ⏭(stub-mode, FINDING-5)
- controls ×3: condition ✅ · loop ⏭(E3, IT-covered) · delay ⏭(SmartEngine timer suspended)

---

## ✅ FINDING-1 (REAL BUG — FIXED + VERIFIED, merged in #437) — webhook/scheduled automations cannot be created
`ab_automation.model_code` was NOT NULL but `AutomationFlowTriggerDeriver` leaves it null
for webhook/scheduled triggers → HTTP 500 on create. Fixed: made the column nullable
(`schema.sql` + `database/migrations/2026-06-05-automation-model-code-nullable.sql`).
trigger-webhook fire is now green.

## ✅ FINDING-2 (RESOLVED) — record-update fire was blocked by a test-fixture invocation bug
- **Inherited (WRONG) diagnosis:** "`e2eto:create_e2et_order` does not leave the order in
  draft, so `e2et:update_order`'s `status IN [draft,rejected]` precondition rejects."
- **Verified truth:** the create command DOES set `e2et_order_status=draft`
  (`autoSetFields` fixed_value — confirmed live). The real blocker was the test's update
  invocation: it passed the record id as `payload.pid` instead of the top-level
  `targetRecordId`. `AssertPhase` (field-operator precondition) reads
  `payload.get("e2et_order_status")` first and only loads the persisted record's status
  when `request.getTargetRecordId()` is non-blank — so with the id in the payload, the
  snapshot never loaded, status read null, `null IN [draft,rejected]` = false → rejected.
- **Fix:** the `updateOrder` helper now sends `{operationType:'update', targetRecordId, payload}`.
  trigger-record-update + trigger-field-change are green. (Lesson: §15 — re-verify inherited
  "blocked" conclusions empirically; the order WAS draft.)

## ✅ FINDING-3 (DESIGN DECISION — RESOLVED) — execute-command runs under a restricted principal
- **Decision (owner):** an automation runs `execute-command` under a **restricted system
  principal** (NOT the automation owner's authorities) — by design, to prevent privilege
  escalation — **and the denial surfaces a clear, specific failure reason**.
- The reason already exists (`CommandAuthorizationPhase`:
  `"Command permission denied: required one of " + join(perms)`). The test now asserts the
  by-design boundary: `e2et:update_order` (needs `E2ET.order.manage`) → node failed with a
  reason that names the required permission. To run a command from an automation, configure
  one that requires no business permission.

## ✅ FINDING-4 (REAL BUG — FIXED + VERIFIED) — every on_state_change run crashed
- **Symptom:** firing any `on_state_change` automation via a state_transition command →
  run `failed`, `errorMessage = "Cannot invoke \"Object.getClass()\" because \"value\" is null"`.
- **Root cause:** a state_transition command applies the new state from its own config and
  does NOT echo it into the `CommandCompletedEvent` payload, so
  `AutomationCommandEventBridge.handleStateTransition` built a trigger payload with a null
  `toState` (and null `fromState` when no before-snapshot). `AutomationProcessRuntime.run`
  put those into the SmartEngine variable map, and **null process variables NPE deep in
  `startProcess`**. Static/unit/validator all green — only the assembled run catches it.
- **Fix:** strip null-valued variables in `AutomationProcessRuntime.run`
  (`variables.values().removeIf(Objects::isNull)`) — an absent variable is the correct
  semantics for a null. `handleStateTransition` additionally best-effort-reads the
  post-commit state (`CommandStateCheckExecutor.readCurrentState`) to enrich `toState`.
- **FINDING-4b (follow-up, not blocking):** a SPECIFIC `toStates:['cancelled']` filter is
  still imprecise — `readCurrentState` returns null when the async `CommandCompletedEvent`
  carries no tenant, so `toState` is unavailable for filtering. The state-change test filters
  on "any transition" (empty fromStates/toStates), which is fully green. Tightening the
  toStates filter needs a reliable tenant on the async event (small follow-up).

## ⏭ FINDING-5 (honest skip) — action-llm-call needs stub-mode on the E2E stack
- The executor + built-in `StubLlmProvider` both exist, but the GA test tenant carries a
  SEEDED real provider (minimax, from the showcase seed) that overrides the yml stub-sentinel
  fallback → the run makes a real call → 401. The clean lever is `agent.llm.stub-mode=true`
  on the GA backend (its intended use: exercise the chat pipeline without real credentials),
  a stack-config not a product gap. Executor is unit + IT covered (`LlmCallExecutorTest` +
  Streaming/Vision IT). **Follow-up:** enable `agent.llm.stub-mode` on the ga-e2e stack, then
  un-fixme.

## 🟦 / ⏭ FINDING-7 — start-process executor added; bpm-event + start-process E2E blocked by the OSS BPM stub
- **Gap closed:** `action-start-process` shipped with NO backend executor —
  `CompositeActionExecutor` threw `UnsupportedOperationException("No executor found for action
  type: start_process")` for every automation using it. Added `StartProcessActionExecutor`
  (delegates to `BpmIntegrationService.startBusinessProcess`) + `StartProcessActionExecutorTest`
  (5/5). The node now runs through to the BPM call.
- **Honest skip for the E2E fire:** the OSS SmartEngine BPM adapter is a stub
  (`processEngineService.startProcess` → "not implement intentionally"), so a real process
  instance cannot start on OSS — the run fails with the stub message, not a node defect. The
  same limitation blocks `trigger-bpm-event` (no real process events to fire). Assembled-runtime
  verification needs a non-stub BPM engine (enterprise). (Lesson: §15 — the spike's "bpm-event
  feasible" was inferred from code structure, not run; the BPM engine is stubbed.)

## ✅ FINDING-6 (REAL BUG — FIXED + VERIFIED) — call_api was 100% broken
- **Symptom:** every `action-call-api` run → `failed`, `"Unsupported HTTP method: get"`.
- **Root cause:** `CallApiExecutor` did `switch (method.toUpperCase())` against LOWERCASE
  case labels (`case "get"` …), so `"GET"` never matched any case → default → throw. Every
  method (incl. the default `post`) fell through → call_api never worked. Red line §9
  (case-consistency).
- **Fix:** `switch (method.toLowerCase())`. The action-call-api test (real outbound GET to
  `host.docker.internal:6444/actuator/health`, SSrf test-profile allowlist) is green.

## Already covered by Layer A / Layer B
record-create trigger (B1/H1), send-notification (B1), update-record (H1/E1/S3),
condition control (S5/E2), webhook (FINDING-1).

## Remaining (next session)
- **Task 3.4** — property-panel render vitest (each configSchema field type renders).
- **Phase 4** — coverage matrix doc, `/e2e-truth` self-audit, ≥3× flake, **PR**.
- **FINDING-4b / FINDING-5 / FINDING-7** — small follow-ups (tenant on async event; stub-mode
  on stack; enterprise BPM for start-process/bpm-event E2E).

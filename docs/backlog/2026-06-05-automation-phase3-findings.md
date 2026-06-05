# Automation Golden E2E — Phase 3 findings (2026-06-05)

Phase 3 (18-node-type back coverage) extended the Layer B behavioral matrix in
`web-admin/tests/e2e/automation/automation-golden.spec.ts`. One new case passes;
three surfaced **real product/design issues** that block their fire path. Per §2.4
they are counted `test.fixme` (not silently dropped), with root cause + the exact
fix recorded here.

## ✅ Passing
- **action-create-record** — the action creates a child `e2et_order_item` linked to
  the trigger order via `${recordId}` (no recursion: a different model than the
  trigger). Verified: node `completed`, the item exists and is linked. (Reuses
  `CreateRecordExecutor`, which resolves `${var}` in fields against the context.)
- **trigger-webhook** — an inbound `POST /api/automations/webhooks/{pid}` fires the
  automation to success. Verified after the FINDING-1 fix below.

## 🟩 FINDING-1 (REAL BUG — FIXED + VERIFIED) — webhook / scheduled automations cannot be created
- **Symptom:** `POST /api/automations` for a `trigger-webhook` flow → **HTTP 500**
  `null value in column "model_code" of relation "ab_automation" violates not-null
  constraint`.
- **Root cause:** `AutomationFlowTriggerDeriver` intentionally leaves `modelCode`
  **null** for webhook/scheduled triggers (its own javadoc: *"modelCode optional —
  absent for scheduled/webhook"*), but `ab_automation.model_code` is **NOT NULL**
  (`platform/src/main/resources/database/schema.sql:3093`,
  `model_code VARCHAR(100) NOT NULL`). The live column is NOT NULL. So a webhook (or
  scheduled) automation can never be inserted. This is the same class as PB-6 but at
  the schema layer — every static/unit/validator gate is green; only the assembled
  runtime create surfaces it.
- **Fix (shipped):** made `ab_automation.model_code` nullable.
  - `schema.sql:3093` → `model_code VARCHAR(100),`
  - new migration `database/migrations/2026-06-05-automation-model-code-nullable.sql`:
    `ALTER TABLE ab_automation ALTER COLUMN model_code DROP NOT NULL;`
  - (`trigger_type` stays NOT NULL — every trigger has a type.) Safe: nullability
    relaxation, no data loss. The webhook fire path uses the webhook controller
    (`AutomationWebhookController` → `findByPid`), not model-based dispatch, so a null
    `model_code` is correct for webhook/scheduled.
- **Verified:** GA stack reset (`down --purge` + `up --rebuild` + bootstrap) →
  `ab_automation.model_code` is now `is_nullable=YES`; the `trigger-webhook` test
  (un-`fixme`d) creates the webhook automation + fires it via the webhook POST →
  run success. Layer A 6/6 + Layer B 12/12 (B1 + Phase-2 9 + create-record + webhook),
  no regression from the schema change.

## 🟧 FINDING-2 (test-fixture gap) — record-update fire path is status-gated
- **Symptom:** `trigger-record-update` test fires the update via `e2et:update_order`
  → command rejected at its assert phase: **"仅草稿或已退回状态可编辑"** (only draft/
  rejected status editable). `e2eto:create_e2et_order` does not leave the order in
  draft, so the update is blocked before the trigger can dispatch.
- **Assessment:** the `trigger-record-update` wiring is sound; the blocker is the
  fixture's update path. Need a status-unconstrained update to dispatch
  `on_record_update`: e.g. seed/transition an order to draft first, or fire via
  `e2et:update_order_item` (verify it carries no status gate), or confirm whether a
  raw `PUT /api/dynamic/{model}/{id}` dispatches the automation trigger.
- **Fix:** choose a status-unconstrained update fixture, then un-`fixme`.

## 🟧 FINDING-3 (security-model decision) — execute-command runs under a restricted principal
- **Symptom:** `action-execute-command` running `e2et:update_order` → node `failed`
  with **"Command permission denied: required one of E2ET.order.manage"**. The
  automation's command execution principal lacks the command's required permission
  (the automation owner — admin — holds it).
- **Open question:** should an automation run commands **with its owner's
  permissions** (current behavior = a propagation gap/bug) or under a **restricted
  system principal** (by design, to prevent privilege escalation)? This is a
  security-model decision, not a test bug. `ExecuteCommandExecutor` is unit-covered.
- **Fix:** decide the principal policy. If owner-perms: propagate the owner's
  authorities into the automation action execution context, then un-`fixme`. If
  restricted-by-design: document it and point the test at a permission-free command.

## Honest skips (counted, IT/roadmap-covered) — not attempted in the E2E fire path
- **action-llm-call** — covered by `LlmCallExecutorTest` + `LlmCallExecutorVision/
  StreamingIntegrationTest`; E2E fire needs an LLM stub provider.
- **trigger-bpm-event / action-start-process** — need a deployable BPM process +
  event publish; derivation covered by `AutomationFlowConfigDerivationIntegrationTest`.
- **trigger-scheduled** (cron-realtime, `AutomationSchedulerTest`), **control-delay**
  (SmartEngine timer suspended, roadmap #8), **control-loop** fire-path (E3 — covered
  by `AutomationProcessRuntimeIntegrationTest`; e2et_order has no collection field).
- **action-call-api / action-send-webhook** — `CallApiExecutor`/`SendWebhookExecutor`
  make real HTTP via `java.net.http.HttpClient` and are unit-covered; an E2E fire
  pointing at a loopback endpoint + asserting node-completed is feasible as a
  follow-up (egress-to-loopback feasibility to confirm live).

## Already covered by Layer A / Layer B
record-create trigger (B1/H1), send-notification (B1), update-record (H1/E1),
condition control (S5/E2).

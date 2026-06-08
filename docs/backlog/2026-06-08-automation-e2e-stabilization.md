---
type: backlog
status: active
created: 2026-06-08
---

# Automation / AuraBot E2E stabilization — 4 deferred items resolved (2026-06-08)

Resolves the four items deferred from the BPM/automation coverage work (PR #452 / #453
comments). All verified on an isolated docker stack (`start-isolated --slug=autostab --e2e`,
backend served from the worktree via bind-mount so frontend fixes apply over vite HMR).

Branch: `feat/automation-e2e-stabilization`.

## Fixes

### 1. `trigger-record-update` cross-file cascade → cross-process file lock
`automation-golden`'s `trigger-record-update` asserted *"a create must NOT fire an
on_record_update automation"*, but `automation-designer-golden` H1 leaves an enabled
`on_record_create → update_record` automation live across its serial H1→H2→H3 chain.
Record-trigger automations are **model-scoped** (`e2et_order`), so on a parallel worker
H1's automation fired an `update_record` on golden's freshly-created order → tripped the
on_record_update trigger → attributed a log to that exact `orderId`. triggerRecordId
scoping cannot help (the spurious update genuinely targets the test's own record).

Fix: `tests/e2e/automation/_e2et-order-lock.ts` — an atomic O_EXCL lockfile in the OS temp
dir (dead-holder-PID steal + 25min cap). The e2et_order-mutating specs (golden /
designer-golden / deep) acquire it in a top-level `beforeAll` and release in `afterAll`, so
at most one holds an enabled record-mutating automation at a time. The waiting `beforeAll`
sets `test.setTimeout(0)` (the wait can exceed the default 15s hook timeout).

Verified: `trigger-record-update` + designer H1 passed in **two** serialized runs of both
files together (workers=2; both files fully executed and serialized via the lock).

### 2. RC-01/02/03 result_contract rendering (two real fixes)
- **Flaky panel open:** `gotoAppAndOpenPanel`'s 3×-click-with-2500ms-early-break dance
  toggles the panel state (a flip); an even number of effective flips left it CLOSED
  ("aurabot-panel … unexpected value hidden"). New deterministic helper
  `tests/e2e/aurabot/_open-panel.ts` re-checks visibility **before every click** and never
  toggles an open panel shut.
- **Provider stale-closure wipe (product bug):** after the first send in a new session,
  `AuraBotProvider.refreshConversations` auto-restored the remembered conversation using a
  **stale-closure** `!state.currentConversationId` guard; `ensureConversation` writes
  `LAST_CONVERSATION_KEY` before the `set_current_conversation` dispatch commits, so a stale
  `refreshConversations` hydrated the (server-empty, because canned) conversation over the
  in-flight one — wiping the just-rendered result_contract. Fixed by guarding against the
  **live** id via `currentConversationIdRef`.

### 3. LLM-005 logs-dialog (test drift)
`ExecutionLogDialog` renders a humanized / i18n action label (NOT the raw `llm_call` code —
`automationTypeLabels` exists precisely to avoid raw codes); the test grepped the raw code.
Added a stable `data-testid="log-action-<actionType>"` to `ActionResultItem` and asserted
that (locale-independent).

### 4. Flake / drift bucket
- **VG-01/02** ("Bad parameter" on create): the trigger node config was missing
  `triggerType` (`AutomationFlowTriggerDeriver` requires `data.config.triggerType`). Added
  it. Also fixed a `initialData`-resets-`isDirty` race with a retry-dirty helper.
- **space-selection** (`current-tenant-name` not found): `Header.tsx` intentionally hides
  the separate tenant span when the name duplicates the env chip (`tenantDuplicatesChip`,
  e.g. "AuraBoot Dev" + a "Dev" chip). Test now accepts either rendering.
- saved-view (LF/RH/QF/BF), org-position (ORG-022): passed on re-run (per-run flake; not
  reproduced — left as-is).

## Out-of-scope (observed during golden verification; NOT in the 4 items, NOT regressions)
- **`automation-golden` action-send-webhook** — pre-existing **stale test**: uses the old
  eventType model, but `SendWebhookExecutor` migrated to a direct POST `url` (FINDING-10 /
  PR #438). The url-based capability is already covered by the passing designer
  `N-SEND-WEBHOOK-OUTBOUND`. Was masked as "did not run" in the original full suite. Fix
  needs the local `startWebhookReceiver` extracted to a shared helper. **Follow-up.**
- **N-LLM-CALL / action-llm-call** — require an LLM provider; fail with *"no LLM provider
  configured"* unless `AGENT_LLM_STUB_MODE=true` (set by `scripts/host-e2e-up.sh`, not by
  `start-isolated`). Env-only; tests are correct.
- **E2 (Phase 2 condition routing)** — one run failed with `socket hang up` on the
  create-command POST (transient infra under heavy serialized load), not the cascade.

## Verification env
`autostab` isolated stack: backend :6459, vite :5189, pg :5448, redis :6494. Run Playwright
on the host: `source` the autostab ports into `PLAYWRIGHT_BASE_URL`/`BACKEND_URL`, then
`pnpm exec playwright test -c playwright.oss.config.ts <spec> --no-deps`.

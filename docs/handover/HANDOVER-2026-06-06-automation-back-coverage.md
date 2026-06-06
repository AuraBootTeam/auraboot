---
type: handover
status: active
created: 2026-06-06
---

# Session Handover — 2026-06-06 · automation Phase-3 back coverage (PR #438)

## Session Summary
Pushed the automation 18-node-type **back** coverage on the existing `automation-golden.spec.ts`
behavioral matrix. The assembled-runtime golden (§2.2) surfaced + fixed **4 real production bugs**,
resolved the FINDING-3 security decision, and added the front property-panel render coverage.
All in **OSS PR #438** (`feat/automation-golden-back-coverage`, tip `e21683503`, 6 commits),
**OPEN, awaiting review/merge**.

## Tasks Completed (verified)
- [x] **FINDING-2** — record-update fire fixed. Inherited "create doesn't leave it draft" was WRONG
  (it does — verified); real bug = the `updateOrder` helper passed the id as `payload.pid` instead
  of top-level `targetRecordId`, so `AssertPhase` couldn't load the persisted status for the
  `IN [draft,rejected]` precondition. + added trigger-field-change.
- [x] **FINDING-4** (REAL BUG) — every `on_state_change` run crashed (`Object.getClass() because
  value is null`): state_transition doesn't echo the applied state → null SmartEngine variable NPE.
  Fixed by stripping null variables in `AutomationProcessRuntime.run` (+ best-effort `readCurrentState`
  in the bridge). State-change test filters on "any transition" (empty from/to).
- [x] **FINDING-6** (REAL BUG) — `call_api` 100% broken: `switch (method.toUpperCase())` vs lowercase
  case labels (§9). Fixed → `toLowerCase()`. + send-webhook (dispatch completes).
- [x] **start_process executor gap** — palette node had NO backend executor (CompositeActionExecutor
  threw UnsupportedOperationException). Added `StartProcessActionExecutor` + `StartProcessActionExecutorTest`
  (5/5). E2E fire is an honest skip (OSS BPM adapter is a stub).
- [x] **FINDING-3 decision (owner)** — execute-command runs under a restricted system principal (not the
  owner's authorities), by design; test asserts the denial names the required permission.
- [x] **Task 3.4** — front property-panel render vitest (13/13): all 12 automation configSchema field
  types render a control + a regression guard.
- [x] **llm-call verified end-to-end vs a REAL provider** (2026-06-06, owner one-time minimax key): an
  `action-llm-call` automation ran to **success** against real minimax configured at runtime via the
  encrypted cloud-config API. Key deleted + volume `--purge`d after; **never committed** (git has 0
  occurrences). Committed E2E stays `test.fixme` (a real-provider test isn't CI-portable).

**Verified:** `automation-golden.spec.ts` = 18 passed + 3 skip (E3 loop, llm-call, start-process),
**3× flake-free**. Unit: StartProcessActionExecutorTest 5/5 + CallApiExecutorTest. Front: 13/13 vitest.
Local gates: `check-docs-governance` + `check-oss-boundary` pass. (Actions off → no CI; merge via local gates.)

## Tasks Remaining (the 3 follow-ups — all blocked on a decision or external dep)
1. **FINDING-7 — start-process / bpm-event E2E**: OSS SmartEngine BPM adapter is a stub
   (`processEngineService.startProcess` → "not implement intentionally"). Assembled-runtime fire needs a
   **non-stub (enterprise) BPM engine**. Executor itself is done + unit-tested + deployed.
2. **FINDING-5 — llm-call committed test → green**: don't depend on a real key. Cleanest = make the GA
   bootstrap seed the LLM provider with the stub sentinel `stub_key_for_no_llm_paths` (non-secret) instead
   of a demo key, OR `agent.llm.stub-mode=true` on the ga-e2e stack; then un-fixme. (Real keys ONLY in
   encrypted `ab_cloud_config` / env / secrets-manager — never seeds/git.)
3. **FINDING-4b — tighten the `toStates` filter**: root-caused (read-only) — `readCurrentState` uses the
   tenant-line-interceptor-enabled `selectByQuery`, and the `@Async` bridge has no MetaContext → the
   interceptor injects a wrong tenant predicate → null. Fix = set tenant from `event.getTenantId()` before
   the read (mirror `AutomationProcessRuntime.run`), or use `selectByQueryWithoutTenant`. **Needs a rebuild
   to verify** — deferred because disk was critically low (~5GB, drained by other sessions' stacks).

## Key Decisions
| Decision | Chosen | Rationale |
|---|---|---|
| execute-command principal (FINDING-3) | Restricted system principal + clear denial reason | Owner's call; prevents privilege escalation |
| state-change test filter | Empty from/to ("any transition") | NPE (real bug) fixed via null-strip; specific toStates filter blocked by FINDING-4b, documented not masked |
| start-process / bpm-event / llm-call E2E | Honest `test.fixme` skips | BPM stub (OSS) / real-key-not-CI-portable — not faked (§2.4) |
| llm-call real-key verification | One-time, runtime-encrypted, deleted+purged after | Prove the node works vs a real LLM without committing any secret |

## Files Changed (this branch)
### Backend (platform)
- `automation/executor/impl/CallApiExecutor.java` — FINDING-6: `method.toLowerCase()`
- `automation/listener/AutomationCommandEventBridge.java` — FINDING-4: best-effort `readCurrentState` for toState
- `automation/bpm/AutomationProcessRuntime.java` — FINDING-4: strip null SmartEngine variables (the real NPE fix)
- `automation/executor/impl/StartProcessActionExecutor.java` (new) + `…/StartProcessActionExecutorTest.java` (new)
### Frontend / tests (web-admin)
- `tests/e2e/automation/automation-golden.spec.ts` — un-fixme'd record-update/field-change/state-change/call-api/execute-command + added send-webhook/llm-call(fixme)/start-process(fixme) + fixed `updateOrder` helper
- `app/framework/smart/automation/nodes/__tests__/property-panel-render.test.tsx` (new) — Task 3.4
### Docs
- `docs/backlog/2026-06-05-automation-phase3-findings.md` — full findings (FINDING-1..7 + 4b)

## Pitfalls & Workarounds
1. **gradle in-container build looked "stale"** — first rebuild seemed not to deploy. Root cause: my bridge
   fix was *incomplete* (fromState could also be null), NOT a stale build — call-api fix DID deploy.
   **Lesson:** §15 — deploy-verify before concluding "stale"; the rebuild (down → up --rebuild) is reliable.
2. **admin login field is `email` not `username`** — `{"email":...,"password":...}`; wrong field → "Invalid
   username or password". storageState regen after a backend restart: `npx playwright test --project=auth --no-deps`.
3. **state_transition update precondition** — needs top-level `targetRecordId` (not `payload.pid`) or the
   snapshot for the field-operator precondition never loads (FINDING-2 root cause).

## Current State
- Git: clean. Branch `feat/automation-golden-back-coverage` @ `e21683503` (6 commits ahead of origin/main).
- **ga-e2e stack is DOWN + `--purge`d** (volumes wiped → next `up` needs a fresh bootstrap). Disk was ~5GB
  (other sessions). The committed backend image still has all fixes (rebuilt 2026-06-05).
- No secrets in the repo (verified: 0 occurrences of the minimax key in git).

## Next Steps (when picked up)
1. Review/merge PR #438 (owner).
2. (Optional) FINDING-4b: implement the tenant-context fix + verify with a `toStates:['cancelled']` test
   — **needs disk headroom for a rebuild first**.
3. (Optional) FINDING-5: switch the GA LLM seed to the stub sentinel, then un-fixme llm-call.
4. start-process / bpm-event E2E only viable on an enterprise (non-stub) BPM stack.

## Context for Next Session
- PR: #438 · branch `feat/automation-golden-back-coverage` · concurrency check: `git ls-remote origin '*automation*'`
- Run recipe + stack bring-up + the §15/§2.2 specifics: the findings doc + the prior handover
  `docs/handover/HANDOVER-2026-06-05-automation-designer-golden-e2e.md`.
- GA E2E stack: `scripts/docker-ga-e2e-up.sh` (no `--rebuild` reuses cached image+volume; but volume is
  purged now → also run `scripts/docker-ga-e2e-bootstrap.sh`). Ports backend 6444 / vite 5174 / BFF 3501 / pg 5433.
  Run env: `PLAYWRIGHT_BASE_URL=http://localhost:5174 BACKEND_URL=http://localhost:6444 BE_PORT=6444
  BFF_PORT=3501 PW_SKIP_WEBSERVER=1 PW_STORAGE_DIR=tests/storage/ga … --no-deps`.

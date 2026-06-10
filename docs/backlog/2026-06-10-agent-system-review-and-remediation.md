---
type: backlog
status: active
created: 2026-06-10
---

# Agent system review — verified findings & remediation tracker (2026-06-10)

Scope: full architecture/completeness review of the agent stack — `framework/conversation`
(turn chokepoint), `framework/agent` (362 files / ~56K LOC runtime), `framework/agentchat`
(group chat / handoff), `framework/ai` + `meta/ai` (AI application layer), enterprise ACP
contracts. Every finding below was **verified live** (file:line evidence); inherited claims
from earlier subagent reports that turned out to be already fixed are listed in §5 so the
next session does not re-litigate them.

Verdict: architecture direction ("governed business execution layer", capability contracts,
five-layer tool policy, L0-L4 risk ladder) is sound and ahead of typical agent frameworks.
Real gaps cluster in three areas: **LLM-backed features stuck at keyword/TODO stage**,
**collaboration completion signaling is poll-only** (protocol doc promises events), and
**structural debt** (god classes, inlined capability sync).

---

## 1. Verified functional gaps

### A1 — ChatBI has no LLM path (keyword fallback only) 🔴 P0

- Evidence: `platform/src/main/java/com/auraboot/framework/ai/chatbi/service/ChatBIService.java:88`
  — `// TODO: Integrate with ACP LLM provider when configured.` Question parsing is
  keyword-heuristic only; precision is capped and complex questions silently degrade.
- Fix: add an LLM parse path via `LlmProviderFactory` (same provider/config resolution as
  `AiSearchServiceImpl`), prompting the model to emit a structured query plan (JSON), with
  the existing keyword parser kept as explicit fallback when no provider is configured or
  the LLM call fails. Response must record which path produced the answer (`parseMode`).
- Acceptance: unit tests with mocked provider (happy / malformed-JSON / provider-absent
  fallback); existing keyword tests stay green; no behavior change when no provider configured.

### A2 — Capability eval LLM mode falls back to keyword 🔴 P0

- Evidence: `agent/service/CapabilityEvalService.java:298` — "LLM-based tool selection.
  Falls back to keyword mode until a full LLM [implementation]". The five-dimension eval
  framework (`capability-evaluation-framework.md`) therefore never exercises real tool
  selection; regression detection runs against a simulation.
- Fix: implement LLM-mode tool selection: present the eval case's question + candidate tool
  contracts to the configured provider, parse selected tool + args, score with the existing
  five-dimension scorer. Keyword mode remains the CI-fast default; LLM mode is opt-in
  (provider configured + explicit mode flag).
- Acceptance: unit tests with mocked provider covering selection parse, hallucinated-tool
  scoring, fallback on provider error; eval run rows persist `eval_mode` correctly.

### A3 — Collaboration completion is poll-only; protocol events missing 🟠 P1

- Evidence: `agent/service/AgentCollaborationService.java` (450 lines: DELEGATE/BROADCAST/
  PIPELINE all implemented, completion via `checkDelegationTimeouts()` scheduler +
  `pollAgentTaskStatus`/`pollWithBackoff` in `AgentBpmBridge`). The events promised by
  `agent-collaboration-protocol.md` §6 — `AgentTaskCompletedEvent` / `CHILD_TASK_COMPLETED` —
  have **zero grep hits** in the codebase. Consequences: completion latency = poll interval,
  scheduler scan cost, and BPM/parent-task reactions cannot be wired declaratively.
- Fix: introduce `AgentTaskCompletedEvent` (Spring application event) published at the task
  terminal-state transition chokepoint; add a listener that (a) advances parent-task
  aggregation via existing `checkDelegationComplete`/`aggregateChildResults`, (b) notifies
  the BPM bridge waiter. Polling stays as the catch-up safety net (no removal — consistent
  with the no-self-healing rule, events are a latency optimization, polls remain authority).
- Acceptance: IT proving a child-task completion publishes the event and the parent
  delegation completes without waiting for the next poll tick; timeout path unchanged.

### A4 — Context window management truncates but never summarizes 🟠 P1 (deferred — design needed)

- Evidence: `agent/service/ContextWindowManager.java` — budget allocation + oldest-first
  message trimming + memory-section truncation exist (docs claiming it is "planned" are
  stale, see C1). What does not exist: LLM summarization of evicted history, so long
  multi-turn sessions lose information silently.
- Why deferred: summarization needs a product decision (which model, cost ceiling, where
  summaries persist) and a live provider; out of scope for this remediation round.
- Tracker: design note to be added to `docs/standards/meta/` when picked up.

### A5 — L3 approval close-loop has no browser E2E 🟠 P1 (deferred — needs frontend stack)

- Evidence: e2e specs cover ACP CRUD/lifecycle/dashboard; no spec drives
  pending-approval card → user approves → command executes → state change assertion.
  This is the core promise of the AI-governance whitepaper §5.
- Why deferred: requires full web stack + golden run; schedule as its own session per
  §2.2 golden discipline.

### A6 — Zero live-LLM regression tests 🟡 P2 (deferred — needs key/budget decision)

- All agent tests mock the provider. Eval framework LLM mode (A2) is the prerequisite;
  once A2 lands, a small live smoke (cheap provider) can be wired as an opt-in suite.

---

## 2. Verified structural debt

### B1 — Capability sync inlined in `PluginImportServiceImpl` (3196 lines) 🟠 P1

- Evidence: `plugin/service/impl/PluginImportServiceImpl.java` = 3196 lines; the components
  the affordance docs name (`CapabilitySyncService`, `AgentToolAutoGenerator`) do not exist
  as classes — derivation logic is inlined, untestable in isolation, and emits no
  completion signal (`CapabilitySyncCompletedEvent` planned, absent).
- Fix (this round, minimal-risk slice): extract the capability-sync block into a dedicated
  `CapabilitySyncService` `@Service` with an explicit interface, delegation-preserving
  (no behavior change), plus unit tests for the extracted hash/upsert/derivation logic.
  Full `AgentToolAutoGenerator`/`Selector` extraction stays on the tracker.
- Acceptance: existing plugin-import ITs green; new unit tests for sync paths; line count
  of import impl drops accordingly.

### B2 — Six god classes ≥1000 lines (7099 total) 🟡 P2 (tracked, not this round)

- `CapabilityViewService` 1387 / `AgentRunController` 1277 / `ToolLoopService` 1206 (41
  injected deps) / `AgentRunService` 1092 / `StepLoopService` 1081 / `ChatTurnRuntime` 1056.
- Splitting these wholesale in one PR is regression-prone; do it opportunistically when
  each area is next touched. B1 is the first slice.

### B3 — `@RequirePermission` nearly absent in agent controllers 🟠 P1 (this round: audit + annotate)

- Evidence: 2 grep hits across the whole agent package; controllers rely on manual
  `MetaContext.getCurrentTenantId()` (tenant scoping, not permission enforcement).
- Fix: audit `AgentRunController` + agent-facing controllers; annotate mutating endpoints
  with `@RequirePermission` using codes per `permission-code-naming.md`; verify codes pass
  `validate-permission-codes.mjs`.
- Acceptance: every mutating agent endpoint carries an explicit permission; gate script green.

### B4 — `service ↔ runtime` package cycle smell 🟡 P2 (tracked)

- service→runtime.policy ~10 imports, runtime→service ~4. No compile cycle; flag for
  the B2 refactor round (runtime should be the dependency-free kernel).

---

## 3. Verified documentation drift

### C1 — Meta contract docs stale (last updated 2026-03-26) 🟠 P1

- `agent-affordance.md` §5 lists `ContextWindowManager` as *planned* — it is implemented.
- `agent-collaboration-protocol.md` §6 describes event-driven completion — implementation
  is poll-based (until A3 lands).
- Fix: after A3/B1 merge, update the enterprise meta docs in one alignment PR (enterprise
  repo) and add `acp-implementation-map.md` (contract → implementing class → table).

### C2 — Frontend SSE event enum drifted from `ResponseSink` 🟡 P2 (verify-then-fix)

- Backend `ResponseSink` exposes onTextChunk/onToolStart/onToolResult/onConfirmRequired/
  onThinking/onWarnings/onResultContract/onTurnBegin/onStreamEnd/onTurnCancelled; frontend
  `auraBotApi.ts` enumerates `'thinking'|'intent'|'preview'|'result'|...`. Map the actual
  wire event names before changing anything (the enum may be the wire protocol, not the
  sink methods).

---

## 4. Remediation waves (this session)

| Wave | Item | Status |
|------|------|--------|
| 1 | A1 ChatBI LLM path + fallback + tests | pending |
| 1 | A2 Eval LLM mode + tests | pending |
| 1 | A3 `AgentTaskCompletedEvent` + listener + IT | pending |
| 2 | B1 extract `CapabilitySyncService` | pending |
| 2 | B3 permission annotations + gate | pending |
| 3 | C1 doc alignment (enterprise PR) | pending |
| — | A4/A5/A6/B2/B4/C2 | deferred, tracked above with owners/preconditions |

## 5. Claims from earlier reviews verified as ALREADY FIXED (do not re-open)

| Claim | Reality (verified 2026-06-10) |
|-------|-------------------------------|
| `extractMemoriesViaLlm` accepts unvalidated `memory_type` | Whitelist exists — `RunLifecycleService.java:324-357` ("See deep-review P1-1") |
| `NlModelingService.sessionHistory` unbounded ConcurrentHashMap | Caffeine `maximumSize(1000)` + `expireAfterAccess(2h)` — `NlModelingService.java:52-55` |
| `MemoryL1L2OrphanScanner` poison-pill aborts tick | Per-row try/catch + continue — `MemoryL1L2OrphanScanner.java:197-219` |
| Hallucination counter transient (JVM only) | Persisted in `ab_agent_run.hallucination_count` — `ToolLoopService.java:1141-1152` |
| Collaboration protocol "paper only" | `AgentCollaborationService` (450 lines) + `AgentBpmBridge` implemented; only the *events* are missing (A3) |
| `StreamErrorClassifier` unused | Used — `BroadcastResponseSink.java:171` |
| Group-chat path bypasses turn chokepoint | It does not — `AgentReplyTask.java:227` calls `turnService.runTurn` |
| No tool-count limit / no selection mechanism | `ToolDiscoveryPort.discoverTools(..., maxTools)` with default 20 — `AgentRunService.java:304-306` |

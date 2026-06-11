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

Session retro (process lessons, root-cause analysis of the falsified findings, fixation
targets): [2026-06-11-agent-system-remediation-session-retro.md](./2026-06-11-agent-system-remediation-session-retro.md).

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

### B1 — ~~Capability sync inlined in `PluginImportServiceImpl`~~ WITHDRAWN: already extracted

- Live verification (2026-06-11): `PluginImportServiceImpl` (3196 lines) contains **no**
  capability-sync logic (single grep hit, a comment). Sync lives in
  `CapabilityViewService.syncCapabilities()` (write-path materialization to
  `ab_capability`) and is event-triggered via `CapabilitySyncListener` — the
  affordance doc's "inlined in PluginImportServiceImpl / CapabilitySyncService
  尚未独立抽取" claims are stale (→ C1). The remaining concern is only that
  `CapabilityViewService` mixes write/read/graph paths — tracked under B2.

### B2 — Six god classes ≥1000 lines 🟡 (2 of 6 split 2026-06-11; rest deferred with rationale)

- **Done (r2 branch)**: `CapabilityViewService` 1387→670 (extracted `CapabilitySyncService`
  553 + `CapabilityGraphService` 146 + shared `CapabilityMappingSupport` 134; public API
  kept as thin delegation, callers untouched); `AgentRunController` 1280→541 (extracted
  `AgentRunAuditController` 304 + `AgentRunOpsController` 245 + `AgentRunQuerySupport` 340;
  URLs and class-level `ACP_AGENT_RUN_ADMIN` preserved endpoint-for-endpoint).
- **Deferred**: `ToolLoopService` 1206 (41 injected deps) / `AgentRunService` 1092 /
  `StepLoopService` 1081 / `ChatTurnRuntime` 1056 — these four form the deeply coupled
  agent-loop core (service↔runtime, B4); splitting them safely needs a behavioral test
  harness around the tool loop first. Split opportunistically when next touched.

### B3 — `@RequirePermission` nearly absent in agent controllers 🟠 P1 (partial this round)

- Evidence: 2 grep hits across the whole agent package (`AgentRuntimeController`
  approve/reject with `ACP_AGENT_APPROVAL`); 16 controllers in `agent/controller/`
  rely on manual `MetaContext` tenant scoping only. `MetaPermission` has **no**
  `acp.agent.run/manage`-style codes to annotate with.
- This round (zero-new-codes slice): legacy ChatBI `/api/ai/chat-bi/query` now requires
  `META_CHATBI_USE` (same code as ChatBI v2 — consistent, already seeded).
- Remainder: agent-run/memory/profile admin endpoints need NEW `acp.agent.*` permission
  codes + bootstrap seeds + role grants — this must ride the active permission-governance
  (RBAC) project rather than be invented ad-hoc here, otherwise existing roles 403.
  Owner: permission-governance tracker (`perm-gov-ent/docs/backlog/2026-06-10-permission-governance-gap.md`).

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

### C2 — Frontend SSE event enum drifted from `ResponseSink` — DONE 2026-06-11

- Verified: real wire events (SseResponseSink) = chunk/thinking/tool_start/tool_result/
  result_contract/warning/confirm_required/done/error; the live parser `processSSEStream`
  was already aligned. The drifted enum belonged to a dead path: `auraBotApi.chat()`
  posted to the `/chat` stub (backend replies "Use /chat/stream") and parsed a
  {type,data} envelope with events the backend never emits — zero callers.
- Fix: dead path + legacy types removed; `AuraBotSseEventName` exported as the wire enum.

---

## 4. Remediation waves (this session)

| Wave | Item | Status |
|------|------|--------|
| 1 | A1 ChatBI LLM path + fallback + tests | **done** — `ChatBiLlmParser` + parseMode + parameterized filters, 15 unit tests green |
| 1 | A2 Eval LLM mode + tests | **done** — `LlmToolSelectionService`, truthful mode degradation, hallucination scoring, 10 unit tests green |
| 1 | A3 task-completion events + event-driven waits | **done** — `AgentTaskCompletedEvent` at all 4 terminal transitions, `TaskJoinService` latch, both delegation wait loops upgraded, 8 tests green |
| 2 | B1 extract `CapabilitySyncService` | **withdrawn** — already extracted (see B1); split of `CapabilityViewService` stays in B2 |
| 2 | B2 god-class splits | **2 of 6 done** (r2) — CapabilityViewService + AgentRunController; loop core deferred (see B2) |
| 2 | B3 permission annotations | **done** — ChatBI `META_CHATBI_USE` (r1) + five `acp.*` codes registered & annotated across agent controllers (r2); user-facing flows intentionally ungated |
| 3 | C1 doc alignment (enterprise PR) | **done** — affordance/collaboration docs updated + `acp-implementation-map.md` added |
| 3 | C2 SSE enum reconciliation | **done** (r2) — dead `/chat` path + legacy enum removed, `AuraBotSseEventName` = wire protocol |
| — | A4/A5/A6/B4 + B2 loop-core | deferred, tracked above with owners/preconditions |

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

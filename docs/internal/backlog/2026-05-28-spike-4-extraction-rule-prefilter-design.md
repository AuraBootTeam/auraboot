# Spike-4: LLM extraction rule pre-filter — design

> **Status**: Phase 1 design + harness skeleton. Phase 2 (offline replay against real run history) is follow-up.
> **Driver**: [`auraboot-enterprise/docs/plans/2026-05/2026-05-27-aurabot-memory-knowledge-assessment-and-plan.md`](../../../auraboot-enterprise/docs/plans/2026-05/2026-05-27-aurabot-memory-knowledge-assessment-and-plan.md) §4 Spike-4
>
> **Strict scope**: design + tooling only. No production code changes. Spike-4 phase 2 produces data; DDR-E owner decides whether to wire the rule-prefilter into runtime.

---

## §1 What problem this addresses

`RunLifecycleService.extractMemoriesViaLlm` (lines 249-310) runs after every agent run. For each run, it:

1. Takes `result.lastResponse` (truncated to 3000 chars)
2. Sends to an LLM with a fixed extraction prompt asking for FACT / LESSON / PREFERENCE / DECISION
3. Parses JSON, persists rows

For many runs, this is **wasted LLM cost** — the structured signal is already obvious from the run inputs:

- Tool calls like `record_user_preference(department='engineering')` → trivially a PREFERENCE
- BPM `task_assigned(user=X)` events → trivially a FACT(assignment)
- Object creation responses `{"success":true,"data":{"recordId":"..."}}` → trivially a FACT(created)
- Status transitions `state_change(from=draft to=published)` → trivially a FACT(transition)

DDR-E asks: *"Can a deterministic rule pre-filter catch these obvious cases without LLM?"*

## §2 Hypothesis

**Conservative claim** (to be measured, not asserted): a rule-prefilter catching common deterministic patterns can cover **30-60%** of extraction-worthy events at near-zero cost, leaving the LLM to handle only ambiguous narrative content.

Caveat: this number is hypothesis-not-data. Spike-4 phase 2 measures actual coverage.

## §3 Rule pre-filter design

### 3.1 Pattern catalog (initial)

Each pattern is `(signal, extracted_memory)` deterministic mapping:

| Signal source | Pattern | Extracted memory |
|---------------|---------|-------------------|
| Tool call `record_user_preference` or similar prefix | Any | `PREFERENCE` with title=`User preference: {field}`, content=`{value}`, importance=5 |
| Tool call response `{"success":true,"data":{"recordId":"..."}}` | Recordable entity created | `FACT` title=`Created {entity_type} {id}`, importance=3 |
| BPM event `task_completed` / `task_failed` | Any | `LESSON` title=`Task {result}: {title}`, content=outcome reason, importance=4 |
| BPM event `task_assigned` | Any | `FACT` title=`Assigned: {who} → {task}`, importance=3 |
| Tool call `update_status` / state transition | Any | `FACT` title=`State: {entity} → {new_state}`, importance=2 |
| Tool call response with `{"success":false}` | Any error | `LESSON` title=`Failed: {operation}`, content=`{error}`, importance=4 |
| Approval `approved` / `rejected` decision | Any | `DECISION` title=`{operation} {decision}`, content=`reason: {note}`, importance=6 |

### 3.2 Architecture(NOT production wiring — Spike-4 phase 2 is offline only)

```
                ┌──────────────────────────────────────┐
                │ RunLifecycleService.saveRunMemory()  │  (today: LLM call always)
                └────────────────┬─────────────────────┘
                                 │
                       ┌─────────▼──────────┐
                       │  rule-prefilter    │  (Spike-4 candidate)
                       │  scans result.bif  │
                       │       tool_calls   │
                       │       artifacts    │
                       └─────────┬──────────┘
                                 │
                  ┌──────────────┴──────────────┐
                  ▼                             ▼
        Rule-matched memories         ambiguous content
        (skip LLM, direct save)       (still call LLM)
```

Critical: **rule-prefilter is additive, not replacement**. LLM still runs for ambiguous content; rules just short-circuit the obvious cases.

### 3.3 What rule-prefilter CAN'T do

- Subjective preferences from narrative text ("user seems to prefer X")
- Cross-turn synthesis ("this is the 3rd time user has asked about Y")
- Lessons from open-ended response analysis
- Multi-hop reasoning from chained tool calls

These remain LLM territory. The point is to stop wasting LLM on the **explicit + structured** signals.

## §4 Phase 1 deliverables (this PR)

- [x] Design doc (this file)
- [x] Pattern catalog JSON: `platform/src/test/resources/extraction-prefilter/patterns.json`
- [x] Java DTOs: `ExtractionSignal` + `ExtractedMemoryCandidate` + `ExtractionDecision`
- [x] Pure-function rule matcher: `ExtractionRuleMatcher.match(toolCalls, response) → List<ExtractedMemoryCandidate>`
- [x] Unit tests for the matcher
- [x] `@Tag("extraction-prefilter")` JUnit harness with Phase 2 stub

## §5 Phase 2 deliverables (follow-up)

- [ ] Offline replay tool: read N=100 historical runs from `ab_agent_run` + `ab_agent_observation`, replay each through rule-prefilter only (don't actually call LLM)
- [ ] Compare against the historical LLM extraction output (stored as memory rows linked via `source_run_id`)
- [ ] Compute:
  - **Coverage**: % runs where rule-prefilter produces at least one memory
  - **Recall vs LLM**: among LLM-extracted memories, what % could a rule have caught
  - **Precision vs LLM**: among rule-matched memories, what % match an LLM-extracted one
  - **Savings**: extrapolated LLM call reduction × token cost × call volume
- [ ] Emit `replay-<ts>.json` + `report-<ts>.md` → enterprise `runtime-traces/extraction-prefilter/`
- [ ] DDR-E owner reviews + decides E1 (do nothing) / E2 (wire rule-prefilter into runtime)

## §6 Exit criteria

**Phase 1** (this PR):
- Design doc complete
- Pattern catalog covers ≥ 6 deterministic cases
- Java skeleton compiles + unit tests pass
- Phase 2 stub `@Disabled` with TODO

**Phase 2** (follow-up):
- ≥ 100 historical runs replayed
- Numeric answer to coverage / recall / precision
- Cost-savings extrapolation with explicit assumptions
- Recommendation memo (E1 vs E2) ready for DDR-E

## §7 Forbidden

- ❌ Modify `RunLifecycleService` / `extractMemoriesViaLlm` production code
- ❌ Add runtime hooks
- ❌ Reduce LLM extraction (Spike-4 only measures hypothesis; **doesn't** ship the optimization)
- ❌ Drop rule patterns without owner-recorded rationale
- ❌ Pattern catalog conflicts with `MemoryTier` semantics (verify with owner before adding patterns)

## §8 Related

- Same scaffold pattern as Spike-1 phase 1 (PR #297) and Spike-2 phase 1 (PR #300)
- LLM extraction target: `RunLifecycleService.extractMemoriesViaLlm` at line 249-310 (auraboot-enterprise fact-baseline §2.2 calibrated)
- Memory schema: `ab_agent_memory` (`schema.sql:5006-5045`)
- Observation schema (for tracking call volume): `ab_agent_observation` (`schema.sql:5049-5067`)
- DDR-E candidates: assessment-and-plan §3 DDR-E

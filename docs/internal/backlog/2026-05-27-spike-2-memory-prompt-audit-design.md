# Spike-2: Memory prompt 装配审计 — design

> **Status**: Phase 1 (audit tooling + annotation methodology). Phase 2 (run on production data + emit metrics report) is follow-up.
>
> **Driver**: [`auraboot-enterprise/docs/plans/2026-05/2026-05-27-aurabot-memory-knowledge-assessment-and-plan.md`](../../../auraboot-enterprise/docs/plans/2026-05/2026-05-27-aurabot-memory-knowledge-assessment-and-plan.md) §4 Spike-2
>
> **Strict scope**: data + report only. Forbidden: modify `ab_agent_memory` schema, modify `ActiveMemoryService` / `RunLifecycleService`, write Memory feature PR.

---

## §1 Why this audit

DDR-B asks: "is Memory 'compiled summary + timeline' dual-zone worth the schema cost?" To answer, three measurements are needed:

1. **矛盾召回率** — when `ActiveMemoryService.preRecall` returns N snippets, what fraction of (tenant, agent, user) sessions contain **mutually contradictory** entries in those N? If the rate is < 5%, the dual-zone is solving a non-problem.
2. **LLM extraction 调用量与 token 分布** — `RunLifecycleService.extractMemoriesViaLlm` runs after every run. What's the call volume per agent_code / memory_type? What's the token cost? If LLM extraction is < 1% of total LLM cost, optimizing it (DDR-E) has low ROI.
3. **`deduplicateMemories` hit rate** — does the existing dedupe path actually remove contradictions, or does it only handle exact duplicates? If exact-dedupe rarely fires, then "矛盾召回率" is real; if it fires often, current path is doing the job.

These three numbers gate DDR-B (timeline schema) and inform DDR-E (extraction rule pre-filter).

## §2 Measurement methodology

### 2.1 Sample selection (10 sessions for human annotation)

Read-only SQL pulls (tenant, agent, user) triples meeting:

- `ab_agent_memory` row count ≥ 3 for the triple
- `ab_agent_memory_access_log` row exists in the last 30 days (proves preRecall was used)
- Sample uniformly across `memory_type` distribution

For each sampled triple:
- Simulate what `ActiveMemoryService.preRecall` would emit:
  - Pull `memoryService.loadScopedByImportance` top-MAX_SNIPPETS
  - Pull `memoryService.searchScoped` (keyword hits)
  - De-dup by pid
- Output as `prompt-segments-<ts>.json` for human annotation

### 2.2 Annotation taxonomy

Human reviewer tags each sampled triple's snippet bundle with one of:

| Tag | Definition |
|-----|------------|
| `no-conflict` | All snippets are internally consistent or about distinct aspects |
| `temporal-conflict` | Two snippets state the same field with different values, where the later one supersedes (e.g. "prefers SF Express" then "prefers JD Logistics") — the **canonical Compiled Truth + Timeline use case** |
| `factual-conflict` | Two snippets directly contradict and **neither is dated** — LLM cannot tell which is right |
| `granularity-conflict` | One snippet is general ("user likes Logistics") and another is specific ("user likes SF Express") — not contradictory but causes prompt repetition |
| `unclear` | Annotator cannot tell — escalate to second reviewer |

Annotation goes in `annotations-<ts>.json` per the JSON schema in `annotation.schema.json`.

### 2.3 LLM extraction call volume

Query `ab_agent_observation` for `observation_type='memory_saved'` rows. Source = `RunLifecycleService.saveRunMemory` (post-LLM extraction; fires per run regardless of LLM success/failure). Bucket by `obs_agent_id`. Token estimate: from `detail` JSON if recorded, else inferred from extraction prompt template (~150 tokens system + ~3000 input cap + ~1000 output cap → upper bound).

### 2.4 Deduplicate hit rate proxy

`MemoryService.deduplicateMemories` is called immediately after extraction. No metric is emitted today (gap to file as observation). Proxy: count rows added per run vs distinct `memory_content` (after extraction normalize) — high overlap = dedupe likely active.

## §3 Phase 1 deliverables (this PR)

- [x] Design doc (this file)
- [x] SQL templates: `audit-queries.sql` — read-only, parameterized for tenant_id / agent_code / time window
- [x] JSON Schema for annotations: `annotation.schema.json`
- [x] Java DTOs: `PromptSegmentSample` / `ConflictAnnotation`
- [x] Pure-function `ConflictMetrics` (rate calculator)
- [x] Unit tests for `ConflictMetrics`
- [x] `@Tag("memory-audit")` JUnit harness with phase 2 stub
- [x] README runbook

## §4 Phase 2 deliverables (follow-up PR)

- [ ] Run audit against staging-like PG fixture (or real anonymized snapshot)
- [ ] Emit `prompt-segments-<ts>.json` with ≥ 10 samples
- [ ] Human review pass — fill `annotations-<ts>.json`
- [ ] Emit `extraction-volume-<ts>.json` (histogram + token estimate)
- [ ] Emit `report-<ts>.md` answering the 3 measurement questions
- [ ] Data lands in `auraboot-enterprise/docs/system-reference/runtime-traces/memory-audit/`
- [ ] Report feeds DDR-B + (possibly) DDR-E

## §5 Exit criteria

**Phase 1** (this PR):
- Design doc complete
- SQL templates parameterized + lint-clean
- JSON schema validates a sample annotation
- Java skeleton compiles + ConflictMetrics tests green

**Phase 2** (follow-up):
- ≥ 10 annotated samples
- 3 measurement questions have numeric answers
- Report explicit states the recommendation: B1 (no schema change) / B2 (add dual-zone) / B3 (status quo)

## §6 Forbidden

- ❌ Modify `ab_agent_memory` schema
- ❌ Modify `ActiveMemoryService` / `MemoryService` / `RunLifecycleService`
- ❌ Write any memory-feature PR (those are DDR-B follow-up)
- ❌ Run audit against production PG without anonymization
- ❌ Annotate samples without grounding in the snippet bundle

## §7 Privacy / safety

Sample snippets contain real `memory_content` from user sessions. Phase 2 runs must:

- Use staging snapshot OR anonymize PII via `MetaContext` shadow filter
- Annotations stored in `auraboot-enterprise` enterprise repo (gated access)
- No raw `memory_content` quoted in public docs / reports — only counts + taxonomy distributions

## §8 Related

- Driver: §4 Spike-2 in assessment-and-plan
- Memory schema: `ab_agent_memory` at `schema.sql:5006-5045` (see fact-baseline §2.2)
- Prompt assembly entry: `ActiveMemoryService.preRecall`
- LLM extraction entry: `RunLifecycleService.extractMemoriesViaLlm` (line 249, 271)
- Pure-scorer contract not to violate: `MemoryTierEvaluator` Javadoc line 30-38
- Same pattern as Spike-1: see `2026-05-27-rag-d7-eval-harness-design.md`

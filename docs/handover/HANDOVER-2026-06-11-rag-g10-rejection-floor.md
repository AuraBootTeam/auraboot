---
type: handover
status: closed
created: 2026-06-11
owner: platform
topic: RAG G10 relevance-rejection floor (no-answer behavior) — implemented, calibrated, merged
distilled_to: docs/backlog/2026-06-11-rag-g10-rejection-floor.md
---

# Session Handover — 2026-06-11 — RAG G10 rejection floor

## Session Summary

Implemented the **G10** gap from the RAG Phase-2 eval (no-answer behavior): a
query-level relevance-rejection floor on both retrieval paths so off-topic
queries return empty instead of chunks sharing one incidental term. Thresholds
were **calibrated from a real-stack eval run** (not guessed). Shipped to main as
**OSS #563** (squash `d7aa2b6dd`); branch + worktree closed `MERGED_AND_DELETED`.

## Tasks Completed

- [x] Instrumented `RagEvaluationPhase2IT` to emit per-query score signals
      (Path A keyword coverage / vector similarity, Path B D7 match score) + a
      no-answer calibration section, so thresholds come from data.
- [x] Ran the eval (keyword-fallback, 272-doc corpus) → captured true-hit vs
      neither score distributions; built the precision/recall trade-off curve.
- [x] Implemented the floor: `RagRetrievalService` + new `RagRetrievalProperties`
      (Path A), `D7CompiledKnowledgeService` + `aurabot.d7.min-match-score`
      (Path B), new `KeywordCoverage` util, metric `rag.retrieval.rejection_floor_dropped`.
- [x] Deterministic tests (default suite): `KeywordCoverageTest`, floor cases in
      `RagRetrievalServiceBranchTest` + `D7CompiledKnowledgeServiceTest`.
- [x] Re-ran the full rag package (real PG host stack): **226 tests / 0 fail**;
      eval re-run confirmed **no-answer 0 → 3/10, Path A recall@5 0.600 / Path B
      0.985 preserved** (zero per-path regression).
- [x] Docs: tracker §8 + `docs/backlog/2026-06-11-rag-g10-rejection-floor.md`
      (with the curve); memory + ARCHIVE updated.
- [x] PR #563 opened, head-ref verified == commit oid, merged `--admin --squash`,
      branch/worktree removed.

## Tasks In Progress

None — this line is closed. The remaining no-answer rejection is gated on an
owner item (below), not on code.

## Key Decisions

| Decision | Chosen | Rationale | Alternatives |
|----------|--------|-----------|--------------|
| Floor signal | Keyword **coverage** (normalized [0,1]) + vector **similarity** | `ts_rank_cd` is unbounded/length-dependent → fragile absolute cutoff | absolute BM25 rank |
| Floor scope | **Query-level** best-match gate (drop whole set iff no chunk clears) | per-chunk filtering dropped the relevant low-coverage chunk while a wrong higher-coverage one survived — measured Path-A recall 0.600→0.300 | per-chunk filter |
| Mode handling | hybrid → vector sim; keyword-fallback → coverage (per chunk's `distance<1.0`) | each mode's authoritative signal; vector leg is the real unlock for vocabulary-overlap off-topic | single fixed signal |
| Thresholds | kFloorA 0.27 / minScoreB 0.15 / vFloor 0.20 (default-on) | calibrated for **zero per-path recall regression** on the 52-query golden set | aggressive (more rejection, real recall loss) |
| Path B floor target | **pre-penalty** coverage | the stale-page ×0.25 penalty is ranking-only; flooring penalized score would cut relevant stale pages | floor on penalized score |
| Coverage query | **original** user query, not synonym-expanded | expansion adds terms → inflates denominator → would dilute coverage | expanded query |

## Files Changed (all in #563 `d7aa2b6dd`, merged)

### Backend (production)
- `platform/.../rag/util/KeywordCoverage.java` — **new**; normalized coverage via `CjkBigramSegmenter.tsQueryTerms` on both sides.
- `platform/.../rag/config/RagRetrievalProperties.java` — **new**; `aurabot.rag.{rejection-floor-enabled,min-keyword-coverage,min-vector-similarity}`.
- `platform/.../rag/service/RagRetrievalService.java` — `applyRejectionFloor` (query-level) after rerank; injects props.
- `platform/.../rag/service/RagRetrievalMetrics.java` — `recordRejectionFloor`.
- `platform/.../rag/d7/D7CompiledKnowledgeService.java` — query-level gate on pre-penalty `rawCoverage`; extracted helper.
- `platform/.../rag/d7/D7KnowledgeProperties.java` — `minMatchScore` (default 0.15).

### Tests
- `platform/.../rag/util/KeywordCoverageTest.java` — **new** (7).
- `platform/.../rag/service/RagRetrievalServiceBranchTest.java` — floor cases (keyword/hybrid/disabled, query-level) + constructor now takes props.
- `platform/.../rag/d7/D7CompiledKnowledgeServiceTest.java` — G10-01/02/03; ranking test pins `minMatchScore=0` to isolate from the floor.
- `platform/.../rag/eval/RagEvaluationPhase2IT.java` — score signals + calibration report section.

### Docs
- `docs/backlog/2026-06-11-rag-g10-rejection-floor.md` — **new** (mechanism + curve + keyword-mode ceiling).
- `docs/backlog/2026-06-10-rag-system-review-and-gap-tracker.md` — §8 G10 DONE entry.

## Pitfalls & Workarounds

1. **Per-chunk floor regressed recall (caught only by the real-stack eval)**
   - Root cause: filtering each chunk below the floor dropped the relevant
     low-coverage chunk whenever a different (wrong) chunk scored higher →
     returned the wrong chunk, cut the right one. Offline I had modelled survival
     with *max*-coverage (= query-level), so the prediction (zero regression) was
     over-optimistic vs the per-chunk implementation (Path-A recall 0.600→0.300).
   - Solution: query-level best-match gate ("is *any* match good enough"). The
     eval is the only thing that surfaced it — unit tests passed both ways.
   - Prevention: a no-answer floor is answerable-detection, not per-chunk reranking.

2. **Keyword-mode cannot reject vocabulary-overlap off-topic without recall loss**
   - 7/10 neither queries (Salesforce 同步 / GraphQL 接口 / 炒菜步骤 …) share
     high-frequency platform vocab (接口/同步/API); their keyword signal overlaps
     genuine low-overlap hits. The curve shows 6/10 rejection costs a third of
     Path-A recall. This is a keyword-retrieval limit, not a tuning miss — the
     vector leg (owner's embedding-key item) separates them semantically.

3. **check-docs-governance has 2 pre-existing errors** (`HANDOVER-2026-06-11-rag-endgame-closed.md`, `2026-06-11-rag-endgame-session-deep-retro.md`, from #552) — not touched here; this PR's new doc passes governance.

## Lessons Learned

- Calibrate retrieval thresholds from a measured score distribution, never a guess
  — and re-measure on the **real stack** after implementing, because the unit-level
  model (max-signal survival) silently differed from the per-chunk code.
- Distinguish "no-answer gate" (best-match, all-or-nothing) from "reranking"
  (per-item filtering); conflating them drops relevant low-score items.

## Current State

### Git
- `origin/main` has #563 (`d7aa2b6dd`) in history; canonical `/Users/ghj/work/auraboot/auraboot` on `main`, clean.
- Branch `feat/rag-g10-rejection-floor` + its worktree: removed (local + remote).

### Running Services
- None left running. Eval used the **host** Postgres 17.6 (`localhost:5432/aura_boot`, user `ghj`) + pgvector + Redis 6379 (integration-test profile). Non-destructive (`@Transactional @Rollback`).

### Database State
- No schema/migration changes in this PR.

## Next Steps (all owner-gated, none code-blocking)

1. **Owner**: configure the `ab_cloud_config` embedding key → rerun
   `RagEvaluationPhase2IT` in `live` mode (env `RAG_EVAL_DOCS_PATH` /
   `RAG_EVAL_D7_PAGES_PATH`) → **recalibrate `aurabot.rag.min-vector-similarity`**
   from the live hit/neither similarity distributions. This unlocks the remaining
   7/10 vocabulary-overlap no-answer rejections **without code changes** (the floor
   already consults vector similarity).
2. Optional: tune `min-keyword-coverage` / `min-match-score` up to trade recall
   for more keyword-mode rejection (see the curve in the backlog doc).

## Context for Next Session

- Authoritative status: `docs/backlog/2026-06-10-rag-system-review-and-gap-tracker.md` §8 + `docs/backlog/2026-06-11-rag-g10-rejection-floor.md`.
- Reproduce the eval (host-first, no docker): `cd platform && RAG_EVAL_DOCS_PATH=/abs/.../auraboot-enterprise/docs/system-reference RAG_EVAL_D7_PAGES_PATH=/abs/.../compiled-knowledge/pages ./gradlew :test --tests '*RagEvaluationPhase2IT*'` (host PG must be up; report → `platform/build/rag-eval-output*/`).
- Floor tunables: `aurabot.rag.*` (Path A) + `aurabot.d7.min-match-score` (Path B); disable via `rejection-floor-enabled=false` / `min-match-score=0`.
- Concurrency check before any RAG follow-up: `git ls-remote --heads origin '*rag*'`.

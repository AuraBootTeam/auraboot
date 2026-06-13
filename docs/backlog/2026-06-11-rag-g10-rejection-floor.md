---
type: backlog
status: active
created: 2026-06-11
owner: platform
topic: RAG G10 — relevance-rejection floor (no-answer behavior) implementation + calibration
---

# RAG G10 — relevance-rejection floor (2026-06-11)

Closes the G10 gap opened by the Phase-2 evaluation
(`docs/backlog/2026-06-11-rag-eval-phase2-first-run.md`): every off-topic
(`expected_path=neither`) query retrieved *something* — keyword `tsquery @@ tsv`
OR-matching plus D7 partial-term scoring return any item sharing a single
incidental term, so the correct-rejection rate was **0/10**. The chat layer
could not trust a "no relevant knowledge" signal.

## What landed

A **query-level no-answer gate** on both retrieval paths, config-driven and
default-on:

- **Path A** (`RagRetrievalService`, new `RagRetrievalProperties` `aurabot.rag.*`):
  after rerank, if *no* returned chunk clears the relevance bar the whole result
  set is dropped (empty); otherwise the top-k is returned unchanged. Per chunk
  the mode-appropriate signal gates:
  - hybrid mode (embedding present, `distance < 1.0`) → vector similarity ≥
    `minVectorSimilarity` (default 0.20 = `1 − 0.8` cosine gate);
  - keyword-fallback mode (no embedding key, `distance == 1.0`) → keyword
    coverage ≥ `minKeywordCoverage` (default 0.27), computed with
    `KeywordCoverage` over the original (non-expanded) query.
- **Path B** (`D7CompiledKnowledgeService`, `aurabot.d7.min-match-score`, default
  0.15): if the best-covered compiled page is below the floor, return empty.
  Gated on **pre-penalty** coverage so a genuinely relevant stale page (score
  ×0.25) is not unfairly cut.

Metric `rag.retrieval.rejection_floor_dropped` (G6 observability parity).

## Why query-level, not per-chunk (measured)

A first implementation filtered chunks individually. The real-stack eval showed
that **regressed Path-A recall 0.600 → 0.300**: a relevant-but-low-coverage chunk
was dropped whenever a different (wrong) chunk scored higher, returning the wrong
chunk and cutting the right one. A query-level best-match gate ("is *any* match
good enough") holds recall at 0.600 while rejecting the same off-topic queries.
The floor's job is no-answer detection, not reranking.

## Calibration (52-query golden set, keyword-fallback mode)

Thresholds chosen so **no answerable true hit is dropped** (zero per-path recall
regression). Lowest answerable true-hit signal sat at Path A coverage 0.273 /
Path B score 0.167; highest off-topic below it at 0.250 / 0.143.

Precision/recall trade-off (shipped row **measured** on the floor-on eval re-run;
the rest **modeled** from the first-run best-match signal distributions, the same
query-level survival the floor applies):

| kFloorA | minScoreB | correct-reject | Path A recall | Path B recall |
|---|---|---|---|---|
| **0.27** | **0.15** | **3/10** | **0.600** | **0.985** |  ← shipped (measured, zero regression) |
| 0.34 | 0.26 | 6/10 | 0.400 | 0.864 | (modeled) |
| 0.40 | 0.30 | 7/10 | 0.400 | 0.833 | (modeled) |
| 0.51 | 0.34 | 9/10 | 0.300 | 0.712 | (modeled) |
| 0.67 | 0.40 | 10/10 | 0.200 | 0.712 | (modeled) |

Measured result with the shipped defaults (eval re-run, floor on): **correct-rejection
0 → 3/10**, **no-answer recall 0.300**, **Path A recall@5 0.600 / Path B 0.985
preserved**. The 3 rejected are the queries with low lexical overlap on both paths
(区块链存证 / 如何配置人脸识别登录 / 微信小程序直播带货). Two queries flagged
"wrong rejection" (zh-long-005 / mix-medium-005) were already recall@5 *misses*
before the floor (they retrieved the wrong doc); the floor turns those into clean
empties rather than feeding the LLM irrelevant context — recall@5 is unchanged.

## The keyword-mode ceiling — and why the vector leg is the real unlock

The remaining 7 off-topic queries (Salesforce 双向同步 / GraphQL 接口 / 炒菜步骤 /
best pasta recipe / video live streaming / OCR contract translation / 天气预报 API)
survive because they share **high-frequency platform vocabulary** (接口/同步/支持/
API) with the corpus. Their keyword signals are indistinguishable from genuine
conceptual hits that also match few literal terms — the curve shows that pushing
the floor up to reject them costs real recall steeply (3/10→6/10 rejection drops
Path A recall by a third).

This is a fundamental limit of keyword-only retrieval, not a tuning miss. The
**vector leg separates these semantically** (an off-topic query is far from every
chunk regardless of shared words), so it can reject the vocabulary-overlap set
without the lexical recall cost. The floor is already wired to use vector
similarity (`minVectorSimilarity`, default 0.20, inert until an embedding key is
configured). When the owner backfills the embedding key (already tracked in §8)
and reruns `RagEvaluationPhase2IT` in `live` mode, recalibrate `minVectorSimilarity`
from the live-mode hit/neither similarity distributions and the rest of the
no-answer rejection follows without code changes.

## Regression net

- Default-suite (no corpus): `KeywordCoverageTest`, `RagRetrievalServiceBranchTest`
  (keyword/hybrid/disabled floor cases, query-level), `D7CompiledKnowledgeServiceTest`
  (G10-01/02/03) — the floor *logic* gate.
- Env-gated empirical: `RagEvaluationPhase2IT` now emits the no-answer matrix +
  rejection-floor calibration signals; rerun on the live embedding key to measure
  the vector-leg lift.

## Tuning

- Disable entirely: `aurabot.rag.rejection-floor-enabled=false` (Path A) and
  `aurabot.d7.min-match-score=0` (Path B).
- Trade recall for more rejection: raise `minKeywordCoverage` / `min-match-score`
  per the curve above.

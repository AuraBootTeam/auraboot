---
type: backlog
status: active
created: 2026-06-11
owner: platform
topic: RAG golden-query Phase-2 evaluation harness — first live run report (keyword-fallback mode)
---

# RAG golden-query evaluation — Phase-2 first live run (2026-06-11)

First live measurement run of the Phase-2 evaluation harness
(`platform/src/test/java/com/auraboot/framework/rag/eval/RagEvaluationPhase2IT.java`)
against the 52-query golden set (`rag-eval/golden-queries.json` v0.2.0).

- **Corpus**: enterprise `docs/system-reference` (272 md files, Path A) + 15 compiled-knowledge pages (Path B)
- **Mode**: `keyword-fallback` — no embedding provider key configured (`ab_cloud_config` embedding rows disabled, no apiKey), so Path A ran on the BM25/keyword leg only. This is the expected baseline mode, not a failure.
- **This is a measurement, not a gate**: no recall thresholds asserted; harness asserts only that all 52 queries executed and artifacts were written/parseable.

## Key findings

1. **Path B (D7 compiled knowledge) is strong even keyword-only**: recall@5 = 0.985, MRR@10 = 0.909 across 33 scored queries; uniform across zh/en/mixed (CJK bigram matching in `D7CompiledKnowledgeService.terms` works as designed).
2. **Path A (KB keyword leg) is the weak path**: recall@5 = 0.600, MRR@10 = 0.454 over 10 scored queries. zh/mixed queries that paraphrase (no literal keyword overlap with the doc) miss entirely (`zh-medium-005`, `zh-long-005`, `mix-short-005`, `mix-medium-005`). Re-run with a live embedding key to quantify the hybrid-leg lift.
3. **No-answer behavior is the biggest gap**: 0/10 `expected_path=neither` queries were correctly rejected — keyword OR-matching plus D7 partial-term scoring return *something* for every off-topic query (e.g. 炒菜步骤). Neither path has a relevance floor / minimum-score cutoff for rejection.

## Reproduce

```bash
cd platform && \
  RAG_EVAL_DOCS_PATH=/abs/path/auraboot-enterprise/docs/system-reference \
  RAG_EVAL_D7_PAGES_PATH=/abs/path/auraboot-enterprise/docs/system-reference/compiled-knowledge/pages \
  ./gradlew :test --tests '*RagEvaluationPhase2IT*'
# artifacts → platform/build/rag-eval-output/ (override with RAG_EVAL_OUTPUT_DIR)
```

Without the env vars the test skips (assumption-based gate), so the default suite is unaffected.

---

## Run report (verbatim)

### RAG golden-query evaluation — Phase 2 run 20260610-235450

| Run metadata | Value |
|---|---|
| Golden set version | 0.2.0 |
| Queries executed | 52 |
| embeddingMode | keyword-fallback |
| Path A corpus | 272 md files (imported=272, updated=0, skipped=0, failed=0) |
| Import duration | 2s |
| Eval duration (52×2 retrievals) | 2s |
| topK / recall@K | 10 / 5 |

## Path A — KB retrieval (RagRetrievalService), recall@5 / MRR@10 by language

| language | queries scored | recall@5 (avg) | MRR@10 (avg) |
|---|---|---|---|
| zh | 5 | 0.600 | 0.629 |
| en | 1 | 1.000 | 0.500 |
| mixed | 4 | 0.500 | 0.223 |
| **all** | 10 | 0.600 | 0.454 |

## Path B — D7 compiled knowledge (D7CompiledKnowledgeService), recall@5 / MRR@10 by language

| language | queries scored | recall@5 (avg) | MRR@10 (avg) |
|---|---|---|---|
| zh | 12 | 1.000 | 0.833 |
| en | 10 | 0.950 | 0.900 |
| mixed | 11 | 1.000 | 1.000 |
| **all** | 33 | 0.985 | 0.909 |

## No-answer behavior (expected_path = neither)

| metric | value |
|---|---|
| neither queries | 10 |
| correctly rejected (both paths empty) | 0 |
| no-answer recall | 0.000 |
| no-answer precision | n/a |
| false positives (neither but retrieved) | zh-short-002 (A+B hit), en-short-002 (A hit), mix-medium-002 (A+B hit), zh-short-007 (A hit), zh-medium-007 (A+B hit), zh-long-006 (A+B hit), en-short-005 (A+B hit), en-long-004 (A+B hit), mix-short-006 (A hit), mix-medium-007 (A+B hit) |
| wrong rejections (expected hits but both empty) | none |

## Per-query results

| id | lang | expected_path | A recall@5 | A rr@10 | B recall@5 | B rr@10 | no-answer |
|---|---|---|---|---|---|---|---|
| zh-short-001 | zh | B | — | — | 1.000 | 1.000 | — |
| zh-medium-001 | zh | B | — | — | 1.000 | 1.000 | — |
| zh-long-001 | zh | B | — | — | 1.000 | 0.333 | — |
| zh-short-002 | zh | neither | — | — | — | — | false-positive |
| zh-medium-002 | zh | B | — | — | 1.000 | 0.333 | — |
| en-short-001 | en | B | — | — | 1.000 | 1.000 | — |
| en-medium-001 | en | B | — | — | 1.000 | 1.000 | — |
| en-long-001 | en | B | — | — | 1.000 | 1.000 | — |
| en-short-002 | en | neither | — | — | — | — | false-positive |
| en-medium-002 | en | B | — | — | 1.000 | 1.000 | — |
| mix-short-001 | mixed | B | — | — | 1.000 | 1.000 | — |
| mix-medium-001 | mixed | B | — | — | 1.000 | 1.000 | — |
| mix-long-001 | mixed | both | — | — | 1.000 | 1.000 | — |
| mix-short-002 | mixed | B | — | — | 1.000 | 1.000 | — |
| mix-medium-002 | mixed | neither | — | — | — | — | false-positive |
| zh-short-003 | zh | B | — | — | 1.000 | 1.000 | — |
| zh-short-004 | zh | B | — | — | 1.000 | 1.000 | — |
| zh-short-005 | zh | A | 1.000 | 1.000 | — | — | — |
| zh-short-006 | zh | A | 1.000 | 1.000 | — | — | — |
| zh-short-007 | zh | neither | — | — | — | — | false-positive |
| zh-medium-003 | zh | B | — | — | 1.000 | 1.000 | — |
| zh-medium-004 | zh | B | — | — | 1.000 | 1.000 | — |
| zh-medium-005 | zh | A | 0.000 | 0.000 | — | — | — |
| zh-medium-006 | zh | A | 1.000 | 1.000 | — | — | — |
| zh-medium-007 | zh | neither | — | — | — | — | false-positive |
| zh-medium-008 | zh | B | — | — | 1.000 | 0.333 | — |
| zh-long-002 | zh | B | — | — | 1.000 | 1.000 | — |
| zh-long-003 | zh | B | — | — | 1.000 | 1.000 | — |
| zh-long-004 | zh | B | — | — | 1.000 | 1.000 | — |
| zh-long-005 | zh | A | 0.000 | 0.143 | — | — | — |
| zh-long-006 | zh | neither | — | — | — | — | false-positive |
| en-short-003 | en | B | — | — | 1.000 | 0.500 | — |
| en-short-004 | en | B | — | — | 1.000 | 1.000 | — |
| en-short-005 | en | neither | — | — | — | — | false-positive |
| en-medium-003 | en | B | — | — | 1.000 | 1.000 | — |
| en-medium-004 | en | A | 1.000 | 0.500 | — | — | — |
| en-medium-005 | en | B | — | — | 1.000 | 1.000 | — |
| en-long-002 | en | B | — | — | 0.500 | 1.000 | — |
| en-long-003 | en | B | — | — | 1.000 | 0.500 | — |
| en-long-004 | en | neither | — | — | — | — | false-positive |
| mix-short-003 | mixed | B | — | — | 1.000 | 1.000 | — |
| mix-short-004 | mixed | B | — | — | 1.000 | 1.000 | — |
| mix-short-005 | mixed | A | 0.000 | 0.143 | — | — | — |
| mix-short-006 | mixed | neither | — | — | — | — | false-positive |
| mix-medium-003 | mixed | B | — | — | 1.000 | 1.000 | — |
| mix-medium-004 | mixed | B | — | — | 1.000 | 1.000 | — |
| mix-medium-005 | mixed | A | 0.000 | 0.000 | — | — | — |
| mix-medium-006 | mixed | A | 1.000 | 0.500 | — | — | — |
| mix-medium-007 | mixed | neither | — | — | — | — | false-positive |
| mix-long-002 | mixed | both | 1.000 | 0.250 | 1.000 | 1.000 | — |
| mix-long-003 | mixed | B | — | — | 1.000 | 1.000 | — |
| mix-long-004 | mixed | B | — | — | 1.000 | 1.000 | — |

Full ranked lists per query: `results-20260610-235450-path-a.json` / `results-20260610-235450-path-b.json`.

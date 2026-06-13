---
type: retro
status: active
created: 2026-06-11
owner: platform
topic: RAG G1-G9 remediation — pre-completion full review (aura-endgame P5R)
---

# RAG Gap Remediation — Pre-completion Review (2026-06-11)

Five-item verification per /aura-endgame before declaring completion.
Tracker: `docs/backlog/2026-06-10-rag-system-review-and-gap-tracker.md`.
Branch: `feat/rag-gap-remediation` (worktree /Users/ghj/work/rag-remediation, base origin/main 925eb530c).

## 1. Direction ✅

Deliverables match the locked direction (tracker §3): debt-first (G1/G2/G3), DDR-A
ratified as A1, observability parity, token budget; architecture unchanged (RAG
evidence layer + D7 compiled layer); P2 advanced items (reranker/Milvus/GraphRAG/
sidebar menu/structured citation channel) explicitly descoped, no scope drift.
G7 delivered as "foundation only" exactly as recorded in the §4 absorption map.

## 2. Progress — per-gap evidence ✅

| Gap | Status | Evidence (commit / test) |
|-----|--------|--------------------------|
| G9 ingest 3×镜像 | DONE | a22d882bb — `KbChunkIngestPipeline` consolidates 4 sites (grep `to_tsvector` in main = pipeline + reindex only); `KbChunkIngestPipelineTest` 6/6; full rag package green |
| G2 CJK BM25 | DONE | fd57e63e5 — `CjkBigramSegmenter` (10 unit tests); IT `chineseQuery_hitsChineseContent_keywordOnly` proves zh hit with embeddings mocked OFF; `reindex_upgradesLegacyTsvRows` proves legacy-miss → reindex → hit |
| G1 permissions | DONE | fd57e63e5 — 14/14 endpoints `@RequirePermission` (grep count 14=14); codes in MetaPermission + default-bootstrap.json; `validate-permission-codes.mjs` 0 drift; IT 403-without-grant / 200-with-grant |
| G5 RRF fusion | DONE | 24b35ee61 — `D7RagFusion` (DDR-A=A1, k=60, compiled ×1.5); `D7RagFusionTest` incl. raw-beats-low-compiled case |
| G4 token budget | DONE | 24b35ee61 — `contextMaxTokens` (default 3000) in fused renderer, both D7 and raw-only paths; budget-truncation unit test |
| G6 observability+retry | DONE | d99005802 — `RagRetrievalMetrics` (latency/zero-result/degraded/kb-dropped/retry); `EmbeddingRetryService` + `sys-rag-embedding-retry` (5 min, max 5 attempts → `failed_permanent`); 3 real-DB ITs (recover / increment+exhaust / skip-exhausted); raw path now traces |
| G8 dimension mismatch | DONE | d99005802 — `/retrieve` → `{results, warnings}`; playground surfaces warning; metric counts drops |
| G3 golden queries | DONE | b40fd845f — 15→52 (zh 21 / en 14 / mixed 17; no-answer 10; multi-hop 4); every expected page id/path existence-verified; schema harness green |
| G7 feedback loop | PARTIAL (as planned) | trace+metrics foundation (G6); interactive UI deferred to P2 (recorded §4 absorption map) — not silent |

## 3. Gap re-scan (completeness critic) ✅

- `grep to_tsvector` main sources → only `KbChunkIngestPipeline` (ingest) + `KnowledgeBaseService.reindexChunkTsv` (upgrade) — no stray unsegmented site.
- 14/14 controller methods guarded (grep count match).
- Residual (non-blocking, recorded): `RagRetrievalService.buildRagContext` and `D7ContextAssembler.buildAuraBotContext` are now test-only legacy renderers — cleanup candidate.
- Local gates: `check-oss-boundary.sh` ✅, `check-reset-init-contracts.sh` ✅, `validate-permission-codes.mjs` ✅, `check-docs.sh` 5 dead links **pre-existing** (user-soul-profile docs, untouched by this branch). `check-schema-sql.sh` requires docker (daemon not running) — schema change exercised instead against real Postgres via additive migration + full IT suite; rerun under docker before any release image build.
- New gap found during re-scan: none.

## 4. UX verification (real browser/stack)

Stack: isolated slot-23 runtime (backend 6423 / web 5123 / DB auraboot_23), clean
reset+bootstrap from this branch's code.

- E2E `tests/e2e/ai/knowledge-base-smoke.spec.ts`: **32 passed / 1 skipped (design-conditional, not a product gap) / 0 failed**, incl. the new
  "should reindex chunk tsv via UI button" (real click → POST /reindex → reindexedChunks > 0).
- **Full OSS suite on this branch's stack: 924 passed / 0 failed** (72 skipped + 117 did-not-run = showcase-seed-dependent, SKIP_SEED env; rerun 906 passed / 0 failed) — no regression anywhere in the web surface.
- Screenshots (assets/rag-remediation/): `01-kb-list.png`, `02-kb-detail-reindex-button.png` (Reindex button rendered beside Upload Files, verified visually), `03-kb-reindex-clicked.png`, `04-retrieval-playground.png`.
- /e2e-truth 5-dim audit on `tests/e2e/ai/`: D1=1 (smoke-level UI ratio — pre-existing smoke spec; new reindex case is UI-driven; deep behaviors covered at real-stack IT layer), D2=2 (0 thresholds, 22 hard assertions), D3=2 (0 skip/fixme wrapping), D4=1 (6 pre-existing >5s timeouts for async doc-processing polling, none added), D5=2 (0 retries/thresholds) → **8/10**.
- Honest wording: KB E2E is smoke-grade UI coverage + one new UI-driven action test; the golden-depth verification for CJK retrieval, permission 403, retry exhaustion and dimension-mismatch warnings lives in the 208-test backend suite against real Postgres/pgvector — the correct layer for those behaviors.

## 5. Test completeness ✅ (pending §4 E2E)

- rag package: 208 tests green (units + real-Postgres/pgvector ITs); aurabot + scheduler packages green (BUILD SUCCESSFUL).
- Happy-path real-stack: CJK retrieval IT (real DB, real tsquery), retry ITs (real DB), controller ITs (MockMvc through real permission interceptor).
- No skip-wrapped product gaps; no threshold loosening; no retries-bolstered assertions. Embedding API is the only mocked external (LLM-key class, legitimate stub); pgvector/tsvector/permission interceptor/scheduler registration all real.
- Stubbed block points to backfill with a live embedding key: live-eval run of the 52-query golden set (Phase-2 harness), real-provider embedding smoke. Recorded in tracker §8.

## Residual list

1. Golden-set Phase-2 live evaluation (needs embedding API key + populated fixtures) — tracker §8.
2. `buildRagContext`/`buildAuraBotContext` legacy renderers test-only — cleanup follow-up.
3. `check-schema-sql.sh` docker rerun before release packaging.
4. P2 deferred list unchanged (tracker §2).

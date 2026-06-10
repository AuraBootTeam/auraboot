---
type: backlog
status: active
created: 2026-06-10
owner: platform
topic: RAG system review, architecture assessment, and gap remediation tracker
---

# RAG System Review & Gap Remediation Tracker (2026-06-10)

> Full architecture review of the AuraBoot RAG subsystem (`auraboot/platform/.../framework/rag/` + `aurabot` D7 layer + agent memory consumers).
> Verdict: **architecture direction is sound (RAG evidence layer + D7 compiled layer); foundation complete; remediate P0s before any advanced features.**

## 1. Current architecture (verified 2026-06-10)

```
Ingestion (3 paths)              Processing                   Storage (Postgres)
├─ File upload (PDF/DOCX/MD/     ChunkingService              ab_knowledge_base
│  TXT/CSV/HTML)                  (paragraph-aware +           ab_kb_document
├─ Entity sync (dk_document       sentence-aligned,            ab_kb_chunk
│  publish events, async)         500 chars / 50 overlap)        ├─ embedding vector(1536) + HNSW
└─ Plugin SPI                    EmbeddingService                └─ tsv TSVECTOR + GIN
   KnowledgeBaseAccessor          (OpenAI-compatible,
   .ingestText (idempotent)        multi-provider, degrade→BM25)

Retrieval: QueryRewrite (synonyms.yml) → hybrid SQL (0.7*vector + 0.3*BM25)
           → lightweight rerank (term overlap) → top-5

Consumption: AuraBotChat → RagContextProvider → [D7 compiled pages (keyword rank)
             + RAG raw chunks] → D7ContextAssembler → system prompt
Frontend: KB management page + chunk viewer + retrieval playground + chat citation rendering
Parallel embedding consumers: agent memory L1/L2, model semantic search — all share EmbeddingService ✅
```

Key strengths (keep, do not redesign):
- Two-layer split: RAG = evidence retrieval, D7 compiled knowledge = reviewed conclusions with sourceRefs/staleStatus/publish workflow (`compiled.decision.d7-knowledge-layer.md`).
- Single `EmbeddingService` shared by rag / agent memory / model search — no duplicate embedding stacks.
- Complete degradation chain: embed fail → BM25 → keyword-only → empty.
- 3-layer tenant isolation; pgvector HNSW (m=16, ef=64) appropriate for <100K vectors.
- Deterministic evaluation: source registry 4,301 items + golden queries + recall/MRR/citation-faithfulness (no LLM judging).

Test baseline: 22 backend test files / ~183 @Test methods (incl. RagPipelineIntegrationTest on real Postgres+pgvector); 29 E2E specs (knowledge-base-smoke 12 + dk-doc-knowledge 17).

## 2. Gap list

### P0 — fix immediately

| # | Gap | Evidence | Status |
|---|-----|----------|--------|
| G1 | **All 13 `KnowledgeBaseController` endpoints lack permission annotations** (create/delete KB, upload, retrieve, import-internal-docs, generate-docs — any authenticated user can delete a whole KB; only tenant isolation) | verified: `grep RequirePermission` exit=1 on `rag/controller/KnowledgeBaseController.java`; violates `permission-code-naming.md` | **DONE** (commit fd57e63e5: @RequirePermission ×14, codes seeded, gate green, 403 IT) |
| G2 | **Chinese BM25 path is near-useless**: 4 ingest sites use `to_tsvector('simple', …)`; query side splits CJK into per-char OR. 30% hybrid weight is noise for Chinese | verified: KbTextIngestService:120, DocumentProcessingService:88, RagDocumentSyncListener:194, InternalDocImportService:155; self-flagged as "CJK tokenization P0 / Spike-5 BLOCKED" in spike-1 docs | **DONE** (fd57e63e5: CjkBigramSegmenter index+query, reindex endpoint+UI button, IT proves zh hit on BM25 leg) |
| G3 | **Evaluation set too small**: 12 golden queries with recall@5=1.0 means the set is too easy; cannot claim production-grade quality. Spike-1 Phase 3 (expand to 50-100 incl. CJK, multi-hop, no-answer; latency) planned but not started | `rag-golden-query-report.md`, `2026-05-28-spike-1-phase-2-analysis.md` | **DONE** (b40fd845f: 15→52, all ground truth existence-verified, harness green) |

### P1 — quality & operability

| # | Gap | Evidence | Status |
|---|-----|----------|--------|
| G4 | No token budget: RAG context (top-5 chunks + full D7 page bodies) concatenated into system prompt without truncation | `AuraBotChatService.buildSystemPrompt()` L486-566 | **DONE** (24b35ee61: contextMaxTokens budget in fused renderer, both paths) |
| G5 | D7 and RAG have no joint ranking: keyword-rank pages always precede hybrid-scored chunks; deferred **DDR-A** decision (soft recommendation A1: upper-layer fusion / RRF) never ratified | `D7ContextAssembler` L10-38; spike-1 phase-2 analysis | **DONE** (24b35ee61: DDR-A ratified as A1, D7RagFusion RRF k=60 w=1.5) |
| G6 | Zero observability on RAG side: D7 has RetrievalTraceWriter, RAG has none (no latency/hit-rate/zero-result metrics); `embedding_status='failed'` chunks have no retry & no alert (stuck forever) | `RagRetrievalService` logs only | **DONE** (d99005802: RagRetrievalMetrics + sys-rag-embedding-retry bounded retry + failed_permanent + raw-path trace) |
| G7 | No feedback loop: no signal whether retrieved chunks were used/helpful | one-way consumption chain | PARTIAL — trace+metrics foundation landed (G6); interactive feedback UI deferred to P2 list (§4 absorption map) |
| G8 | Cross-KB embedding dimension mismatch silently drops KBs (recall loss invisible to user) | `RagRetrievalService` L73-90 | **DONE** (d99005802: /retrieve returns {results,warnings} + playground toast + metric) |
| G9 | Ingest logic mirrored 3× (DocumentProcessingService / KbTextIngestService / RagDocumentSyncListener) — chunking changes must be applied in 3 places | identical INSERT statements, comment-acknowledged | **DONE** (a22d882bb: KbChunkIngestPipeline, 4 paths consolidated, completed/failed exit invariant) |

### P2 — advanced capability deficit (defer until G3 baseline exists)

- No dedicated reranker (current: 0.6*hybrid + 0.4*term-overlap linear combo); no query decomposition; no embedding cache; no vector quantization; no metadata filtering beyond tenant_id; no semantic dedup.
- Citation is prompt-convention only (`[Source: docName, Chunk N]` parsed by regex in `AuraBotChat.tsx`); no structured metadata channel through ResponseSink.
- KB management page not reachable from the sidebar menu (direct URL only, `/aurabot/knowledge`) — violates "直达 URL ≠ 菜单已通".

## 3. Direction (ratified by this review)

Follow the existing D7 roadmap (`96-AuraBoot知识系统重设计方案.md`); do not redesign:

1. **Debt before features**: G1 (permissions) → G2 (CJK: zhparser extension or ingest-side pre-tokenization into tsv; NOT Elasticsearch) → G3 (golden queries 50-100). G3 is the precondition for every subsequent retrieval change.
2. **Ratify DDR-A as A1** (upper-layer fusion, RRF of D7 keyword score + RAG hybrid score), validated against the expanded golden set.
3. **Observability parity with D7**: RAG retrieval trace (query, recall count, score distribution, latency, degradation reason); embedding-failed retry + metric.
4. **Token budget + structured citation** via ResponseSink metadata channel (align with §12 conversation chokepoint).
5. **Defer**: dedicated reranker / Milvus / GraphRAG until post-G3 data justifies them. Crawler F2 should reuse `KnowledgeBaseAccessor.ingestText` SPI, not a second vector stack.

## 4. Endgame anchor (P1, /aura-endgame 2026-06-10)

Endgame docs for this remediation run (existing canonical docs, not rewritten):
- **Product/architecture endgame**: `auraboot-enterprise/docs/.../96-AuraBoot知识系统重设计方案.md` (D7 layered knowledge system) — direction unchanged, ratified by §1 of this review.
- **Gap analysis (P2)**: §2 of this doc — every gap evidence-graded (✅实测), verified 2026-06-10.
- **User journeys** (remediation-relevant, happy/sad/edge/corner):
  - J1 KB admin: create KB → upload doc → chunks embedded → retrieval test. Sad: non-privileged user hits 403 on every mutating endpoint (G1). Edge: doc with embedding API down → chunk `failed` → auto-retried → recovered (G6). Corner: KB with mismatched embedding dimension → explicit warning in response, not silent drop (G8).
  - J2 Chinese user asks AuraBot a zh question → CJK-segmented BM25 + vector hybrid returns relevant chunks (G2) → context fused with D7 pages by RRF (G5) within token budget (G4) → citations rendered.
  - J3 Plugin ingests text via SPI → same single pipeline as upload/sync (G9) → idempotent re-ingest.
  - J4 Operator reviews retrieval quality: golden-query eval ≥50 queries incl. zh/multi-hop/no-answer (G3); retrieval metrics visible (G6).

### Input-absorption map (保全闸门)

| Review input | Disposition |
|---|---|
| G1-G9 | P5 slices S1-S6 below, all in scope |
| G7 feedback loop | **Minimal slice**: trace foundation lands with G6; interactive user-feedback UI **deferred** to §2 P2 list (reason: touches chat UX product surface, needs product decision on signal design; foundation-first per direction §3-3) |
| P2 advanced items (reranker/Milvus/GraphRAG/KB menu visibility/structured citation channel) | **Descoped** per user instruction + direction §3-5 (post-G3 data-driven) |

## 5. Decisions (P1/P2, recorded per decision-defaults)

- **D1 (G2) CJK strategy = Java-side bigram segmentation**, not zhparser, not Elasticsearch. Why: zhparser requires a Postgres extension in every env (shared host PG, docker images, CI) — heavy infra coupling; ES is a second search stack. CJK bigram (ES `cjk` analyzer approach) is dictionary-free, deterministic, pure-Java, applied symmetrically at ingest (`to_tsvector('simple', segmented)`) and query (`tsquery` of bigrams). Tradeoff: slightly larger tsv, no semantic word boundaries — acceptable at current scale; revisit post-G3 eval. Existing rows need re-segmentation → admin reindex endpoint (no startup backfill per §4.1).
- **D2 (G5) DDR-A ratified as A1**: upper-layer RRF fusion of D7 keyword-ranked pages and RAG hybrid-scored chunks in `RagContextProviderImpl`; D7 pages keep a rank bonus (reviewed > raw) but no longer unconditionally precede all chunks.
- **D3 (G9 before G2)**: consolidate the 3 mirrored ingest paths into one `KbChunkIngestPipeline` first, so CJK segmentation lands in exactly one place. Existing IT suites for all three paths are the regression net.
- **D4 (G6) retry = bounded queue-processing task** (SystemTaskInitializer TaskDef, every 5 min, `embedding_retry_count` cap 5) — this is outbox-style queue processing of an explicit `failed` state, not §8 symptom-layer self-heal; failures stay visible via metric `rag.embedding.retry` and terminal state `failed_permanent`.
- **D5 (G1) permission codes** `ai.knowledge.read / ai.knowledge.manage / ai.knowledge.retrieve` (module.resource.action), constants in `MetaPermission`, seeded in `tenant-templates/default-bootstrap.json`; TENANT_ADMIN auto-binds via `RolePermissionTemplate.ALL` → admin E2E unaffected; gate `scripts/validate-permission-codes.mjs` must stay green.

## 6. P3/P4 reconciliation & UI coverage (verified)

- Consistency: this tracker ↔ D7 roadmap (`96-方案` Phase 2) — no conflict; G5 implements deferred DDR-A; G3 implements Spike-1 Phase 3 first step. No dangling refs.
- UI surface impact matrix: KB pages (`knowledge.tsx`, `knowledge.$kbPid.tsx`) — unchanged behavior for admin ✓covered by existing E2E; 403 paths for non-admin = new sad-path E2E (S3); **Reindex** action = new button on KB detail (DSL-exempt: existing React plugin page, modification not new page) (S2); chat citation rendering unchanged ✓. No方向性缺口.

## 7. P5 slice plan

| Slice | Gaps | Status |
|---|---|---|
| S1 | G9 ingest pipeline consolidation | DONE a22d882bb |
| S2 | G2 CJK bigram (ingest+query+reindex endpoint+UI button) | DONE fd57e63e5 |
| S3 | G1 permission annotations + codes + sad-path tests | DONE fd57e63e5 |
| S4 | G4 token budget + G5 RRF fusion | DONE 24b35ee61 |
| S5 | G6 metrics/trace/retry + G8 dimension-mismatch surfacing | DONE d99005802 |
| S6 | G3 golden queries ≥50 + harness green | DONE b40fd845f |

## 8. Progress log

- 2026-06-10: Review completed; tracker created. Remediation started (this session, /aura-endgame). P0-P4 done: worktree `feat/rag-gap-remediation`@925eb530c, facts verified (permission pattern/harness/trace/scheduler/E2E auth), decisions D1-D5 recorded.
- 2026-06-11: S1-S6 all landed (see §7). Pre-completion review `docs/retro/2026-06-11-rag-remediation-review.md`: KB E2E 32/1skip/0fail on isolated slot-23 stack, full OSS suite 924 passed ×2, /e2e-truth 8/10. Code review found+fixed: CommandPalette doc-search broken by {results,warnings} shape (P0), supplementary-plane CJK lone-surrogate bigrams (P1), missing existing-DB migration for embedding_retry_count (P1, added 2026-06-11-rag-embedding-retry.sql), latin tsquery terms not lowercased (P2), keyword_sql_failed metric unwired (P2).
- **Block points to backfill with a live embedding API key (LLM-key class)**: real-provider embedding smoke for retry task; rerun Phase-2 eval in `live` embeddingMode to quantify vector-leg lift (harness ready, see below).
- 2026-06-11 (residuals round): ① billing.catalog/quota/usage.read registered in bootstrap (pre-existing main drift, gate back to 0). ② Legacy renderers buildRagContext/buildAuraBotContext removed; tests migrated to fused renderer. ③ **Phase-2 evaluation harness DONE** (`RagEvaluationPhase2IT`, env-gated: RAG_EVAL_DOCS_PATH/RAG_EVAL_D7_PAGES_PATH) — first live run (keyword-fallback mode, 52/52 queries, 272-doc corpus): **Path B (D7) recall@5 0.985 / MRR 0.909** (CJK bigram effective across zh/en/mixed); **Path A keyword leg recall@5 0.600 / MRR 0.454** (4 paraphrase-type zh/mixed misses = exactly what the vector leg must add); full report in `docs/backlog/2026-06-11-rag-eval-phase2-first-run.md`.
- **NEW GAP (G10, P1, from eval)**: no-answer behavior — 10/10 `neither` queries returned results from both paths (no relevance score floor); correct-rejection rate 0. Needs a minimum-score threshold / calibrated floor before the chat layer can trust "no relevant knowledge" signals. Deferred to next slice with the eval harness as its regression net.

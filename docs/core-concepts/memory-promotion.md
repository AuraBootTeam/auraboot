# Memory Promotion

The Memory Promotion pipeline lifts agent memories up the scope lattice —
from personal (`scope=user`) to team-level (`scope=tenant`) — with a
human-in-the-loop review gate, semantic deduplication, and a shadow
observation window before a promoted memory becomes authoritative.

Full design: [`docs/plans/2026-04/2026-04-18-memory-promotion-design.md`](../plans/2026-04/2026-04-18-memory-promotion-design.md).

## Phase 1 landed (PR-65)

Phase 1 ships the foundation: schema + embedding layer + nightly extractor
that emits proposals. No review UI or applier yet — those are Phase 2–4.

### Schema

| Object | Purpose |
|---|---|
| `ab_agent_memory_promotion` | Proposal + audit ledger; status lattice `DRAFT_PENDING_REVIEW → PROMOTED_SHADOW → ACTIVE` with terminal `REVIEWED_REJECTED / RETRACTED / DISCARDED / EXPIRED`. |
| `ab_agent_memory.shadow_mode` | `BOOLEAN DEFAULT FALSE`; flipped `TRUE` while the source promotion is observing. |
| `ab_agent_memory.promoted_from_pid` | Back-link to `ab_agent_memory_promotion.pid`; partial index when non-null. |
| `ab_agent_memory.embedding` (reused) | Existing `vector(1536)` column + HNSW index; reused for similarity. No separate `content_embedding` column. |

### Services

| Class | Role |
|---|---|
| `ConfidenceScorer` (util) | Pure static formulas for each proposal strategy, clamped to [0,1]. |
| `EmbeddingSimilarity` (util) | Pure-Java cosine fallback for `double[]` / `float[]` with null/length-mismatch safety. |
| `MemoryEmbeddingService` | Reads / lazily computes + stores the memory embedding via the existing `EmbeddingService` (CloudConfig `embedding` provider type). Provider down → null; extractor skips. |
| `MemoryPromotionExtractor` | Scheduled `@Scheduled(cron=…)` runner. Advisory lock key `7303`. Three strategies: `cross_user_agreement`, `implicit_co_sign`, `importance_spike`. Dedup against any non-terminal prior proposal for the same source. |
| `MemoryPromotionMetrics` | Micrometer counter `auraboot_memory_promotion_proposal_total{tenant, reason_code}`. |

### Config knobs

```properties
acp.memory.promotion.scheduler.enabled=false
acp.memory.promotion.scheduler.cron=0 30 3 * * *
acp.memory.promotion.min-users-per-tenant=3
acp.memory.promotion.min-similarity=0.85
acp.memory.promotion.min-importance-for-spike=9
acp.memory.promotion.importance_spike.enabled=false
acp.memory.promotion.rationale.enabled=true
```

### Known Phase-1 gaps (deferred to Phase 2)

- **`implicit_co_sign`**: scaffolded but `countCoSigners()` returns 0 in
  Phase 1 — `ab_agent_memory` has a single `last_accessed` timestamp and
  no per-user access log. A future memory access log feeds this strategy.
- **AI rationale**: `generateRationale()` is a null-returning hook;
  column exists and wiring is stubbed for a Phase-2 LLM call.
- **Applier / Activator / Expirer**: Phase 2 (PR-66).
- **REST + UI**: Phase 3 / 4.

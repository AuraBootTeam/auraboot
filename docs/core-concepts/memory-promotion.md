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

---

## Mission Control UI (Phase 4, PR-68)

Reviewer workflow surface at `/aurabot/memory-promotions`.

### Tabs

1. **Pending Review** (default) — `GET /api/memory/promotions?status=DRAFT_PENDING_REVIEW&sort=confidence_desc`
   - Per-row card: confidence bar, category badge, proposed title + content,
     AI rationale, expandable evidence (source user_ids), batch checkbox,
     approve / reject / provenance buttons.
   - Always-visible PII warning banner above the approve action.
2. **Shadow Observation** — `?status=PROMOTED_SHADOW`
   - Shows "N hours remaining" countdown vs `shadow_ends_at`.
   - Retract button → modal requiring a free-form reason → `POST /retract`.
3. **Audit History** — `ACTIVE` + `REVIEWED_REJECTED` + `RETRACTED` (three parallel
   requests merged + sorted by `created_at desc`). Read-only table with
   provenance drill-down.

### Keyboard shortcuts (Pending tab)

| Key | Action |
|-----|--------|
| `j` | Select next proposal |
| `k` | Select previous proposal |
| `a` | Approve currently selected |
| `r` | Open reject modal |
| `s` | Skip (advance selection without mutation) |
| `e` | Toggle evidence expansion |

Shortcuts are suppressed while focus is inside an `input` / `textarea` / `select`
or while any modal (reject / batch / provenance) is open.

### Batch approve drawer

- Enabled when ≥ 1 row is checked.
- Forwarded to `POST /api/memory/promotions/batch-approve` which filters out
  entries with `confidence < 0.80` — the drawer surfaces the floor inline.
- PII warning repeats inside the drawer so bulk approvers cannot miss it.

### Provenance modal

- `GET /api/memory/promotions/{pid}/provenance` renders a timeline:
  source memories → promotion step → promoted tenant memory (if any).
- Author attribution is pulled from `ab_user` when the user row still exists.

### Grounding integration (`[SHADOW / 近期团队记忆 · 观察中]`)

`AgentMemoryService.searchScoped` / `loadScopedByImportance` return
`shadow_mode` alongside other columns. `ActiveMemoryService.snippet()`
prefixes the content with the bilingual marker when `shadow_mode=TRUE`
so AuraBot's prompt context conveys the observation-window uncertainty
and can preface its reply with "根据团队近期记忆（尚在观察期）：...".

Integration test: `ActiveMemoryShadowAnnotationIntegrationTest` pins three
shapes — shadow tenant memory annotated; active tenant memory verbatim;
importance-only path also annotated.

# Memory Promotion (L1 → L2 → L3) — Design

**Status**: approved
**Owner**: ACP team
**Date**: 2026-04-18 (revised 2026-04-18 with excellence-first strategy)
**Depends on**: Learning Loop HITL scaffolding (PR-26 → PR-64)
**Blocks**: Soul Profile derivation (next round)

---

## Strategic framing

Small team, big ambition. The goal is **not** "raise signal volume with loose thresholds to feel useful at small scale" — the goal is **every proposal is worth reading**. A reviewer looking at 20 high-confidence annotated proposals extracts more long-term value than one looking at 200 hashy near-duplicates. Small-scale excellence is built on signal density and evidence, not quantity.

Every design decision below is scored against: *does this make the product better when we have 10,000 users?* If a shortcut works only at 5 users and would need rewrite at 500, it's rejected.

---

## 1. Motivation

The `AgentMemoryService` already models a 3-scope lattice:

- `scope='user'` — scoped to one user across sessions
- `scope='tenant'` — scoped to an organisation, readable by all members
- `scope='global'` — platform-wide, readable by everyone

`AgentMemoryConsolidationService.promoteSessionMemories` handles **session → user** silently. But:

1. There is **no** user → tenant promotion path at all.
2. No audit trail for why a memory is at the scope it is.
3. Cross-user signals (three users independently discovering the same tenant-level fact) have no path to become a tenant-scope memory.
4. A mis-promoted tenant memory can influence AI decisions immediately, with no review gate and no way to catch wrong inferences before they compound.

Soul Profile (next round) requires durable memories with provenance. Without a promotion pipeline, every profile extraction is one-shot over raw session history — expensive, non-incremental, non-auditable.

This plan adds the promotion layer, built to a product-grade bar from day 1.

---

## 2. Current state

Existing surfaces we reuse:

- `ab_agent_memory` table with `scope` + `scope_key` columns (PR-13).
- `AgentMemoryService.createScopedMemory / searchScoped / loadScopedByImportance` — scope visibility contract already enforces the lattice.
- `AgentMemoryConsolidationService.promoteSessionMemories(tenantId, agentCode, importanceThreshold)` — single-step importance-threshold promoter (to be wrapped for audit).
- Mission Control HITL scaffolding (learning-drafts page, review endpoint pattern).
- `LearningLoopMetrics` Micrometer pattern.
- Advisory-lock `TransactionTemplate.execute` pattern (PR-59).
- `PromotionEvaluator` / `PromotionEvaluationRunner` / scheduled runner shape.
- LLM provider infrastructure (`LlmProviderFactory`) — for embedding generation.

---

## 3. Scope

### In scope (v1)

- **user → tenant** promotion with semantic similarity matching.
- **session → user** upgrade: existing logic retained; add full audit rows.
- Cross-user pattern detection using **embedding similarity** (not verbatim hash).
- Single-user `importance_spike` with **implicit co-sign via reference tracking**.
- **Confidence score** on every proposal.
- **Provenance chain** queryable + rendered in UI.
- **Shadow period** (7 days) before a promoted memory is treated as authoritative.
- **Reject reason classification** captured as feedback signal.
- Mission Control review UI with reviewer-focus-mode shortcuts.
- Metrics + Grafana panels.
- Schema with CHECK/FK/unique constraints from day 1.

### Explicit deferrals (v2 / Tier-2)

- Tenant → global promotion (platform governance)
- Dual-blind review for sensitive categories (HR / Finance / Compliance)
- User-facing "this memory is wrong" button inside AuraBot chat
- ML-based confidence replacing the heuristic
- Cross-tenant knowledge lifting

These are documented deferrals, not accidental gaps. Each has a clear trigger for promotion from v2 → current.

---

## 4. Data model

### New table: `ab_agent_memory_promotion`

```sql
CREATE TABLE IF NOT EXISTS ab_agent_memory_promotion (
    id                    BIGSERIAL PRIMARY KEY,
    pid                   VARCHAR(26) UNIQUE NOT NULL,
    tenant_id             BIGINT NOT NULL,

    -- Source
    source_scope          VARCHAR(16) NOT NULL,     -- 'session' | 'user'
    source_memory_pid     VARCHAR(26),              -- primary source; null for cross-user merges
    source_memory_pids    JSONB,                    -- full list for merges; null when 1:1
    target_scope          VARCHAR(16) NOT NULL,     -- 'user' | 'tenant'

    -- Proposal content
    category              VARCHAR(32) NOT NULL,
    proposed_title        VARCHAR(200),
    proposed_content      TEXT NOT NULL,
    proposed_importance   INTEGER DEFAULT 5,

    -- Signal strength
    reason_code           VARCHAR(32),              -- 'cross_user_agreement' | 'implicit_co_sign' | 'importance_spike' | 'session_upgrade'
    reason_detail         JSONB,                    -- counts, user ids, similarity score, etc.
    confidence_score      NUMERIC(3,2),             -- [0.00, 1.00], reviewer sorts desc
    similarity_score      NUMERIC(3,2),             -- embedding similarity when cross_user_agreement
    ai_rationale          TEXT,                     -- one-line LLM-generated "why" for reviewer

    -- Review workflow
    status                VARCHAR(32) NOT NULL DEFAULT 'DRAFT_PENDING_REVIEW',
    reviewer_id           BIGINT,
    review_comment        TEXT,
    reject_reason         VARCHAR(32),              -- classification when rejected: 'too_specific' | 'contains_pii' | 'outdated' | 'wrong' | 'duplicate' | 'other'

    -- Shadow period (post-promotion observation)
    promoted_memory_pid   VARCHAR(26),              -- set when status=PROMOTED_SHADOW
    shadow_started_at     TIMESTAMPTZ,
    shadow_ends_at        TIMESTAMPTZ,              -- shadow_started_at + 7d
    activated_at          TIMESTAMPTZ,              -- when status flipped to ACTIVE

    -- Audit
    created_at            TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    reviewed_at           TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_memory_promotion_status CHECK (status IN (
        'DRAFT_PENDING_REVIEW',
        'REVIEWED_REJECTED',
        'PROMOTED_SHADOW',        -- 7-day observation window
        'ACTIVE',                 -- fully authoritative
        'RETRACTED',              -- rolled back during shadow period
        'DISCARDED',              -- rejected + 90d retention expired
        'EXPIRED'                 -- pending review > 30d with no action
    )),
    CONSTRAINT chk_memory_promotion_scope CHECK (
        (source_scope = 'session' AND target_scope = 'user') OR
        (source_scope = 'user' AND target_scope = 'tenant')
    ),
    CONSTRAINT chk_memory_promotion_reject_reason CHECK (
        reject_reason IS NULL OR reject_reason IN (
            'too_specific', 'contains_pii', 'outdated', 'wrong', 'duplicate', 'other'
        )
    ),
    CONSTRAINT fk_memory_promotion_source FOREIGN KEY (source_memory_pid)
        REFERENCES ab_agent_memory (pid) ON DELETE SET NULL
);

CREATE INDEX idx_memory_promotion_tenant_status ON ab_agent_memory_promotion (tenant_id, status);
CREATE INDEX idx_memory_promotion_pending_confidence ON ab_agent_memory_promotion (tenant_id, confidence_score DESC)
    WHERE status = 'DRAFT_PENDING_REVIEW';
CREATE INDEX idx_memory_promotion_shadow_ends ON ab_agent_memory_promotion (shadow_ends_at)
    WHERE status = 'PROMOTED_SHADOW';
CREATE INDEX idx_memory_promotion_created ON ab_agent_memory_promotion (created_at DESC);
```

### Status lattice

```
DRAFT_PENDING_REVIEW
   │
   ├── approve ──> PROMOTED_SHADOW  (creates ab_agent_memory row; 7-day observation)
   │                     │
   │                     ├── no retraction for 7d ──> ACTIVE
   │                     │
   │                     └── retracted during window ──> RETRACTED
   │                                                     │
   │                                                     └── soft-delete the ab_agent_memory row
   │
   ├── reject (+reason) ──> REVIEWED_REJECTED
   │                             │
   │                             └── +90d ──> DISCARDED
   │
   └── +30d no review ──> EXPIRED   (auto-cleanup, counts as soft reject for feedback)
```

Key design notes:

- **`PROMOTED_SHADOW` vs `ACTIVE`** — mirrors Learning Loop's `SHADOW_RUNNING` vs `ACTIVE`. A promoted memory is queryable but AuraBot annotates it in responses ("Per a recent team memory: ...") so users can catch misinformation before it calcifies. Retraction during shadow soft-deletes the memory row.

- **`ab_agent_memory.shadow_mode BOOLEAN`** — new column, TRUE while the source promotion is in `PROMOTED_SHADOW`. Grounding service can choose whether to include shadow memories in default candidate set (default: include with annotation).

### Companion schema change

```sql
ALTER TABLE ab_agent_memory
    ADD COLUMN IF NOT EXISTS shadow_mode BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS promoted_from_pid VARCHAR(26);
    -- promoted_from_pid nullable; links back to ab_agent_memory_promotion.pid for provenance lookup.
CREATE INDEX IF NOT EXISTS idx_memory_promoted_from ON ab_agent_memory (promoted_from_pid)
    WHERE promoted_from_pid IS NOT NULL;
```

---

## 5. Embedding similarity

### Why

Verbatim hash misses paraphrases: "We close books on the 28th" vs "Month-end close lands on 28" are the same fact. Small-scale excellence demands semantic dedup from v1.

### How

1. When a user-scope memory is created or meaningfully updated, emit an embedding via `LlmProviderFactory.resolveConfig(tenantId, "embedding").generateEmbedding(content)`.
2. Store in a new column:
   ```sql
   ALTER TABLE ab_agent_memory
       ADD COLUMN IF NOT EXISTS content_embedding VECTOR(1536);
   CREATE INDEX IF NOT EXISTS idx_memory_embedding ON ab_agent_memory
       USING ivfflat (content_embedding vector_cosine_ops);
   ```
   If `pgvector` extension is not enabled on deploy targets, **fall back** to a `TEXT` column holding the JSON-serialised vector and compute cosine in Java. This is v1-acceptable; `pgvector` migration is a tuning follow-up.
3. `MemoryPromotionExtractor.findAgreements(tenantId)` groups candidates by cosine similarity >= 0.85 across user boundaries; min 3 users per cluster.
4. Proposal records the cluster's `similarity_score = min(pairwise cosine)` — reviewer sees honest "how similar were these, really" number.

### Cost control

- Embedding only recomputed on memory write, not every scan. Amortised ~$0.00001 per memory at current provider rates.
- Extractor runs nightly against the last 90 days of memories only (partitioned by `updated_at`).
- If embedding endpoint is unreachable at write time, memory is stored without embedding; extractor will compute lazily on first scan and store.

---

## 6. Services

### 6.1 `MemoryPromotionExtractor`

Scheduled nightly. Advisory lock key `7303`. Three extraction strategies:

**Strategy A — `cross_user_agreement`**
- Cluster user-scope memories via embedding cosine >= 0.85
- Min cluster size: 3 distinct users in the same tenant
- `reason_code='cross_user_agreement'`
- `reason_detail = { "user_ids": [...], "agreement_count": N, "min_similarity": 0.87 }`
- `confidence_score = 0.5 + 0.1 × min(agreement_count - 3, 5) + 0.2 × (similarity - 0.85) × 3.33` → typically 0.7 – 0.9

**Strategy B — `implicit_co_sign`**
- User-scope memories with `shareable=TRUE AND importance >= 8`
- Count: how many OTHER users have `last_accessed` on this memory in the last 90d?
- If co_sign_count >= 3 → propose with `reason_code='implicit_co_sign'`
- `reason_detail = { "author_user_id": X, "co_signer_user_ids": [...], "access_count": N }`
- `confidence_score = 0.6 + 0.1 × min(co_sign_count - 3, 4)` → typically 0.6 – 1.0

**Strategy C — `importance_spike` (gated)**
- Single-user `shareable=TRUE AND importance >= 9` and author has a tenant-admin role
- `reason_code='importance_spike'`
- `confidence_score = 0.5` (deliberately moderate — single-user signal)
- Only enabled per-tenant via `acp.memory.promotion.importance_spike.enabled` config

**AI rationale generation**
- For each proposal, call LLM with `{source_memories_text, category, reason_code}` → produce one-sentence rationale "为什么这值得提升到团队级别".
- Stored in `ai_rationale`. Reviewer sees it next to the proposal.
- Failure is non-blocking — rationale is null, proposal proceeds.

### 6.2 `MemoryPromotionApplier`

Synchronous; invoked by the review endpoint:

```
approve(promotionPid, reviewerId, comment):
  # status guard
  row = SELECT ... WHERE pid=? AND tenant_id=? AND status='DRAFT_PENDING_REVIEW'
  if missing: return 409

  now = NOW()
  shadowEnds = now + INTERVAL '7 days'

  newMemoryPid = AgentMemoryService.createScopedMemory(
      tenantId=row.tenant_id, agentCode='default',
      memoryType='tenant_shared', category=row.category,
      title=row.proposed_title, content=row.proposed_content,
      importance=row.proposed_importance, shareable=true,
      scope=row.target_scope, scopeKey=null
  )
  UPDATE ab_agent_memory
      SET shadow_mode = TRUE, promoted_from_pid = ?
      WHERE pid = newMemoryPid

  UPDATE ab_agent_memory_promotion
      SET status='PROMOTED_SHADOW', reviewer_id=?, review_comment=?,
          promoted_memory_pid=?, shadow_started_at=?, shadow_ends_at=?, reviewed_at=?
      WHERE pid=? AND status='DRAFT_PENDING_REVIEW'
  if affected_rows != 1: throw ConcurrentModificationException

  emit auraboot_memory_promotion_decision_total{decision=APPROVE}

reject(promotionPid, reviewerId, reason, comment):
  UPDATE ab_agent_memory_promotion
      SET status='REVIEWED_REJECTED', reviewer_id=?, reject_reason=?,
          review_comment=?, reviewed_at=?
      WHERE pid=? AND status='DRAFT_PENDING_REVIEW'
  emit auraboot_memory_promotion_decision_total{decision=REJECT, reason}

retract(promotionPid, reviewerId, reason):
  # must be in PROMOTED_SHADOW state
  row = SELECT ... WHERE pid=? AND status='PROMOTED_SHADOW'
  if missing: return 409
  UPDATE ab_agent_memory SET deleted_flag=TRUE WHERE pid=row.promoted_memory_pid
  UPDATE ab_agent_memory_promotion SET status='RETRACTED', ...
  emit auraboot_memory_promotion_decision_total{decision=RETRACT, reason}
```

### 6.3 `MemoryPromotionActivator`

Scheduled every 30 minutes. Flips `PROMOTED_SHADOW → ACTIVE` for rows past `shadow_ends_at`:

```
UPDATE ab_agent_memory
    SET shadow_mode = FALSE
    WHERE promoted_from_pid IN (
        SELECT pid FROM ab_agent_memory_promotion
         WHERE status='PROMOTED_SHADOW' AND shadow_ends_at <= NOW()
    );

UPDATE ab_agent_memory_promotion
    SET status='ACTIVE', activated_at=NOW()
    WHERE status='PROMOTED_SHADOW' AND shadow_ends_at <= NOW();

emit auraboot_memory_promotion_decision_total{decision=ACTIVATE} × affected_rows
```

Advisory lock key `7304`.

### 6.4 `MemoryPromotionExpirer`

Daily. Handles auto-transitions:

```
-- DRAFT pending > 30d → EXPIRED (stored as negative signal for classifier)
UPDATE ab_agent_memory_promotion
   SET status='EXPIRED', reject_reason='other',
       review_comment='auto-expired after 30d with no reviewer action'
 WHERE status='DRAFT_PENDING_REVIEW' AND created_at < NOW() - INTERVAL '30 days';

-- REVIEWED_REJECTED + 90d → DISCARDED (retention cleanup)
UPDATE ab_agent_memory_promotion
   SET status='DISCARDED'
 WHERE status='REVIEWED_REJECTED' AND reviewed_at < NOW() - INTERVAL '90 days';
```

### 6.5 `SessionMemoryConsolidationAuditRunner`

Retrofit wrapper around `AgentMemoryConsolidationService.promoteSessionMemories`:

```
consolidate():
  before = SELECT pid FROM ab_agent_memory WHERE scope='session' AND category='session'
  AgentMemoryConsolidationService.promoteSessionMemories(tenantId, agentCode, threshold)
  after = SELECT pid FROM ab_agent_memory WHERE scope='user' AND promoted_from_pid IS NULL
          AND pid IN (before)

  # For each newly-promoted session → user memory, create an audit row
  for pid in (after - before):
    INSERT INTO ab_agent_memory_promotion (
      pid, tenant_id, source_scope, source_memory_pid, target_scope,
      category, proposed_title, proposed_content, proposed_importance,
      reason_code, reason_detail, confidence_score, ai_rationale,
      status, reviewed_at, promoted_memory_pid, shadow_started_at,
      shadow_ends_at, activated_at
    ) VALUES (
      UniqueIdGenerator.generate(), tenantId, 'session', pid, 'user',
      ..., 'session_upgrade', {threshold_exceeded: ...}, 1.0, null,
      'ACTIVE', NOW(), pid, NOW(), NOW(), NOW()  -- no shadow period for session→user
    )
```

Session → user bypasses review and shadow (no new boundary crossed — just consolidation within user scope). Audit row is informational only.

### 6.6 `MemoryPromotionController`

```
GET  /api/memory/promotions                              — list (status + sort by confidence desc)
GET  /api/memory/promotions/{pid}                        — detail + source memories preview
GET  /api/memory/promotions/{pid}/provenance             — full chain: promotion ← user memory ← session ← conversation
POST /api/memory/promotions/{pid}/review                 — {decision: approve|reject, comment, reject_reason?}
POST /api/memory/promotions/{pid}/retract                — {reason}; only valid in PROMOTED_SHADOW
POST /api/memory/promotions/batch-approve                — {pids: [...], comment}; body-size bounded, confidence >= 0.80 only
GET  /api/memory/promotions/stats                        — counts by status, by reason_code, by reject_reason
```

All tenant-scoped via `MetaContext.getCurrentTenantId()`.

---

## 7. Mission Control UI

### Page `/aurabot/memory-promotions`

Three tabs:
- **Pending Review** (default) — `DRAFT_PENDING_REVIEW` sorted by `confidence_score DESC`
- **Shadow Observation** — `PROMOTED_SHADOW`; shows remaining shadow window; retract button per row
- **Audit History** — `ACTIVE / REVIEWED_REJECTED / RETRACTED / session_upgrade audit rows`

### Per-row card (reviewer focus mode)

```
┌─────────────────────────────────────────────────────────────────────┐
│  [confidence bar ████████░░ 0.82]   [category: operations]         │
│                                                                      │
│  Proposed:                                                           │
│    "Month-end close cycle runs on the 28th of each month."          │
│                                                                      │
│  AI rationale:                                                       │
│    All three members of the finance team independently noted this   │
│    date in Q3 planning conversations.                                │
│                                                                      │
│  Evidence (3 users agree, similarity 0.87):                          │
│    ◆ alice@co    "we close books on 28th" (2026-04-10)             │
│    ◆ bob@co      "month-end = 28" (2026-04-12)                      │
│    ◆ carol@co    "28th is close day" (2026-04-15)                   │
│                                                                      │
│  [Approve (a)]  [Reject (r)]  [Skip (s)]  [View source session →]   │
└─────────────────────────────────────────────────────────────────────┘
```

Keyboard shortcuts: `j/k` next/prev, `a` approve, `r` open reject modal with reason dropdown, `s` skip (decrement confidence by 0.05, send to back of queue), `e` expand full source memories, `/` focus search.

### Batch approve drawer

- Checkbox next to each pending proposal
- "Approve all selected" button enabled only when every selected has `confidence >= 0.80`
- Single comment + optional per-row comment
- Posts to `/batch-approve`

### Provenance modal

Click a promoted memory anywhere → modal shows:
- Timeline: session → user memory → promotion → tenant memory
- Source conversation (if available) link
- Every author user id + timestamps
- Who approved + when + comment

### i18n

All text via `l(zh, en)` per PR-57 convention. Reject reason dropdown options:
- `too_specific` — 内容过于具体，不适合团队共享
- `contains_pii` — 含有个人信息
- `outdated` — 已过时
- `wrong` — 内容有误
- `duplicate` — 与已有记忆重复
- `other` — 其他（必填 comment）

### Quick link

Add to `mission-control/index.tsx` QuickLinks grid.

---

## 8. Grounding integration

`AgentMemoryService.searchScoped` / `loadScopedByImportance` return `shadow_mode` column alongside existing fields. Grounding service (when assembling AuraBot prompt context):

- Include shadow memories in candidate set with a **prefix annotation**:
  ```
  [近期团队记忆 · 观察中] Month-end close cycle runs on the 28th...
  ```
- When AuraBot echoes the memory in its reply, it MUST preface with "根据团队近期记忆（尚在观察期）" so users can flag misinformation.
- Frontend shows a "这条不对" button on shadow memory references. Click → call `/retract` with a pre-filled reason.

ACTIVE memories have no special treatment — authoritative, pure quote.

---

## 9. Security / privacy

1. **PII detection** — v2 work. Reviewer responsibility in v1. Mandatory warning banner above the approve button:
   > 批准后，此内容对租户内所有成员可见。请确认不含个人信息、客户数据、合规敏感信息。

2. **User notification** — by default NOT notified (shadow bias). `acp.memory.promotion.notify_author=true` to opt in per tenant.

3. **GDPR forget-user** — existing `AgentMemoryService.forgetUser(tenantId, userId)` extended:
   - Soft-delete user-scope memories as today.
   - For each such memory where `promoted_from_pid IS NOT NULL` references appear in a tenant memory: log a warning, leave tenant memory intact (different data once generalised), emit metric `auraboot_memory_forget_user_preserved_tenant_memory_total` so ops notice.

4. **Retraction retention** — `RETRACTED` promotions retained 1 year for forensic trail.

5. **Reject reason as learning signal** — reject_reason aggregated per (tenant, reason_code) → pre-filter threshold: if `too_specific` rate > 40% for `cross_user_agreement`, auto-raise the similarity threshold from 0.85 → 0.90 for that tenant.

---

## 10. Metrics

### Counters

```
auraboot_memory_promotion_proposal_total{tenant, reason_code}
   reason_code ∈ {cross_user_agreement, implicit_co_sign, importance_spike, session_upgrade}
auraboot_memory_promotion_decision_total{tenant, decision, reason?}
   decision ∈ {APPROVE, REJECT, RETRACT, ACTIVATE, EXPIRE}
   reason ∈ reject_reason values (for REJECT only)
auraboot_memory_promotion_shadow_retraction_total{tenant}
auraboot_memory_forget_user_preserved_tenant_memory_total{tenant}
```

### Gauges

```
auraboot_memory_promotion_pending_count{tenant}
auraboot_memory_promotion_shadow_count{tenant}
auraboot_memory_promotion_reviewer_backlog_seconds{tenant}
   — age of oldest pending proposal; triggers MemoryReviewerStalled alert
```

### Grafana panels (new file `docs/operations/grafana-memory-promotion.json`)

- Proposal rate per reason_code (stacked area)
- Approval vs reject rate by confidence bucket (sanity: high-conf should mostly approve)
- Rejection reason distribution
- Pending queue depth (with threshold alert at >50)
- Shadow retraction rate (high rate = extractor is wrong; tune)

### Alerts (append to `docs/operations/learning-loop-alerts.yaml`)

- `MemoryReviewerStalled` — pending >50 for 7d
- `MemoryHighRejectionRate` — reject / (approve+reject) > 0.6 rolling 30d
- `MemoryShadowRetractionSpike` — retraction > 3 per tenant per week
- `MemoryExtractorIdle` — 0 proposals created in 14d on a tenant with >20 users

---

## 11. Test matrix

### Integration (real PG + pgvector if available)

- `MemoryPromotionExtractorIntegrationTest`
  - cross_user_agreement: 3 users, similarity 0.90 → 1 proposal, confidence >= 0.80
  - cross_user_agreement: 2 users → 0 proposals
  - cross_user_agreement: 4 users, similarity 0.70 → 0 proposals (below threshold)
  - implicit_co_sign: 1 author + 3 co-signers via last_accessed → 1 proposal
  - importance_spike (enabled): single user + shareable + importance=9 → 1 proposal confidence=0.5
  - dedup: re-run → no duplicate proposal for same cluster
  - advisory_lock: concurrent runOnce
  - embedding_fallback: without pgvector, Java cosine still produces proposal
  - tenant_isolation: two tenants independent
- `MemoryPromotionApplierIntegrationTest`
  - approve: creates shadow memory, shadow_mode=true, shadow_ends_at=+7d
  - reject: no memory, status=REVIEWED_REJECTED, reject_reason stored
  - retract: PROMOTED_SHADOW → RETRACTED, ab_agent_memory soft-deleted
  - retract_after_active: 409 (must be shadow)
  - concurrent_approve: first wins, second 409
- `MemoryPromotionActivatorIntegrationTest`
  - shadow_ends_at past → ACTIVE, ab_agent_memory.shadow_mode=false
  - not past → unchanged
- `MemoryPromotionExpirerIntegrationTest`
  - 31d pending → EXPIRED
  - 89d reject → unchanged
  - 91d reject → DISCARDED
- `MemoryPromotionControllerIntegrationTest`
  - list, stats, provenance chain, batch-approve (confidence filter), retract permission

### Unit

- `ConfidenceScorerTest`: verify the scoring formulas for each reason_code
- `EmbeddingSimilarityTest`: cosine edge cases (identical / orthogonal / null)

### Schema

- `MemoryPromotionSchemaIntegrationTest`: CHECK, FK, UNIQUE, partial indexes

### E2E (real backend, PR-64 helper pattern)

- `memory-promotions-real.spec.ts`:
  - MP-E2E-01 approve → shadow
  - MP-E2E-02 reject with reason dropdown
  - MP-E2E-03 retract during shadow window
  - MP-E2E-04 batch approve with confidence filter
- `memory-promotions.spec.ts` (mocked regression):
  - Keyboard shortcuts (`a` / `r` / `j` / `k`)
  - Confidence bar visual
  - Provenance modal render

---

## 12. Task breakdown (7 PRs, parallelisable where noted)

### Phase 1 — Schema + Embedding + Extractor (PR-65)
- Schema migration (table + shadow_mode + promoted_from_pid + pgvector optional)
- `EmbeddingProjector` utility + pgvector-or-java fallback
- `ConfidenceScorer` utility
- `MemoryPromotionExtractor` + 3 strategies + scheduled runner (advisory lock 7303)
- Extractor integration tests
- **Not parallel** — others depend on schema

### Phase 2 — Applier + Activator + Expirer (PR-66)
- `MemoryPromotionApplier` approve/reject/retract
- `MemoryPromotionActivator` shadow → active (lock 7304)
- `MemoryPromotionExpirer` daily cleanup
- `SessionMemoryConsolidationAuditRunner` retrofit
- Integration tests
- **Parallel with Phase 3 REST/metrics below**

### Phase 3 — REST + Metrics (PR-67)
- `MemoryPromotionController` all endpoints including provenance + batch-approve
- `MemoryPromotionMetrics` (counters + gauges)
- Controller integration tests
- **Parallel with Phase 2**

### Phase 4 — Mission Control UI + Grounding (PR-68)
- `/aurabot/memory-promotions` page with 3 tabs
- Keyboard shortcuts (custom hook + data-testid)
- Provenance modal
- Batch approve drawer
- Grounding service update: annotate shadow memories, emit "这条不对" button
- QuickLink tile
- TypeScript types generated / hand-written
- **After Phase 3 (REST ready)**

### Phase 5 — E2E + Ops (PR-69)
- Real-backend spec with helper extensions
- Mocked spec for UI regression
- Grafana dashboard JSON + alerts YAML
- `docs/core-concepts/memory-promotion.md` subsystem reference

### Phase 6 — Code review round 1 (PR-70+)
- Trigger after Phase 5 lands
- Multi-agent fix-up following Learning Loop convention

### Phase 7 — Live run + tuning (PR-71+)
- Enable scheduler in dev with real data
- Observe first 7-day cycle
- Tune thresholds (similarity, confidence weights, co-sign window)
- Release notes

Estimated: 7 PRs, ~1.5 weeks at parallel-worktree pace.

---

## 13. Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Embedding provider outage blocks extractor | M | M | Lazy computation + Java fallback for cosine on stored JSON vectors |
| Shadow period too short / too long | M | L | Start at 7d, instrument retraction rate; tune in Phase 7 |
| Reviewer never uses batch approve, pending grows | M | M | Confidence-DESC sort + 30d EXPIRE keeps queue bounded |
| `promoted_from_pid` dangling after forget-user | L | M | Documented in §9.3; follow-up may add cascade cleanup |
| pgvector not available in customer deploys | M | L | TEXT+JSON fallback mandatory; pgvector is optimization only |
| Wrong PII slips past reviewer into shadow | M | H | Shadow period + "这条不对" button catches within 7d; retraction metric triggers alert |
| Two extractor runs propose same cluster concurrently | L | L | Advisory lock 7303 + dedup check on `source_memory_pids` overlap |
| AI rationale hallucinates and biases reviewer | M | M | Optional; reviewer knows it's LLM-generated; source memories are primary evidence |

---

## 14. Acceptance criteria per phase

**Phase 1** (PR-65)
- [ ] Schema compiled + constraints present via `\d+`
- [ ] Extractor seeded with 3-user agreement cluster produces 1 proposal with confidence >= 0.70
- [ ] Embedding similarity computed for cross_user_agreement, stored in `similarity_score`
- [ ] Advisory lock 7303 prevents concurrent extraction (integration test)
- [ ] 15+ integration tests pass

**Phase 2** (PR-66)
- [ ] Approve flips draft → PROMOTED_SHADOW and creates `ab_agent_memory` row with `shadow_mode=TRUE`
- [ ] Activator flips shadow → active after 7d
- [ ] Retract during shadow soft-deletes memory row
- [ ] Expirer moves 30d+ pending to EXPIRED

**Phase 3** (PR-67)
- [ ] All endpoints return expected shapes
- [ ] `/provenance` returns full chain for a promoted memory
- [ ] `/batch-approve` rejects low-confidence entries correctly
- [ ] `/actuator/prometheus` shows all counters + gauges

**Phase 4** (PR-68)
- [ ] Mission Control page renders 3 tabs
- [ ] Keyboard shortcuts (`j/k/a/r`) work + have data-testid
- [ ] Shadow memory in AuraBot reply has prefix annotation
- [ ] All strings bilingual

**Phase 5** (PR-69)
- [ ] Real-backend E2E specs pass (4+ scenarios)
- [ ] Grafana JSON validates
- [ ] Subsystem doc published

**Phase 6** (PR-70+)
- [ ] Code review completes with ≤2 critical findings
- [ ] All critical findings resolved

**Phase 7** (PR-71+)
- [ ] Live operational verification: first proposal flows DRAFT → SHADOW → ACTIVE end-to-end with real data
- [ ] Thresholds tuned based on observed data
- [ ] Release notes published

Overall exit: Soul Profile plan (next round) can depend on stable tenant-scope memories with full provenance and shadow-mode discipline.

---

## 15. Open questions (all default-approved unless challenged)

1. **Shadow period**: 7 days default. Too long for fast-moving teams? Start at 3 days for tenants with < 5 active users.
2. **Embedding similarity threshold**: 0.85 default. Lower (0.80) yields more proposals at cost of more false positives. Decided in Phase 7 tuning.
3. **AI rationale**: optional but enabled by default. If LLM cost is a concern per tenant, `acp.memory.promotion.rationale.enabled=false` disables it.
4. **Importance_spike**: disabled by default. Tenant admin must opt in via config because single-user signal is weakest.

---

## Appendix A — Why this plan is excellence-first

- Every proposal carries **confidence + similarity score + AI rationale + full evidence list**. Reviewer has everything needed on one screen.
- **Shadow period** borrows from Learning Loop's proven pattern — caught errors in PR-54 would have produced real bugs without it. Applying the same discipline to memory.
- **Reject reason classification** turns every rejection into data that improves the next proposal. The system gets smarter with use, not noisier.
- **Provenance chain** means any tenant memory can be traced back to specific users + conversations. Compliance / audit / dispute resolution — day-1 ready.
- **Keyboard-driven reviewer workflow** + **batch approve** make a single-operator team genuinely keep up, without sacrificing thoroughness.
- **Shadow retraction** is the "这条不对" path — a user spotting misinformation in AuraBot's reply can pull the memory before it influences more decisions.
- Every threshold and weight is a tuning knob with instrumentation. Day-1 defaults are conservative; tuning is data-driven via Phase 7.

This is what "small team, big ambition" looks like in system design: no compromise on eventual correctness, but every feature dimensionable to actual current scale via configuration. Grows with the product, not replaced at scale.

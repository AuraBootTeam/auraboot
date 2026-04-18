# Memory Promotion (L1 → L2 → L3) — Design

**Status**: draft
**Owner**: ACP team
**Date**: 2026-04-18
**Depends on**: Learning Loop HITL scaffolding (PR-26 → PR-64)
**Blocks**: Soul Profile derivation (next round)

---

## 1. Motivation

The `AgentMemoryService` already models a 3-scope lattice:

- `scope='user'` — scoped to one user across sessions
- `scope='tenant'` — scoped to an organisation, readable by all members
- `scope='global'` — platform-wide, readable by everyone

`AgentMemoryConsolidationService.promoteSessionMemories` handles **session → user** (soft promotion: flips the scope tag when importance crosses a threshold). But:

1. There is **no** user → tenant promotion path.
2. The session → user path runs silently without a review step.
3. There is no audit trail for why a memory is at the scope it is.
4. Cross-user signals — e.g. three different users in the same tenant all wrote a memory saying "our month-end close happens on the 28th" — have no mechanism to become a tenant-level fact.

Soul Profile (next round) wants to derive a user's long-term persona from durable memories with provenance. Without a promotion pipeline, every extraction is one-shot over raw history — expensive, non-incremental, non-auditable.

This plan adds the missing promotion layer with the same HITL shape as the Learning Loop.

---

## 2. Current state

Existing surfaces we reuse:

- `ab_agent_memory` table with `scope` + `scope_key` columns (PR-13).
- `AgentMemoryService.createScopedMemory / searchScoped / loadScopedByImportance` — scope visibility contract already enforces the lattice.
- `AgentMemoryConsolidationService.promoteSessionMemories(tenantId, agentCode, importanceThreshold)` — single-step importance-threshold promoter.
- Mission Control HITL scaffolding (learning-drafts page, review endpoint pattern).
- `LearningLoopMetrics` Micrometer pattern.
- Advisory-lock `TransactionTemplate.execute` pattern (PR-59).
- `@Inherited` `DryRunSafe` pattern (reusable marker style).
- `PromotionEvaluator` / `PromotionEvaluationRunner` / scheduled runner shape.

Things we don't reuse:

- `ab_agent_skill_draft` is skill-specific; memory promotions need their own table.
- `ShadowExecutor` / dry-run pipeline isn't applicable — memory is data, not executable code.

---

## 3. Scope

### In scope

- **user → tenant** promotion: propose, review, approve → tenant-scope memory created.
- **session → user** upgrade: retain existing logic but add audit row (no behaviour change).
- Cross-user pattern detection: detect when multiple users in one tenant independently wrote semantically-equivalent memories; propose a single tenant-scope generalisation.
- Mission Control review UI + REST endpoints.
- Metrics + Grafana panels.
- Schema with CHECK/FK/unique constraints from day 1 (lessons learned from round-1 review).

### Out of scope

- **tenant → global** promotion — requires platform-level governance (superadmin review, multi-tenant signoff); explicit future work.
- Cross-tenant memory inference — privacy landmine, not attempted.
- Auto-rejection of promotions that contain PII — left to operator judgement in review; flagged as a follow-up.
- Retraction / unpromotion — if a tenant memory turns out to be wrong, operator edits or soft-deletes directly via the existing memory admin page.
- Edit-before-approve UX — first cut only supports approve-as-proposed / reject. If proposal needs fixing, reject + re-propose.

---

## 4. Data model

### New table: `ab_agent_memory_promotion`

```sql
CREATE TABLE IF NOT EXISTS ab_agent_memory_promotion (
    id                    BIGSERIAL PRIMARY KEY,
    pid                   VARCHAR(26) UNIQUE NOT NULL,
    tenant_id             BIGINT NOT NULL,
    source_scope          VARCHAR(16) NOT NULL,     -- 'user' (only supported value in v1)
    source_memory_pid     VARCHAR(26),              -- primary source; null for cross-user merges
    source_memory_pids    JSONB,                    -- full list for cross-user merge; null for 1:1
    target_scope          VARCHAR(16) NOT NULL,     -- 'tenant'
    category              VARCHAR(32) NOT NULL,
    proposed_title        VARCHAR(200),
    proposed_content      TEXT NOT NULL,
    proposed_importance   INTEGER DEFAULT 5,
    reason_code           VARCHAR(32),              -- 'cross_user_agreement' | 'explicit_pin' | 'importance_spike'
    reason_detail         JSONB,                    -- counts, user ids involved, etc.
    status                VARCHAR(32) NOT NULL DEFAULT 'DRAFT_PENDING_REVIEW',
    reviewer_id           BIGINT,
    review_comment        TEXT,
    promoted_memory_pid   VARCHAR(26),              -- set when status=PROMOTED
    created_at            TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    reviewed_at           TIMESTAMPTZ,
    promoted_at           TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_memory_promotion_status CHECK (status IN (
        'DRAFT_PENDING_REVIEW',
        'REVIEWED_OK',            -- intermediate, not used in v1 (no shadow equivalent)
        'REVIEWED_REJECTED',
        'PROMOTED',
        'DISCARDED'
    )),
    CONSTRAINT chk_memory_promotion_scope CHECK (
        source_scope IN ('user') AND target_scope IN ('tenant')
    ),
    CONSTRAINT fk_memory_promotion_source FOREIGN KEY (source_memory_pid)
        REFERENCES ab_agent_memory (pid) ON DELETE SET NULL
);

CREATE INDEX idx_memory_promotion_tenant_status
    ON ab_agent_memory_promotion (tenant_id, status);
CREATE INDEX idx_memory_promotion_created
    ON ab_agent_memory_promotion (created_at DESC);
```

`status` lattice:

```
DRAFT_PENDING_REVIEW
   ├── approve ──> PROMOTED        (creates the tenant-scope ab_agent_memory row)
   └── reject  ──> REVIEWED_REJECTED
                     │
                     └── manual ──> DISCARDED (cleanup signal, 90-day retention)
```

`REVIEWED_OK` exists in the CHECK constraint for symmetry with Learning Loop draft status but is not used in v1 (no shadow/dry-run step for memory). Keeping the value in the enum avoids a schema churn if we later add a "validate against retrieval benchmarks" step.

---

## 5. Services

### 5.1 `MemoryPromotionExtractor`

Scheduled job (default: nightly). Two extraction strategies:

**Strategy A — cross-user agreement**
- Query `ab_agent_memory` grouped by `(tenant_id, category, normalised_content_hash)` where `scope='user'`.
- If ≥ `min-users-per-tenant` (default 3) distinct `scope_key` values agree on the same `content_hash`, propose a single tenant-scope memory.
- `reason_code='cross_user_agreement'`, `reason_detail={"user_ids": [...], "agreement_count": N}`.

Normalised content hash: lowercase, trim, strip punctuation, SHA-256 of the resulting text. Good for verbatim agreements; doesn't catch paraphrases. Semantic dedup is future work.

**Strategy B — importance spike**
- User-scope memories with `importance >= 9` AND `shareable=true` AND `scope_key=user_X` in a tenant where at least one OTHER user has a tenant-write role.
- Reason: operator-flagged high-value note.
- `reason_code='importance_spike'`, `reason_detail={"user_id": X, "importance": N}`.

Runner loop (TransactionTemplate + advisory lock 7303):

```
runOnce():
  acquire advisory lock 7303
  for tenantId in active_tenants:
    proposals = Strategy A + Strategy B (tenantId)
    for proposal in proposals:
      if not already_proposed(source_memory_pid):
        insert ab_agent_memory_promotion row (status=DRAFT_PENDING_REVIEW)
        emit auraboot_memory_promotion_proposal_total{tenant, reason}
  release lock
```

Config:
```
acp.memory.promotion.scheduler.enabled=false   # off by default
acp.memory.promotion.scheduler.cron=0 30 3 * * *
acp.memory.promotion.min-users-per-tenant=3
acp.memory.promotion.min-importance-for-spike=9
```

### 5.2 `MemoryPromotionApplier`

Invoked synchronously by the review endpoint when an operator clicks "approve":

```
apply(promotionPid, reviewerId, comment):
  row = SELECT * WHERE pid=? AND tenant_id=? AND status='DRAFT_PENDING_REVIEW'
  if missing: return 409

  newMemoryPid = AgentMemoryService.createScopedMemory(
       tenantId=row.tenant_id, agentCode='default',
       memoryType='tenant_shared', category=row.category,
       title=row.proposed_title, content=row.proposed_content,
       importance=row.proposed_importance, shareable=true,
       scope='tenant', scopeKey=null
  )
  UPDATE ab_agent_memory_promotion
     SET status='PROMOTED', reviewer_id=?, review_comment=?,
         promoted_memory_pid=?, reviewed_at=NOW(), promoted_at=NOW()
     WHERE pid=? AND status='DRAFT_PENDING_REVIEW'

  # same optimistic guard pattern as PromotionEvaluator
  if affected_rows != 1: throw ConcurrentModificationException

  emit auraboot_memory_promotion_decision_total{decision=PROMOTE}
```

Reject path is simpler: status = REVIEWED_REJECTED, no memory created.

### 5.3 `MemoryPromotionController`

REST:

```
GET  /api/memory/promotions?status=DRAFT_PENDING_REVIEW&limit=50
GET  /api/memory/promotions/{pid}                       — detail + source memory preview
POST /api/memory/promotions/{pid}/review                — {decision: approve|reject, comment}
GET  /api/memory/promotions/stats                        — counts by status (for dashboard)
```

All tenant-scoped via `MetaContext.getCurrentTenantId()` — mirror `LearningLoopController`.

### 5.4 `SessionMemoryConsolidationAuditRunner`

Wraps the existing `AgentMemoryConsolidationService.promoteSessionMemories` to write an audit trail:

```
before: count pending session memories
  AgentMemoryConsolidationService.promoteSessionMemories(...)
after: diff → for each newly-promoted memory, INSERT an ab_agent_memory_promotion row
       with status='PROMOTED' (retroactive audit — promotion already happened)
```

No behaviour change; just fills the audit table so the UI shows session→user history too.

---

## 6. Mission Control UI

New page `/aurabot/memory-promotions` (filed next to learning-drafts / interrupts):

| Column | Source |
|--------|--------|
| created_at | row |
| reason badge | `reason_code` mapped to zh+en label |
| agreement count | `reason_detail.agreement_count` |
| category | row |
| proposed title | row |
| proposed content (truncated) | row |
| status badge | row |
| actions | approve / reject / expand |

Expanded row:
- Full `proposed_content`
- Source memories preview (joined from `ab_agent_memory` by pid) with each user's exact phrasing side-by-side
- Approve: textarea comment + submit → calls `/review` with `decision=approve`
- Reject: textarea comment + submit

All text via `l(zh, en)` helper per PR-57 convention.

### Quick link

Add to `web-admin/app/plugins/core-aurabot/pages/mission-control/index.tsx` QuickLinks alongside `learning-drafts` and `interrupts`.

---

## 7. Security / privacy

1. **PII detection**: out of scope for v1. Reviewer is responsible. Add a one-line warning banner on the review page: "Approving will make this content visible to all members of tenant X — verify no personal information is included."

2. **User opt-out**: a user whose memory is being proposed for promotion does NOT get notified in v1 (shadow transparency; could bias the decision). Future: add a "do not promote memories from this conversation" toggle per session.

3. **GDPR forget-user**: existing `AgentMemoryService.forgetUser(tenantId, userId)` soft-deletes that user's scope=user memories. Must extend: if any of those memories were promoted to tenant scope, the derived tenant memory is NOT automatically retracted (different data now). Document this in the forget flow; if stricter behaviour is required add a `source_memory_pids` lookup in forgetUser.

4. **Audit retention**: `ab_agent_memory_promotion` rows retained indefinitely (same as skill drafts). Provides forensic trail if a promoted tenant memory is later challenged.

---

## 8. Metrics

Two new Micrometer counters (extend `LearningLoopMetrics` pattern — create `MemoryPromotionMetrics` parallel class):

```
auraboot_memory_promotion_proposal_total{tenant, reason}
    reason ∈ {cross_user_agreement, importance_spike, session_upgrade}
auraboot_memory_promotion_decision_total{tenant, decision}
    decision ∈ {APPROVED, REJECTED, AUTO_PROMOTED (session→user), EXPIRED}
```

Grafana panels (append to existing `docs/operations/grafana-learning-loop.json` OR new `grafana-memory-promotion.json`):
- Pending review count (>72h alert)
- Proposal vs approval rate
- Decay curve of `AUTO_PROMOTED` per tenant

Alerts:
- `MemoryPromotionStalled` — >50 pending for >7d, reviewer attention needed
- `MemoryPromotionHighRejection` — rejection rate >60% for 30d → extractor needs tuning

---

## 9. Test matrix

### Integration (real PG, extend `BaseIntegrationTest`)

- `MemoryPromotionExtractorIntegrationTest`
  - cross_user_agreement: 3 users same normalised text → 1 proposal
  - cross_user_agreement: 2 users → 0 proposals (below threshold)
  - importance_spike: high-importance shareable user memory → proposal
  - dedup: re-run extractor → no duplicate proposal
  - tenant_isolation: two tenants independent
  - advisory_lock: concurrent runOnce
- `MemoryPromotionApplierIntegrationTest`
  - approve: creates tenant-scope memory + flips status
  - reject: no tenant memory, status=REVIEWED_REJECTED
  - concurrent_approve: first wins, second 409
  - unknown_pid: 404
  - cross_tenant_probe: 404
- `MemoryPromotionControllerIntegrationTest`
  - list with filter, stats endpoint, tenant isolation, limit clamping
- `SessionMemoryConsolidationAuditRunnerTest`
  - verifies retroactive audit rows written for session→user promotions

### E2E (real backend, extending PR-64 helper pattern)

- `memory-promotions-real.spec.ts`
  - MP-E2E-01 approve flow via sidebar click
  - MP-E2E-02 reject flow
  - MP-E2E-03 empty state

### Schema

- `MemoryPromotionSchemaIntegrationTest`
  - CHECK constraints on status / scope
  - FK source_memory_pid SET NULL on parent delete
  - unique pid

---

## 10. Task breakdown (Phase-wise)

### Phase 1 — Schema + Services (PR-65)
- Schema migration (table + constraints + indices)
- `MemoryPromotionExtractor` + config
- `MemoryPromotionApplier`
- `SessionMemoryConsolidationAuditRunner` retrofit
- Integration tests for all above

### Phase 2 — REST + Metrics (PR-66)
- `MemoryPromotionController` endpoints
- `MemoryPromotionMetrics` + call sites
- `MemoryPromotionEvaluationRunner` scheduled (advisory lock 7303)
- Controller integration tests

### Phase 3 — Mission Control UI (PR-67)
- `/aurabot/memory-promotions` page (React)
- `resources.ts` route + menu entry
- index.tsx QuickLink tile
- i18n via `l(zh, en)` from PR-57
- PII-warning banner

### Phase 4 — E2E + Ops (PR-68)
- `memory-promotions-real.spec.ts` with helper
- Mocked spec for UI regression
- Grafana JSON snippet + alert rules
- `docs/core-concepts/memory-promotion.md` subsystem reference

### Phase 5 — Audit closure (PR-69)
- First-week review: live data in dev env, any tuning of `min-users-per-tenant`
- Micrometer dashboards verified
- Release notes

Estimated: 5 PRs, ~1 week at parallel-worktree pace.

---

## 11. Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| Proposals flood the queue | M | M | Threshold tuning; 10-proposal-per-tenant-per-run cap |
| PII leaks through reviewer inattention | L | H | Warning banner; future: automated PII scanner |
| Normalised content hash misses obvious paraphrases | H | L | v1 is "good enough"; semantic dedup is future work, not blocking |
| Promoted tenant memory contradicts later user-level reality | M | M | Promoted rows stay visible; UI shows provenance; manual retract via admin |
| `ab_agent_memory_promotion` grows unbounded | L | L | Partial index `WHERE status IN (DRAFT_PENDING_REVIEW)`; archive rejects after 90d |
| Session upgrade path becoming inconsistent between old and new code | M | M | `SessionMemoryConsolidationAuditRunner` is a retrofit, no dual-write — only reads the post-state |
| Multi-node scheduler race (cf. round-2) | L | M | Advisory lock 7303 + unique `(tenant_id, source_memory_pid)` partial index |

---

## 12. Acceptance criteria

**Phase 1**
- [ ] `ab_agent_memory_promotion` exists with all constraints, verified via `\d+`
- [ ] Extractor unit runs create at least one proposal for a seeded 3-user-agreement scenario
- [ ] Applier creates a tenant-scope memory visible via `loadScopedByImportance`
- [ ] 10+ integration tests pass

**Phase 2**
- [ ] All REST endpoints return expected shape
- [ ] `curl /api/memory/promotions/stats` live on running backend shows stable counts
- [ ] Metrics counters emit in `/actuator/prometheus`

**Phase 3**
- [ ] `/aurabot/memory-promotions` lists seeded proposals
- [ ] Approve flow flips DB status and creates tenant memory
- [ ] All strings bilingual

**Phase 4**
- [ ] `memory-promotions-real.spec.ts` passes
- [ ] Grafana JSON validated
- [ ] Subsystem doc linked from README or learning-loop.md

**Phase 5**
- [ ] Round-1 code review run; ≤2 critical findings, all fixed
- [ ] Zero new Notable after round-2

Overall: Soul Profile plan (next round) can depend on `loadScopedByImportance` returning tenant-scope memories that have audited provenance.

---

## 13. Non-goals / explicit deferrals

- **Tenant → global promotion** — governance policy needs product signoff first.
- **Semantic dedup via embedding similarity** — needs vector store + embedding pipeline.
- **Auto-retract if source memory deleted** — we set source FK `ON DELETE SET NULL`, but the promoted tenant memory stays. Document this in the subsystem reference.
- **Edit-in-place during review** — first cut is approve-as-is OR reject. Edit support is a Phase 6+ follow-up.

---

## 14. Open questions for reviewer

1. Is `min-users-per-tenant=3` too high for early-stage tenants? Should it scale with tenant active-user count?
2. Should `importance_spike` require multi-user validation too, or is a single user's high-importance note sufficient signal?
3. For `SessionMemoryConsolidationAuditRunner`: retroactive audit rows have `status=PROMOTED` directly, which bypasses review. Is that the correct default for session→user (current behaviour is silent), or should we start requiring review for session→user too?

Defaults proposed above; open to change before Phase 1 starts.

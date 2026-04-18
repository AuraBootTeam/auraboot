# Memory Promotion Subsystem

The Memory Promotion pipeline lifts agent memories up the scope lattice —
from personal (`scope=user`) to team-level (`scope=tenant`) — with a
human-in-the-loop review gate, semantic deduplication, and a shadow
observation window before a promoted memory becomes authoritative.

Full design: [`docs/plans/2026-04/2026-04-18-memory-promotion-design.md`](../plans/2026-04/2026-04-18-memory-promotion-design.md).

## Lifecycle

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

Every transition writes back to `ab_agent_memory_promotion` and (for
approve/retract) updates the companion `ab_agent_memory` row's
`shadow_mode` / `deleted_flag` so Grounding can pick up the new state on
the next query.

## Sources and signals

Three extraction strategies, each nightly-scheduled under
`MemoryPromotionExtractor`:

| `reason_code`              | Source                                                                                             | Threshold                                                                                   |
| -------------------------- | -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `cross_user_agreement`     | User-scope memories across ≥ 3 distinct users in the same tenant with semantic-embedding agreement | Cluster min cosine ≥ 0.85; ≥ 3 users                                                        |
| `implicit_co_sign`         | One author + others accessing a `shareable=TRUE` user memory                                       | ≥ 3 co-signer accesses in trailing 90d (Phase 1 gap: access log — feeds lands post-PR-66) |
| `importance_spike`         | Single-user, `shareable=TRUE`, `importance ≥ 9`, tenant-admin author                               | Off by default; per-tenant opt-in                                                           |
| `session_upgrade` (audit)  | `AgentMemoryConsolidationService` promoting session → user                                         | Informational audit row; no review gate                                                     |

## Confidence scoring

`ConfidenceScorer` derives a `[0.00, 1.00]` score per proposal. Formulas
per plan §6.1:

- **cross_user_agreement**: `0.5 + 0.1 × min(agreement_count − 3, 5) + 0.2 × (similarity − 0.85) × 3.33`
  → typically 0.70 – 0.90
- **implicit_co_sign**: `0.6 + 0.1 × min(co_sign_count − 3, 4)`
  → typically 0.60 – 1.00
- **importance_spike**: `0.50` flat (single-user signal, deliberately moderate)
- **session_upgrade**: `1.00` (bypasses review; informational only)

Batch-approve rejects any pid whose `confidence_score < 0.80`.

## Shadow period

After approve, the promoted memory lands in `ab_agent_memory` with
`shadow_mode = TRUE`. For 7 days (`shadow_ends_at = shadow_started_at +
7d`), Grounding includes shadow memories in the candidate set **but
annotates them** so AuraBot prefaces its reply with
`根据团队近期记忆（尚在观察期）...`. End-users can click "这条不对"
which triggers `POST /api/memory/promotions/{pid}/retract` — soft-deletes
the memory row, transitions the promotion to `RETRACTED`, and increments
`auraboot_memory_promotion_shadow_retraction_total`.

`MemoryPromotionActivator` (runs every 30 min, advisory lock 7304) flips
`PROMOTED_SHADOW → ACTIVE` once `shadow_ends_at <= NOW()`, also clearing
`ab_agent_memory.shadow_mode` back to `FALSE`.

## Schedulers and advisory locks

All off by default — flip per environment:

| Property                                             | Job                          | Lock key | Default cron        |
| ---------------------------------------------------- | ---------------------------- | -------- | ------------------- |
| `acp.memory.promotion.scheduler.enabled`             | `MemoryPromotionExtractor`   | 7303     | `0 30 3 * * *`      |
| `acp.memory.promotion.activator.enabled`             | `MemoryPromotionActivator`   | 7304     | every 30 min        |
| `acp.memory.promotion.expirer.enabled`               | `MemoryPromotionExpirer`     | 7305     | daily               |
| `acp.memory.promotion.importance_spike.enabled`      | strategy C gating            | —        | false               |
| `acp.memory.promotion.rationale.enabled`             | AI rationale generation      | —        | true                |
| `acp.memory.promotion.min-users-per-tenant`          | cross-user-agreement min k   | —        | 3                   |
| `acp.memory.promotion.min-similarity`                | embedding cosine threshold   | —        | 0.85                |

`MemoryPromotionExpirer` (daily, lock 7305) transitions any
`DRAFT_PENDING_REVIEW` pending for more than 30 days to `EXPIRED`
(recorded as a soft reject with `reject_reason='other'`), and moves any
`REVIEWED_REJECTED` older than 90 days to `DISCARDED`.

## REST endpoints

Base path `/api/memory/promotions`. All endpoints tenant-scoped via
`MetaContext.getCurrentTenantId()`.

| Method | Path                    | Purpose                                                             |
| ------ | ----------------------- | ------------------------------------------------------------------- |
| GET    | `/`                     | List (filter by `status` + `reason`, sort; default `confidence_desc` on pending) |
| GET    | `/stats`                | Per-tenant counts by `status`, `reason_code`, `reject_reason` + backlog age |
| GET    | `/{pid}`                | Detail + joined source memories                                     |
| GET    | `/{pid}/provenance`     | Full chain: promotion ← source memories ← upstream promotions       |
| POST   | `/{pid}/review`         | `{decision: approve|reject, comment, reject_reason?}`               |
| POST   | `/{pid}/retract`        | `{reason}` — only valid in `PROMOTED_SHADOW`                        |
| POST   | `/batch-approve`        | `{pids: [...], comment}` — bulk approve, ≤ 50 pids, `confidence ≥ 0.80` |

Valid `reject_reason` values: `too_specific`, `contains_pii`, `outdated`,
`wrong`, `duplicate`, `other`. Mismatch returns HTTP 400.

Conflict codes: 404 not found, 409 state-guard violation (e.g. retract
on a non-shadow row, concurrent approve wins lost).

## Mission Control

Reviewer UI at **`/aurabot/memory-promotions`** (Phase 4, PR-68). Three
tabs: Pending Review (default, confidence DESC), Shadow Observation
(retract button), Audit History. Supports keyboard shortcuts (`j/k/a/r`)
and batch approve. Provenance modal surfaces the full chain in one
click.

Phase 4 also adds a QuickLink tile on `/aurabot/mission-control` so the
promotion backlog is visible from the central dashboard.

## Metrics

Exported via the Actuator Prometheus scrape endpoint
(`/actuator/prometheus`). See `docs/operations/grafana-memory-promotion.json`
for the Grafana dashboard and `docs/operations/learning-loop-alerts.yaml`
(rule group `auraboot.memory_promotion`) for the alert rules.

### Counters

- `auraboot_memory_promotion_proposal_total{tenant, reason_code}` — one
  per proposal emitted by the extractor. `reason_code ∈ {cross_user_agreement,
  implicit_co_sign, importance_spike, session_upgrade}`.
- `auraboot_memory_promotion_decision_total{tenant, decision, reason?}` —
  `decision ∈ {APPROVE, REJECT, RETRACT, ACTIVATE, EXPIRE}`. `reason`
  is the `reject_reason` enum value (REJECT only).
- `auraboot_memory_promotion_shadow_retraction_total{tenant}` — only
  incremented when a user (via "这条不对") retracts during the shadow
  window. Spikes indicate proposal-quality drift.

### Gauges

- `auraboot_memory_promotion_pending_count{tenant}` — current DRAFT
  queue depth. Feeds `MemoryReviewerStalled` alert.
- `auraboot_memory_promotion_shadow_count{tenant}` — current
  `PROMOTED_SHADOW` population (capacity planning).
- `auraboot_memory_promotion_reviewer_backlog_seconds{tenant}` —
  wall-clock age of the oldest `DRAFT_PENDING_REVIEW`. Red > 7d.

## Related PRs

| PR     | Scope                                                                        |
| ------ | ---------------------------------------------------------------------------- |
| PR-65  | Phase 1 — schema, embeddings, extractor + 3 strategies (proposal side)       |
| PR-66  | Phase 2 — `MemoryPromotionApplier` + Activator + Expirer + co-signer log      |
| PR-67  | Phase 3 — REST controller + metric gauges                                    |
| PR-68  | Phase 4 — Mission Control UI + Grounding shadow annotation                   |
| PR-69  | Phase 5 — real-backend E2E + Grafana + alerts + this subsystem doc           |
| PR-70+ | Phase 6 — multi-agent code review round 1                                    |
| PR-71+ | Phase 7 — live dev run, threshold tuning, release notes                      |

## Trade-offs and known limitations

- **Reviewer workload**: at high proposal volume the queue can outgrow a
  single reviewer. Confidence-DESC sort + 30-day EXPIRE keeps the queue
  bounded; escalation to per-tenant reviewer pool is Tier-2.
- **AI rationale may fail silently**: `MemoryPromotionExtractor.generateRationale`
  catches provider errors and stores `NULL` in `ai_rationale`. Reviewers
  MUST NOT rely on the rationale alone — the primary evidence is the
  source memories list. The UI renders a placeholder when null.
- **Embedding cost** is amortised on write, not per scan. Providers
  (`embedding` config type) being down delays extraction by at most one
  nightly cycle — the extractor lazily computes + stores embeddings on
  its next pass.
- **`record_count`-only match in downstream projection** — the Grounding
  integration counts reference hits without per-query attribution until
  `ActionRecorder` adds the upgrade (plan §11, Tier-2).
- **`promoted_from_pid` dangling after forget-user** — preserved
  deliberately; the tenant memory has generalised beyond the source. A
  metric (`auraboot_memory_forget_user_preserved_tenant_memory_total`)
  alerts ops when the ratio climbs above expected.
- **Shadow period uniform 7d** — short-running teams may want 3d;
  long-compliance teams may want 14d. Tier-2 makes this per-tenant.
- **`pgvector` optional** — when the extension is unavailable,
  `EmbeddingSimilarity` falls back to pure-Java cosine over a
  JSON-serialised vector stored in TEXT. Correct, just slower.

## Phase 5 verification (PR-69)

### Enable the schedulers in dev

Set in `platform/src/main/resources/application-dev.yaml` (or
`application.yaml` for a one-off run):

```yaml
acp:
  memory:
    promotion:
      scheduler:
        enabled: true
      activator:
        enabled: true
      expirer:
        enabled: true
      # Optional: opt into importance_spike for tenants whose admins have
      # produced at least one high-value memory.
      importance_spike:
        enabled: false
      min-users-per-tenant: 3
      min-similarity: 0.85
```

Restart the backend, then watch the Prometheus endpoint:

```bash
curl -s http://localhost:6443/actuator/prometheus | grep auraboot_memory_promotion
```

### First-week Grafana watchpoints

Import `docs/operations/grafana-memory-promotion.json` and observe:

1. **Proposal rate per reason_code** — expect cross_user_agreement to
   dominate; implicit_co_sign will be low until PR-66 access log feeds
   real data; importance_spike only if enabled.
2. **Decisions by outcome** — APPROVE vs REJECT ratio over 15 min. If
   REJECT > APPROVE for the first week the extractor is too loose —
   tune `min-similarity` up.
3. **Reviewer backlog — oldest pending** — should track under 48h for a
   healthy single-reviewer tenant. Red line at 168h (7d) triggers the
   `MemoryReviewerStalled` alert.
4. **Shadow retraction rate** — any nonzero value is signal. Three or
   more per tenant per week triggers `MemoryShadowRetractionSpike`.

### When to tune

- **Too few proposals** (`MemoryExtractorIdle` firing): drop
  `min-similarity` from 0.85 → 0.80 **or** `min-users-per-tenant` from 3
  → 2. Only for tenants with < 10 users — smaller pools need looser
  thresholds.
- **Too many rejections** (`MemoryHighRejectionRate`, reject rate > 60 %):
  raise `min-similarity` 0.85 → 0.90 for that tenant. Look at the
  rejection-reason pie — if `too_specific` dominates, the category
  filter is too coarse; if `duplicate`, dedup against existing
  tenant-scope memories is weak.
- **Retraction spikes**: the proposal strategy is producing
  false-positive agreements. Start by widening
  `min-users-per-tenant` 3 → 4.

### Test artifacts shipped in PR-69

- `web-admin/tests/e2e/aurabot/ai-memory-promotions-real.spec.ts` — 5
  real-backend E2E scenarios: approve, reject-with-reason, retract,
  batch-approve, provenance modal.
- `web-admin/tests/e2e/aurabot/_real-backend-helpers.ts` — extended
  with `seedMemoryPromotion`, `seedMemoryPromotionWithPromotedMemory`,
  `dbPromotionRow`, `cleanupPromotions`, `seedMemoryPromotionsMenu`.
- `docs/operations/grafana-memory-promotion.json` — 7-panel dashboard.
- `docs/operations/learning-loop-alerts.yaml` — four new rules under
  `auraboot.memory_promotion`.

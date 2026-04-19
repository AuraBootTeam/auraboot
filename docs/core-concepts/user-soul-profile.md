# User Soul Profile — Subsystem Reference

**Status**: Phases 1-9 shipped; design for L1→L2 landed.
**Plan**: [2026-04-19 design](../plans/2026-04/2026-04-19-user-soul-profile-design.md)
**Follow-up design**: [Memory L1→L2 promotion](../plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md)

Per-user, dynamically-derived personalisation profile used to ground AuraBot responses. Distinct from `ab_agent_definition.soul_profile` (the **Agent** Soul Profile — manually authored per-agent persona) — this profile captures what the LLM should know about the **user**: persona, communication style, domain vocabulary, working hours, recurring habits, expertise, boundaries, preferred language.

## Terminology

| Name | Scope | Storage | Source |
|------|-------|---------|--------|
| **Agent** Soul Profile | per-agent, static | `ab_agent_definition.soul_profile JSONB` | manual config |
| **User** Soul Profile *(this doc)* | per-user, dynamic | `ab_agent_user_soul_profile` (full table) | derived from user memories + actions |

## Status lattice

```
DRAFT ──24h shadow──> ACTIVE ──superseded by new version──> SUPERSEDED
  │
  └── user forget / admin forget ──> ARCHIVED (+ tombstone row; deriver skips forever)
```

A `UNIQUE` partial index guarantees **at most one** `ACTIVE` row per `(tenant_id, user_id)` at all times — the `Activator` atomically demotes prior ACTIVE and promotes the new DRAFT.

## Derivation

**Sources**: `ab_agent_memory` with `scope='user'` (default 90d look-back) + `ab_agent_action` rows (target-model + action_type frequency).

**Projection** (pure Java, `ProfileProjector.project`): persona text, preferences (communication_style / domain_vocabulary / working_hours), habits.recurring_actions, expertise.domains, boundaries, language.

**Confidence** (`ProfileConfidenceScorer`): per-field formulas clamped to [0, 1]; `aggregateMin` yields profile-level score.

**Canonical hashing** (`ProfileHasher`): SHA-256 over canonical JSON (strips timestamps). Re-derivation with identical inputs → `skipped_no_change`.

## Schedulers

All gated by `acp.user.soul-profile.*.enabled` (default `false`); each uses `TransactionTemplate.execute` to pin a JDBC connection for the advisory lock.

| Service | Cron | Lock | Role |
|---------|------|------|------|
| `UserSoulProfileDeriver` | `0 0 4 * * *` | `7306` | Gather → project → hash → write DRAFT |
| `UserSoulProfileActivator` | `0 */30 * * * *` | `7307` | DRAFT → ACTIVE after 24h; demote prior ACTIVE |
| `UserSoulProfileStalenessDetector` | `0 30 4 * * *` | `7308` | Embed profile vs recent memories; flag divergent |

## User control — Editor

`UserSoulProfileEditor` methods:

| Method | Effect |
|--------|--------|
| `pin(tenantId, userId, field)` | `edited_fields[field] = "locked"` — survives re-derivation |
| `hide(tenantId, userId, field)` | `edited_fields[field] = "hidden"` — Reader omits from prompt |
| `edit(tenantId, userId, field, text)` | Stores `{override_text, edited_at}` |
| `reset(tenantId, userId, field?)` | Removes one key (or all when null) |
| `hideProfile(tenantId, userId)` | Sets `hidden_at` — Reader returns `Optional.empty` |
| `forgetProfile(tenantId, userId)` | GDPR cascade: archive + tombstone `{_forgotten: true}`. Idempotent. |

Deriver guards on tombstone presence → `skipped_forgotten`.

## Grounding integration

`UserSoulProfileReader.loadForGrounding(tenantId, userId)` → `Optional<ProfileSection>`:

- Reads the ACTIVE row (respects `hidden_at`).
- Merge semantics: hidden > override > raw.
- Renders a ≤500-char prompt block.
- Stale flag → appends `⚠️ This profile may be outdated`.

Injection sites:
- `AgentRunService.loadMemorySection` — prepends above `## Agent Memory`
- `AuraBotChatService.streamChat` — prepends after `buildSystemPrompt`

Null `userId` (system / cron) → no injection.

## REST endpoints

User-facing (tenant + user scoped via `MetaContext`):

```
GET  /api/user/soul-profile                   — own ACTIVE profile
GET  /api/user/soul-profile/history           — own SUPERSEDED / ARCHIVED versions
GET  /api/user/soul-profile/{pid}             — own specific version (404 if cross-user)
GET  /api/user/soul-profile/export            — GDPR data portability (full JSON dump, Content-Disposition: attachment)
POST /api/user/soul-profile/pin
POST /api/user/soul-profile/hide
POST /api/user/soul-profile/edit
POST /api/user/soul-profile/reset
POST /api/user/soul-profile/hide-profile
POST /api/user/soul-profile/forget            — GDPR forget, idempotent
POST /api/user/soul-profile/derive-now        — manual trigger, rate-limited 1/24h per user
```

Admin-facing returns **metadata only** (SQL explicitly excludes `profile`, `edited_fields`, `source_memory_pids`):

```
GET  /api/admin/user-soul-profiles            — paginated list
GET  /api/admin/user-soul-profiles/stats      — counts + staleness + avg confidence
POST /api/admin/user-soul-profiles/forget     — admin forget-user cascade ({userId, reason}; idempotent)
```

The admin `/forget` endpoint delegates to `UserSoulProfileEditor.forgetProfile` (same cascade as the user's own `/forget`) and records
`auraboot_user_soul_profile_admin_forget_total{tenant, reason}`. The response carries tombstone metadata only — no profile content.

## Mission Control UI

| Route | Audience | Purpose |
|-------|----------|---------|
| `/aurabot/my-profile` | end user | View / pin / hide / edit / reset / forget own profile; History tab; re-derive button |
| `/aurabot/soul-profiles` | tenant admin | Metadata-only dashboard |

## Metrics

**Counters**:
```
auraboot_user_soul_profile_derivation_total{tenant, outcome}
  outcome ∈ {drafted, skipped_no_change, skipped_too_little_signal, skipped_forgotten, failed}
auraboot_user_soul_profile_activation_total{tenant}
auraboot_user_soul_profile_stale_flagged_total{tenant}
auraboot_user_soul_profile_user_edit_total{tenant, action}
  action ∈ {pin, hide, edit, reset, hide_profile, forget}
auraboot_user_soul_profile_manual_derive_total{tenant, outcome}
  outcome ∈ {triggered, rate_limited}
auraboot_user_soul_profile_admin_forget_total{tenant, reason}
  reason ∈ {gdpr_request, account_closed, policy_violation, other, ...}
auraboot_user_soul_profile_read_total{tenant}  (emitted by Reader)
```

**Gauges (cross-tenant aggregate)**:
```
auraboot_user_soul_profile_active_count
auraboot_user_soul_profile_stale_count
auraboot_user_soul_profile_avg_confidence
```

## Operations

### Enabling a tenant

1. Opt-in: set `acp.user.soul-profile.derivation.enabled=true` + `activator.enabled=true` + `staleness.enabled=true`.
2. Users need ≥ 3 high-importance user-scope memories before first derivation.
3. First ACTIVE row lands 24h after first successful DRAFT.

### Tuning knobs

| Property | Default | Tune when |
|----------|---------|-----------|
| `min-memories-for-derivation` | 3 | Raise to 5+ for less-noisy profiles on very-active tenants |
| `look-back-days` | 90 | Shorten to 30 for high-velocity tenants |
| `shadow-period-hours` | 24 | Shorten to 3 for tenants with < 5 active users |
| `staleness.min-divergent-memories` | 3 | Raise if false-positive rate too high |
| `staleness.divergence-cosine-threshold` | 0.6 | Tighten to 0.7 for stricter staleness |

### First-week watch list

- Derivation failure rate
- User edit rate (> 50% → threshold tuning needed)
- Stale count growth (monotonic → detector too sensitive)
- Manual-derive rate-limit hits (> triggers → 24h window too tight)

## Known limitations

- LLM rendering for prose fields is **template-only** in Phase 1 — quality ceiling limited until dedicated rendering lands.
- Cross-user clustering explicitly deferred (privacy).
- Admin dashboard metadata only; no content visibility even for debugging.
- Manual-derive rate limit uses in-process Caffeine cache → multi-instance deployments may allow 1 derive per instance per 24h.
- Access-log silently skips hard-deleted memories (FK cascade); intentional.

## Related PRs

| PR | Phase | Scope |
|----|-------|-------|
| 75 | 1 | Schema + projector + scorer + hasher + deriver + metrics |
| 76 | 2 | Activator + StalenessDetector + Editor (+ tombstone path) |
| 77 | 3 | Reader + grounding injection |
| 78 | 4 | REST controllers + gauges + Caffeine rate limiter |
| 79 | 5 | Mission Control UI + mocked E2E |
| 80 | 6 | Real-backend E2E + Grafana dashboard + alerts + this doc |
| 81 | 9 | JSON export (GDPR portability) + admin forget-user cascade |

### Follow-up fixes (post-merge, bundled)

Round-1 review fixes + enum migration were not separate PRs; these commits land on top of the Phase 1-9 merges:

| Commit | Scope |
|--------|-------|
| `2cf6c6e2` | fix: SUPERSEDED edit rejection + ObjectMapper DI + idempotency test |
| `743c935b` | refactor: status enum lowercase (DB values standardised per red line) |
| `5102a5a6` | fix: Reader `readJson` no silent fallback + `byPid` excludes ARCHIVED |

## Privacy boundary

| Actor | Sees |
|-------|------|
| User (self) | Own profile content + history + edits + can forget |
| Tenant admin | Metadata only — never content |
| System / scheduler | Full profile within advisory-lock scope |
| AuraBot / LLM | Current ACTIVE profile rendered as ≤500-char prompt block |

Enforced at three layers: JWT (user scope), controller `MetaContext.getCurrentUserId()` guard, SQL column-list projection in the admin query.

## Related docs

- Peer subsystem: [`memory-tier-promotion.md`](./memory-tier-promotion.md) — L1→L2 lifecycle promoter. The Reader here benefits from promoted L2 memories since `UserSoulProfileDeriver` consumes `scope='user'` rows regardless of `category`; tier promotion boosts signal quality.

## Related dashboards + alerts

- Grafana: `docs/operations/grafana-user-soul-profile.json` (7-panel dashboard)
- Prometheus alerts: `docs/operations/learning-loop-alerts.yaml` group `auraboot.user_soul_profile` (4 rules)

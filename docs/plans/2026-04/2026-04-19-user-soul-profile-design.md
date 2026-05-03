# User Soul Profile — Design

**Status**: draft
**Owner**: ACP team
**Date**: 2026-04-19
**Depends on**: Memory Promotion (PR-65..74), Learning Loop HITL
**Supersedes**: none (new subsystem)

---

## Terminology disambiguation (read first)

There are already TWO "profile" concepts in the codebase. This plan introduces a THIRD. Don't confuse them.

| Name | Scope | Source | Storage | Status |
|------|-------|--------|---------|--------|
| **Agent Soul Profile** | per-agent, static | manual config | `ab_agent_definition.soul_profile JSONB` | EXISTS since ACP bootstrap |
| **Agent Hint / BIF pre-context** | per-session, dynamic | Grounding preRecall | transient | EXISTS (Active Memory) |
| **User Soul Profile** *(this plan)* | **per-user, dynamic, derived** | user memory history + action patterns | `ab_agent_user_soul_profile` (new table) | ← NEW |

User Soul Profile (USP) describes the **end user** — their preferences, communication style, domain expertise, workflow habits. Derived, versioned, user-controllable.

To keep prose readable, "Soul Profile" alone means User Soul Profile in this document. Agent Soul Profile always gets the "Agent" qualifier.

---

## Strategic framing

Shipping Soul Profile now would have been premature three months ago: extractions from raw session history are expensive, non-incremental, and non-auditable. Memory Promotion just shipped — every durable fact a user generates now has provenance, a confidence score, an audit trail, and a shadow-period uncertainty marker. Soul Profile becomes a **projection** over that corpus, not an LLM safari over raw conversation logs.

Design principles:

1. **Derivation is compression, not invention.** USP is a summary of what's already in the user's scoped memory. No LLM fabrication of traits not evidenced.
2. **Every assertion is citable.** Each field in the rendered USP links back to the source memories that justified it.
3. **User-visible + user-editable.** Unlike background agent hints, the user can see + correct their profile. False assertions get pruned at the source.
4. **Gracefully absent, gracefully stale.** No USP at all is fine (new user) — grounding falls back to `ActiveMemory` snippets. An 8-week-old USP is still usable but flagged stale.
5. **No new extraction quality bar.** Reuses the confidence machinery from Memory Promotion — nothing new to tune.

This is the "product-visible" layer of Learning Loop + Memory Promotion: the user opens AuraBot on Monday morning and it already knows they prefer bullet-point answers, use Vim, close books on the 28th, and only deploy on Tuesdays.

---

## 1. Motivation

Without USP, AuraBot has two grounding signals per turn:
- Top-N high-importance memories returned by `ActiveMemoryService.preRecall`
- BIF pre-context (session scratchpad)

Gaps this creates:

- **Redundant LLM token budget**: 20 disparate memory snippets at 80 tokens each = 1600 tokens of grounding, when a 200-token compact profile + top-3 specific memories would outperform.
- **No personality continuity**: a user who says "keep it short, I'm a dev" in conversation A has no way for that trait to persist into conversation B without being in a top-N recall.
- **No UI for the user's accumulated self**: the user has no view into "what does AuraBot think it knows about me?" → trust gap.
- **Soul Profile (agent) doesn't cover it**: agent.soul_profile describes the Agent. We need the symmetric concept for the user.

---

## 2. Current state

Reusable infrastructure already in place:

- `AgentMemoryService.loadScopedByImportance(tenantId, userId, agentCode, limit)` — top-N user-scope memories (PR-13).
- `AgentMemoryService.searchScoped(..., query, limit)` — keyword search.
- `ab_agent_memory_promotion` — audit trail of user→tenant promotions (PR-65).
- `ab_agent_memory_access_log` (PR-66/73) — per-(memory, user, day) access frequency.
- `MemoryEmbeddingService` (PR-65, PR-74) — embedding provider + normalisation + dim validation.
- `LlmProviderFactory` — tenant-scoped LLM access.
- `MemoryPromotionMetrics` + Grafana + alerts pattern — observability template.
- Mission Control 3-tab page pattern (`learning-drafts`, `memory-promotions`) — UI template.
- `ActiveMemoryService.snippet()` + `applyShadowMarker` — prompt-context shaping primitives.
- `SoulProfileParser` (unrelated: parses Agent soul_profile) — confirms the prompt-section shape we should mirror.
- `AgentRunService.loadAgentMemories` — the injection site for Soul Profile into the LLM prompt (PR-72).

---

## 3. Scope

### In scope (v1)

- Nightly derivation of `user_soul_profile` from recent (90d) user-scope memories + action history.
- Structured JSONB storage (not free text) with provenance per field.
- Grounding injection at the top of the prompt context for chat sessions.
- Mission Control "My Profile" page for the user to view, edit, pin, or hide fields.
- Deprecation / supersession of earlier profile versions (history retained for audit).
- Language preference auto-detection from the user's actual message history.
- Metrics + Grafana + alerts.

### Explicit deferrals (v2+)

- Cross-user similarity clustering ("users like you also...") — privacy-problematic; out.
- Soul Profile for anonymous / unauthenticated users.
- Tenant-level aggregate personas (team-wide tone). Memory Promotion already covers tenant facts; personality at team scope is a different product concept.
- Predictive prompting based on profile (e.g. "Since you prefer bullets, want me to reformat?"). v1 is read-only-by-LLM.
- Integration with BIF v2 pre-context.
- Sentiment / relationship dynamics tracking.
- Multi-agent profile adaptation (user may interact differently with CRM agent vs HR agent — v1 uses one unified profile).

---

## 4. Data model

### New table: `ab_agent_user_soul_profile`

```sql
CREATE TABLE IF NOT EXISTS ab_agent_user_soul_profile (
    id                BIGSERIAL PRIMARY KEY,
    pid               VARCHAR(26) UNIQUE NOT NULL,
    tenant_id         BIGINT NOT NULL,
    user_id           VARCHAR(64) NOT NULL,

    -- Versioning: one ACTIVE row per (tenant, user); older versions stay for audit
    version           INTEGER NOT NULL DEFAULT 1,
    status            VARCHAR(16) NOT NULL DEFAULT 'DRAFT',  -- DRAFT | ACTIVE | SUPERSEDED | ARCHIVED

    -- Derived profile (rendered JSONB, see §5 schema)
    profile           JSONB NOT NULL,
    profile_hash      VARCHAR(64) NOT NULL,   -- sha256 of canonical profile — dedup same-content re-derivations
    language_preference VARCHAR(8),            -- 'zh-CN', 'en-US', ...  (convenience, duplicated from profile.language)

    -- Derivation audit
    source_memory_pids JSONB,                 -- array of ab_agent_memory.pid that contributed
    source_action_count INTEGER DEFAULT 0,    -- number of ab_agent_action rows scanned
    source_window_days INTEGER DEFAULT 90,    -- look-back window used
    derivation_model   VARCHAR(64),           -- LLM model name + version
    derivation_confidence NUMERIC(3,2),       -- [0.00, 1.00] — min of per-field confidences

    -- User control
    edited_fields JSONB,                      -- map {field: "locked"|"hidden"|timestamp} — user overrides
    hidden_at TIMESTAMPTZ,                    -- user-requested soft delete

    -- Staleness + lifecycle
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_at TIMESTAMPTZ,                 -- when status flipped DRAFT → ACTIVE
    superseded_at TIMESTAMPTZ,                -- when a newer version replaced this
    stale_flagged_at TIMESTAMPTZ,             -- set when recent memories contradict this profile

    CONSTRAINT chk_user_soul_profile_status CHECK (status IN (
        'DRAFT', 'ACTIVE', 'SUPERSEDED', 'ARCHIVED'
    )),
    CONSTRAINT chk_user_soul_profile_confidence CHECK (
        derivation_confidence IS NULL OR
        (derivation_confidence >= 0 AND derivation_confidence <= 1)
    )
);

-- Only ONE ACTIVE row per (tenant, user) at a time
CREATE UNIQUE INDEX uq_user_soul_profile_active ON ab_agent_user_soul_profile (tenant_id, user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_user_soul_profile_tenant_user ON ab_agent_user_soul_profile (tenant_id, user_id, status);
CREATE INDEX idx_user_soul_profile_stale ON ab_agent_user_soul_profile (stale_flagged_at)
    WHERE stale_flagged_at IS NOT NULL AND status = 'ACTIVE';
```

### Profile JSONB schema

```json
{
  "schema_version": "1.0",
  "persona": {
    "text": "Engineer in e-commerce; tenant admin; pragmatic tone.",
    "source_memory_pids": ["M01", "M07"],
    "confidence": 0.82,
    "last_derived_at": "2026-04-19T03:30:00Z"
  },
  "preferences": {
    "communication_style": {"text": "concise bullet points; code examples welcome", "source_memory_pids": [...], "confidence": 0.91},
    "domain_vocabulary": {"text": ["SKU", "月结", "PO"], "source_memory_pids": [...], "confidence": 0.85},
    "working_hours":    {"text": "09:00-19:00 Asia/Shanghai", "source_memory_pids": [...], "confidence": 0.76}
  },
  "habits": {
    "recurring_actions": [
      {"pattern": "月底对账", "frequency": "monthly", "source_action_count": 8, "last_seen": "2026-03-28"},
      {"pattern": "每周一 standup", "frequency": "weekly", "source_action_count": 24, "last_seen": "2026-04-15"}
    ]
  },
  "expertise": {
    "domains": [
      {"name": "inventory management", "confidence": 0.88, "evidence_count": 23},
      {"name": "SQL analytics",        "confidence": 0.79, "evidence_count": 11}
    ]
  },
  "boundaries": {
    "text": "never auto-approve commit-level changes; always confirm before sending external email",
    "source_memory_pids": [...],
    "confidence": 0.95,
    "user_pinned": true
  },
  "language": "zh-CN",
  "meta": {
    "derivation_window_days": 90,
    "derivation_run_id": "01HXY...",
    "embedding_model": "text-embedding-3-small",
    "llm_model": "gpt-4o-mini"
  }
}
```

Every assertion carries:
- `text` (what the profile claims)
- `source_memory_pids` (provable origin)
- `confidence` (from the same scorer as Memory Promotion)

Fields a user has pinned OR hidden keep the `user_pinned: true` / `hidden: true` flag and are preserved across re-derivations (§5.3).

---

## 5. Services

### 5.1 `UserSoulProfileDeriver`

Scheduled nightly (cron `0 0 4 * * *`, lock key `7306`). Core loop per tenant+user:

```
derive(tenantId, userId):
  # 1. Skip if a recent draft exists (avoid double-run)
  if exists_draft_within(tenantId, userId, last=6h): return

  # 2. Gather inputs
  memories = loadScopedByImportance(tenantId, userId, "default", limit=50)
  if memories.size() < 3:
    # Too little signal; no profile yet, or keep old one.
    return

  actions = loadRecentActions(tenantId, userId, days=90, limit=200)
  priorProfile = loadActiveProfile(tenantId, userId)  # may be null

  # 3. Project into per-field candidates (pure Java — no LLM yet)
  persona_candidates          = projectPersona(memories)      # heuristic
  preference_candidates       = projectPreferences(memories)  # keyword + category aggregation
  habits                      = projectHabits(actions)        # action frequency + pattern
  expertise                   = projectExpertise(actions)     # action target_model diversity + depth
  boundary_memories           = filterByCategory(memories, "boundary")
  language                    = detectLanguage(memories, actions)

  # 4. LLM-render the free-text fields (persona.text, preferences.*.text, boundaries.text)
  #    LLM is called ONCE per derivation with all structured evidence; asked to
  #    produce concise prose that faithfully summarises the candidates.
  rendered = llmRenderProfile(candidates, priorProfile, userEditedFields)

  # 5. Compute confidence per field via ConfidenceScorer
  #    (reusing Memory Promotion's scorer; weights: evidence_count + recency + importance)

  # 6. Canonical hash + dedup
  newHash = sha256Canonical(rendered)
  if priorProfile != null and priorProfile.profile_hash == newHash:
    return  # no change worth writing

  # 7. Preserve user-pinned fields across re-derivation
  merged = mergeWithUserEdits(rendered, priorProfile.edited_fields)

  # 8. Persist as DRAFT; activator promotes draft → ACTIVE after a 24h shadow
  insertDraft(tenantId, userId, merged, version=priorProfile.version+1)
  emit metric auraboot_user_soul_profile_drafted_total{tenant}
```

Why shadow-like period before DRAFT → ACTIVE: if the derivation introduces a wrong trait, the user gets 24h to notice + correct before the LLM starts asserting it. Symmetric with Memory Promotion shadow mode; reuses the same mental model.

Gates:
- `acp.user.soul_profile.derivation.enabled` (default false initially — opt-in per tenant)
- `acp.user.soul_profile.min-memories-for-derivation` (default 3)
- `acp.user.soul_profile.look-back-days` (default 90)
- `acp.user.soul_profile.shadow-period-hours` (default 24)
- `acp.user.soul_profile.llm.enabled` (default true; if false, skip the rendering step and produce structured-only draft)

### 5.2 `UserSoulProfileActivator`

Scheduled every 30 min (lock `7307`). Flips DRAFT → ACTIVE for rows past `created_at + 24h`. Supersedes the previous ACTIVE row by setting its status=SUPERSEDED.

### 5.3 `UserSoulProfileEditor` (user-control service)

Called from the Mission Control REST endpoint:

```
pin(tenantId, userId, field):   # user confirms "keep this trait across re-derivations"
hide(tenantId, userId, field):  # user asks for the field to be suppressed entirely
edit(tenantId, userId, field, newText):  # user overrides the text
reset(tenantId, userId, field): # remove user override; re-derivation resumes full control
forgetProfile(tenantId, userId):  # GDPR forget — soft-delete ALL versions, prevent future derivation
```

User edits write to `edited_fields` on the ACTIVE row. Next derivation reads these and preserves them.

### 5.4 `UserSoulProfileStalenessDetector`

Scheduled daily (lock `7308`). For each ACTIVE profile:
- Find memories added in last 7d with importance ≥ 7.
- Embed each and compare cosine to the profile's persona + preferences embedding.
- If ≥ 3 recent memories diverge (cosine < 0.6) from the active profile, set `stale_flagged_at = NOW()`.

Stale flag DOESN'T auto-expire or re-derive — it's a signal for the user ("your profile might be outdated, review?") and for the deriver (priority-boost in next run).

### 5.5 `UserSoulProfileReader` (grounding integration)

Called by `AgentRunService.loadAgentMemories` + `ActiveMemoryService.preRecall`:

```
loadForGrounding(tenantId, userId) → Optional<ProfileSection>:
  profile = SELECT ... WHERE tenant_id=? AND user_id=? AND status='ACTIVE' AND hidden_at IS NULL
  if profile == null: return empty

  # Render prompt section — similar to SoulProfileParser.toPromptSection pattern
  return renderPromptSection(profile, userEditedFields)
```

The rendered section prepends to the LLM system prompt:

```
## About this user (User Soul Profile, derived 2026-04-19)
- Persona: {persona.text}
- Preferences: {preferences.*.text joined}
- Habits: {habits.recurring_actions summary}
- Language: {language}
- Constraints: {boundaries.text}

Note: the user can see + edit this profile. Do not quote it verbatim.
If asked about it, direct them to /aurabot/my-profile.
```

If `stale_flagged_at` is set, the section gets a trailing line:
```
⚠️ This profile may be outdated; prefer recent memories over the profile when they conflict.
```

### 5.6 `UserSoulProfileController`

REST endpoints (tenant-scoped via `MetaContext`, user-scoped via `MetaContext.getCurrentUserId()`):

```
GET  /api/user/soul-profile                   — active profile for the current user
GET  /api/user/soul-profile/history           — list superseded versions (pagination, limit 20)
GET  /api/user/soul-profile/{pid}             — specific version (own only; 404 otherwise)
POST /api/user/soul-profile/pin               body: {field}
POST /api/user/soul-profile/hide              body: {field}
POST /api/user/soul-profile/edit              body: {field, text}
POST /api/user/soul-profile/reset             body: {field?}  — null = reset all overrides
POST /api/user/soul-profile/forget            — GDPR forget
POST /api/user/soul-profile/derive-now        — manual trigger (rate-limited 1/day)

# Admin-only (with appropriate permission):
GET  /api/admin/user-soul-profiles            — per-tenant list (for monitoring only; no content)
GET  /api/admin/user-soul-profiles/stats       — counts + staleness distribution
```

---

## 6. Mission Control UI

New page `/aurabot/my-profile`. Two views:

### "My Profile" (primary, every user sees their own)
- Rendered profile card per field with the `text`, `confidence` bar, and "Source: N memories" link (expanding a timeline of source memory titles + dates).
- Per field: `Pin 📌` / `Hide 👁` / `Edit ✏️` / `Reset ↩` buttons.
- Top banner if `stale_flagged_at` is set: "Your profile may be outdated — review or re-derive now?"
- Footer: "Last derived: {datetime}; v{version}; next re-derivation: {datetime}" + "Re-derive now" button (rate-limited 1/day).

### "History"
- Collapsible list of superseded versions with diff-viewer comparing to the previous version.
- Each version shows source_memory_pids count + derivation_confidence + reason (auto / manual / edit).

### Admin dashboard (behind permission)
- `/aurabot/soul-profiles` — list of users with soul profiles in the tenant (metadata only — NO content shown). Shows coverage rate (users with profile / total active users) + staleness distribution.
- No ability for admin to read a user's profile content. This is deliberate — the profile is the user's property within the tenant.

---

## 7. Privacy & control

1. **User ownership**: the user can edit, pin, hide, or GDPR-forget their own profile. Admin cannot read content — only metadata.
2. **Transparent derivation**: every field shows source memories so the user can audit "why does AuraBot think this about me?".
3. **Opt-in per tenant**: derivation is off by default; tenant admin opts in. Individual users can forget after opt-in.
4. **No cross-user leakage**: User A's profile is never visible to user B (not even summarised) — reinforced at query + API layer.
5. **GDPR forget cascades**: `forgetProfile` soft-deletes all versions, marks user as derivation-opt-out (`ab_user_profile_optout` flag or stored on the latest row).
6. **Export**: user can download their full profile history (JSON) for data portability.

---

## 8. Metrics

### Counters
```
auraboot_user_soul_profile_derivation_total{tenant, outcome}
  outcome ∈ {drafted, skipped_no_change, skipped_too_little_signal, failed}
auraboot_user_soul_profile_activation_total{tenant}     -- DRAFT→ACTIVE
auraboot_user_soul_profile_user_edit_total{tenant, action}
  action ∈ {pin, hide, edit, reset, forget}
auraboot_user_soul_profile_stale_flagged_total{tenant}
```

### Gauges
```
auraboot_user_soul_profile_active_count{tenant}           -- users with an ACTIVE profile
auraboot_user_soul_profile_stale_count{tenant}            -- subset with stale_flagged_at set
auraboot_user_soul_profile_avg_confidence{tenant}         -- population mean
```

### Grafana panels
- Coverage rate over time (active / eligible users)
- Derivation outcome stacked area
- Staleness rate trend
- User-edit rate (signal of distrust)

### Alerts
- `UserSoulProfileHighStaleRate` — stale/active > 0.4 for 30d → derivation algorithm drift
- `UserSoulProfileDerivationFailureSpike` — failed > 5% of attempts for 7d → LLM/data issue
- `UserSoulProfileHighEditRate` — user_edit / active > 0.5 for 30d → derivation is wrong enough that users routinely override

---

## 9. Test matrix

### Integration (real PG)
- `UserSoulProfileSchemaIntegrationTest`: CHECK, UNIQUE (tenant, user WHERE ACTIVE), indexes.
- `UserSoulProfileDeriverIntegrationTest`:
  - Skip when < 3 memories
  - Produce DRAFT from 5 seeded memories; verify source_memory_pids populated
  - Idempotent hash: re-run with same inputs produces no new row
  - user-edit preservation: pin a field, re-derive, pinned field survives
  - Advisory lock 7306
- `UserSoulProfileActivatorIntegrationTest`:
  - DRAFT > 24h → ACTIVE, previous ACTIVE → SUPERSEDED
  - Only one ACTIVE per (tenant, user)
- `UserSoulProfileEditorIntegrationTest`: pin/hide/edit/reset/forget
- `UserSoulProfileStalenessDetectorIntegrationTest`: recent divergent memories → flag
- `UserSoulProfileReaderIntegrationTest`: hidden profiles return empty; stale gets trailing warning
- `UserSoulProfileControllerIntegrationTest`: all endpoints, cross-user 404, cross-tenant 404, rate limit on derive-now

### Unit
- `ProfileProjectorTest`: pure Java projection of memories → candidate fields
- `ProfileConfidenceScorerTest`: formulas
- `ProfileHashTest`: canonical hash stability + field-order independence

### E2E
- `my-profile.spec.ts` (mocked): render + pin + hide + edit + stale banner
- `my-profile-real.spec.ts` (real backend): seed profile → navigate → edit → re-fetch → verify changes persist

---

## 10. Task breakdown (9 PRs)

Expected span: ~2 weeks at parallel-worktree pace.

### Phase 1 — Schema + projector + deriver (PR-75)
- `ab_agent_user_soul_profile` table + constraints + indexes
- `ProfileProjector` utility (pure Java, unit-testable)
- `ProfileConfidenceScorer` utility
- `UserSoulProfileDeriver` service (nightly scheduler, advisory lock 7306)
- LLM rendering hook using `LlmProviderFactory`
- `ProfileHasher` for canonical dedup
- Integration + unit tests

### Phase 2 — Activator + staleness + editor (PR-76)
- `UserSoulProfileActivator` (30m scheduler, lock 7307)
- `UserSoulProfileStalenessDetector` (daily, lock 7308)
- `UserSoulProfileEditor` — pin/hide/edit/reset/forget
- Tests

### Phase 3 — Reader + grounding integration (PR-77)
- `UserSoulProfileReader` — `loadForGrounding(tenantId, userId)`
- Update `AgentRunService.loadAgentMemories` to prepend profile section when present
- Update `ActiveMemoryService.preRecall` similarly for chat path
- `applyShadowMarker`-like pattern for stale profiles (prefix annotation)
- Integration tests asserting LLM prompt includes the profile section

### Phase 4 — REST controller + metrics (PR-78)
- `UserSoulProfileController` — all endpoints
- `UserSoulProfileMetrics` — counters + gauges per §8
- Rate limiter on `/derive-now` (1/day/user)
- Controller tests

### Phase 5 — Mission Control UI + E2E mocked (PR-79)
- `/aurabot/my-profile` page
- Field card + pin/hide/edit/reset controls
- History tab
- Admin dashboard at `/aurabot/soul-profiles` (metadata only)
- `resources.ts` registration
- Mocked E2E spec

### Phase 6 — E2E real + Grafana + docs (PR-80)
- `my-profile-real.spec.ts` against live backend
- `grafana-user-soul-profile.json`
- Alert rules
- `docs/core-concepts/user-soul-profile.md` subsystem reference

### Phase 7 — Code review round 1 (PR-81+)
- Spawn code-reviewer; multi-agent fix-up

### Phase 8 — Staleness tuning + opt-in rollout (PR-82+)
- Enable derivation on pilot tenant
- Observe first week: confidence distribution, edit rate, stale rate
- Tune thresholds

### Phase 9 — Export + GDPR closure (PR-83)
- User-facing JSON export of profile history
- Admin dashboard forget-user path integration (cascades to `forgetProfile`)

---

## 11. Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|-----------|
| LLM hallucinates traits not in source memories | M | H | Every field cites source_memory_pids; UI shows them; user can Hide → permanent Hide survives re-derivation |
| Profile becomes stale, user doesn't notice | H | M | Staleness detector + visible banner + alerts |
| User never edits → trusts possibly wrong profile | M | M | "New this version" diff banner when profile changes, nudge user to review |
| LLM call cost per user per day too high | M | M | Derivation is nightly (not per-chat); skip when no meaningful memory change (hash dedup); opt-in per tenant |
| Cross-user leakage bug | L | H | Integration test explicitly probes user A cannot access user B's profile + API layer enforces |
| Admin reads user profile content | L | M | Admin dashboard shows metadata only (counts, timestamps); content redacted at controller level |
| Profile contradicts a new authoritative memory | M | L | Staleness detector flags divergence; grounding section adds the "prefer recent memories" warning when stale |
| User pins something wrong → profile permanently broken | L | L | `reset` unlocks all fields; history retained so they can see prior versions |
| Embedding dim mismatch like PR-74 | L | L | Reuse `MemoryEmbeddingService` which now has dim validation |
| Stale detector false-positives cause user alarm | M | M | Threshold tuned in Phase 8; banner is soft ("may be outdated"), not blocking |
| GDPR forget leaves orphans | L | H | Test forgetProfile fires cleanup transactionally; FK cascade from `ab_user` preserves coverage |

---

## 12. Acceptance criteria per phase

**Phase 1** (PR-75)
- [ ] Schema with unique(tenant,user WHERE ACTIVE) enforced
- [ ] ProfileProjector produces candidate structure from 5 seeded memories deterministically
- [ ] Deriver creates DRAFT row, skips when <3 memories, skips when hash unchanged
- [ ] 15+ tests pass

**Phase 2** (PR-76)
- [ ] DRAFT >24h flipped to ACTIVE, previous ACTIVE → SUPERSEDED (atomically)
- [ ] Staleness detector flags the right rows
- [ ] Editor pin/hide/edit/reset all persist; forget cascades

**Phase 3** (PR-77)
- [ ] `AgentRunService` prompt contains profile section when ACTIVE profile exists
- [ ] Stale profile adds warning line
- [ ] Hidden profile → empty section, no side-effect

**Phase 4** (PR-78)
- [ ] All endpoints return proper shape, 404 on cross-tenant / cross-user
- [ ] derive-now rate-limited
- [ ] Metrics visible in /actuator/prometheus

**Phase 5** (PR-79)
- [ ] My Profile page renders the active profile
- [ ] Pin/hide/edit/reset all work round-trip
- [ ] Admin dashboard shows metadata only (no content leakage)

**Phase 6** (PR-80)
- [ ] Real E2E: seed profile → edit via UI → DB reflects change
- [ ] Grafana validates; alerts parse

**Phase 7+** per Learning Loop/Memory Promotion precedent.

---

## 13. Open questions

1. **Agent vs user profile priority in grounding**: when an Agent has its own `soul_profile` (agent's voice) AND the user has one (preferences), both are prompted. If they contradict (e.g. Agent is "formal" but user prefers "casual"), who wins? v1 default: Agent's Soul Profile applies to AuraBot's TONE, User Soul Profile applies to CONTENT preferences + boundaries. No direct conflict in practice, but needs doc.

2. **Derivation for brand-new users**: < 3 memories means no profile. Chat works fine, just no profile section. First derivation once user accumulates ≥ 3 high-importance memories. Document this "cold start" state explicitly.

3. **Pin granularity**: current plan: pin by top-level field (persona / preferences.communication_style / etc). Should we support pinning individual list items in `habits.recurring_actions`? Deferred — start with field-level.

4. **Language preference from actions vs messages**: if the user reads Chinese content but writes English messages, which language wins? v1: message-language majority vote. Document and tune based on feedback.

5. **Per-Agent user profile specialisation**: should a user have ONE profile used by all agents, or different profiles when talking to different agents? v1: ONE (simpler, matches mental model). Specialisation deferred.

---

## Appendix A — Why this plan matches "excellence-first"

- Every field is **citable**: user can audit AI's assertions about them.
- **User-controllable**: pin, hide, edit, forget — full agency.
- **Shadow period** before DRAFT → ACTIVE (mirrors Memory Promotion pattern): 24h to catch bad derivations.
- **Staleness flag** makes drift visible rather than silent.
- **Reuses proven infrastructure**: advisory locks, canonical hashing, embedding + normalisation, metrics + alerts pattern. Nothing invented from scratch where a precedent exists.
- **Provenance chain**: profile field → source memories → source conversations. Three-deep traceability on every assertion.
- **Admin dashboard is metadata-only**: admin sees coverage + staleness, never content. Trust boundary explicit.

This is what "big ambition" looks like at small-team scale: a premium personalisation feature with the same rigour as the preceding Learning Loop + Memory Promotion subsystems, built on top of them rather than beside them.

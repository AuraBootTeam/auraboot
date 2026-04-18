# User Soul Profile

Per-user, dynamically-derived personalisation profile used to ground AuraBot
responses. Distinct from the agent-side `soul_profile` (which defines an
agent's voice) — this profile captures what the LLM should know about the
_user_: persona, communication style, domain vocabulary, working hours,
recurring habits, expertise, boundaries, preferred language.

Authoritative design doc: `docs/plans/2026-04/2026-04-19-user-soul-profile-design.md`.

## Data model

Table `ab_agent_user_soul_profile`. One `ACTIVE` row per `(tenant_id, user_id)`;
older versions persist with `status='SUPERSEDED'` for audit. The derived profile
is stored as JSONB and every field carries `text`, `source_memory_pids`, and a
`confidence` score so the user can trace every AI assertion back to concrete
evidence.

## Lifecycle (phase status)

| Phase | PR | Subsystem |
|-------|----|-----------|
| 1 | PR-75 | Schema + `UserSoulProfileDeriver` + projector + scorer + hasher |
| 2 | PR-76 | `UserSoulProfileActivator` (DRAFT→ACTIVE) + `StalenessDetector` + `Editor` |
| **3** | **PR-77** | **`UserSoulProfileReader` + grounding injection (this doc)** |
| 4 | PR-78 | REST controller + metrics |
| 5 | PR-79 | Mission Control UI (`/aurabot/my-profile`) |
| 6 | PR-80 | Real E2E + Grafana + alerts |

## Grounding integration (Phase 3)

`UserSoulProfileReader.loadForGrounding(tenantId, userId)` returns an optional
`ProfileSection` that gets prepended to the LLM system prompt in two paths:

- **`AgentRunService.loadMemorySection`** — scheduled/cron agent runs. The
  profile block renders immediately above `## Agent Memory`. When
  `MetaContext.getCurrentUserId()` is null (system/cron without a user) no
  profile is injected — memory-only prompts remain unchanged.

- **`AuraBotChatService.streamChat`** — interactive chat prompt assembly.
  The profile block is prepended to the full system prompt (template-rendered
  or fallback form) just before the trace-span closes.

### Guarantees

- Hidden profiles (`hidden_at IS NOT NULL`) return `Optional.empty()` — the
  LLM sees nothing at all, not a "[hidden]" placeholder.
- Only rows with `status='ACTIVE'` are returned; DRAFT/SUPERSEDED/ARCHIVED
  never ground.
- Stale profiles (`stale_flagged_at IS NOT NULL`) render with a trailing
  warning line instructing the LLM to prefer recent memories over the profile
  when they conflict.
- User-edited fields in `edited_fields` JSONB honour a strict
  **hide > override > raw** precedence (see `UserSoulProfileFieldPaths`).
- Rendered prompt block is hard-capped at `MAX_PROMPT_CHARS = 500` to protect
  the LLM context window. Verbose fields are truncated with an ellipsis.
- `loadForGrounding` never returns `null` and never throws for benign absence —
  only DB infrastructure failures propagate.

### Metrics

`UserSoulProfileReader` increments `auraboot_user_soul_profile_read_total{tenant}`
on each successful read. Derivation/activation/edit/stale counters live in
`UserSoulProfileMetrics` (Phase 2).

## User control

Users can pin, hide, edit, or GDPR-forget their own profile via
`/aurabot/my-profile` (Phase 5). Admins see metadata only — never content.

# User Soul Profile

**Status**: Phase 1 of 9 (PR-75). Deriver ships as DRAFT-producer; activation + grounding + UI come later.
**Plan**: [2026-04-19 design](../plans/2026-04/2026-04-19-user-soul-profile-design.md)

## Problem

Without a per-user profile, the agent has no durable handle on the *user's* preferences, expertise, or boundaries across sessions. Memory Promotion gives us a clean, citable corpus to project over — USP is the compression layer on top, never an invention.

See the design doc for the full narrative. This page only tracks what Phase 1 actually shipped.

## Components (Phase 1)

| Path | Role |
|------|------|
| `ab_agent_user_soul_profile` table | Versioned, per-user profile storage. One `ACTIVE` row max per `(tenant, user)` (UNIQUE partial index). |
| `ProfileProjector` | Pure-Java projection of memories + actions into candidate fields (persona / preferences / habits / expertise / boundaries / language). Deterministic. |
| `ProfileConfidenceScorer` | Per-field confidence formulas; `aggregateMin` for profile-level score. |
| `ProfileHasher` | SHA-256 over canonical JSON (via `CanonicalJsonHasher`), strips mutable `meta` + `last_derived_at` so unchanged content dedups. |
| `UserSoulProfileDeriver` | Nightly cron `0 0 4 * * *`, advisory lock `7306`. Gathers inputs → projects → hashes → writes DRAFT (or skips). |
| `UserSoulProfileMetrics` | `auraboot_user_soul_profile_derivation_total{tenant, outcome}` counter. |

## Not in Phase 1

- DRAFT → ACTIVE activator (Phase 2, lock 7307)
- `UserSoulProfileEditor` (pin / hide / edit / reset / forget) — Phase 2
- `UserSoulProfileStalenessDetector` — Phase 2 (lock 7308)
- Grounding integration in `AgentRunService` / `ActiveMemoryService` — Phase 3
- REST controller `/api/user/soul-profile/*` — Phase 4
- Mission Control UI `/aurabot/my-profile` — Phase 5
- Grafana + alerts — Phase 6

## Terminology

Do not confuse with `ab_agent_definition.soul_profile`. That's the **Agent** Soul Profile — the agent's persona, manually authored. The table and services documented here describe the **User** Soul Profile — derived, per-user, opt-in per tenant.

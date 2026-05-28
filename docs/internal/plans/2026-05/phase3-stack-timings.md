# Phase 3 — Isolated stack cold/warm timings

Measured 2026-05-09 on `fix/oss-suite-r2` worktree using
`bash scripts/dev/start-isolated.sh --slug=phase3-bench` against the
Tier-1-optimised image set (already cached locally — see
`2026-05-08-docker-warm-stack-and-suite-r2.md`).

Slug `phase3-bench` resolved to offset 78 → ports
`postgres 5511 / backend 6522 / vite 5252 / bff 3579 / redis 6557`.

## Cold start (`docker compose up -d`, fresh container creation)

| Stage | Elapsed |
|---|---|
| `docker compose ... up -d` returns (postgres+backend healthy, frontend started) | **~230 s** |
| Vite serves first 200/302 on `/` (after `up -d` returned) | +**~36 s** |
| Backend `/actuator/health` = 200 | already 200 when `up -d` returns (gated by `Healthy` step) |
| BFF responds (any HTTP code, e.g. 404 for non-existent path) | overlaps with vite (≤+5 s) |
| **Total time-to-ready** | **≈ 266 s (~4 min 26 s)** |

The `up -d` phase dominates because backend `Healthy` waits ~3 minutes
on Spring Boot startup + flyway migrations; pre-built images mean
no `docker build` cost on this run.

## Warm restart (`docker compose stop` then `start` — same containers)

| Stage | Elapsed |
|---|---|
| `docker compose -p ... start` returns | **~21 s** |
| Vite responds 302 on `/` | +**~7 s** |
| Backend `/actuator/health` = 200 | already 200 by the time vite is ready |
| **Total time-to-ready** | **≈ 28 s** |

Warm restart is **~9.5× faster** than cold because:

- Postgres re-uses the existing PGDATA volume (no migrations replayed).
- Backend skips fresh classloader init / first-time flyway baseline.
- Vite picks up the existing node_modules + cache.

## Recommendation (input for Op 10)

Cold (~4.5 min) is acceptable for nightly runs and per-PR docker stacks.
Warm (~30 s) makes interactive iteration cheap, so a long-running
"developer stack" + ad-hoc `docker compose stop/start` is the right
default workflow.

For PR-gate CI, factor in image build time on a fresh runner (no local
cache) — that is **not** measured here and is expected to dominate.

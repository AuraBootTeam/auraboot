---
type: handover
status: active
created: 2026-06-11
---

# HANDOVER — OSS test-coverage-to-80 (2026-06-11)

## State (all merged to main)
13 PRs: #527, #529, #531–#535, #540–#544, #555. Canonical `auraboot` checkout is on `main`, clean.

- **Backend**: gate raised 0.50→0.78 then →**0.68** after un-excluding `meta/service/impl`.
  Honest bundle = **70.2% line** (was a misleading 80.1% on a narrow denominator). Test-infra fix:
  `spring.test.context.cache.maxSize=8` (full IT no longer exhausts PostgreSQL `max_connections=400`).
- **Frontend**: **19.08% → 25.61% line**, 2099 → **3599 tests** (all green), ratchet floor 25.
  +1500 tests across services / hooks / server / stores / designer-runtime engines.
- Tracker: `docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md` (per-package targets + plan).
- Retro: `docs/retro/2026-06-11-oss-coverage-session-retro.md` (root-cause analysis + 固化 lessons).

## How to measure (reliable commands)
```bash
# Backend (bounded context cache prevents shared-DB exhaustion):
cd platform && ./gradlew :cleanTest :test :jacocoTestReport --continue
#   if test fails, the report is skipped — regenerate: ./gradlew :jacocoTestReport -x test
#   open build/reports/jacoco/test/html/index.html
# Frontend:
cd web-admin && pnpm install --frozen-lockfile && pnpm test:unit:coverage
```
Needs: shared Postgres :5432 (`aura_boot`, ~570 tables), Redis :6379. Docker only for testcontainers.

## Next steps (all heavy / infra-gated — do in dedicated sessions, NOT mass-parallel)
1. **#8 / #9 (backend, serial)** — real-stack IT to lift `meta/service/impl` 47%→80% + branch
   coverage 31%→up, then raise the gate floor 0.68→higher. **Use an isolated `dev.sh runtime`
   (namespaced DB)** — concurrent IT on shared :5432 re-exhausts connections (proven this session).
2. **#14 (frontend → 80%)** — owner口径 decided: vitest-logic (~30% ceiling, mostly done) + Playwright
   E2E-UI merged coverage. Needs the GA E2E stack up + merging vitest & E2E V8 reports. Harness exists
   (`pnpm coverage:e2e`). Dedicated infra session.
3. **#3 (infra subprojects)** — storage/mq baseline needs Docker (was off this session).

## Watch-outs for the next session
- The committed jacoco report can be a *partial run* — regenerate before citing any number.
- NEVER cap HikariCP globally via `SPRING_DATASOURCE_HIKARI_*` env (breaks `HikariConfig` validation
  → context-cache poisoning). Bound the context cache instead (already done in `build.gradle`).
- If the working checkout is on a shared feature branch (e.g. `codex/crm-endgame-gaps`), it may lag
  main — verify base before editing `build.gradle`; isolate all changes to worktrees from `origin/main`.
- 8 parallel sub-agents worked cleanly this session (each own worktree, test-only commits, main-loop
  re-verified). The pattern scales for additive frontend tests; do NOT use it for shared-DB backend IT.

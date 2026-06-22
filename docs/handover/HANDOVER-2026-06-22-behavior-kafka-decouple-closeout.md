---
type: handover
status: shipped
created: 2026-06-22
slug: behavior-kafka-decouple-closeout
distilled_to:
  - docs/operations/behavior-kafka-ingest-runbook.md
relates_to:
  - docs/superpowers/specs/2026-06-21-behavior-kafka-decouple-ingestion-design.md
  - docs/backlog/2026-06-22-behavior-kafka-post-merge-followups.md
---

# Session Handover - 2026-06-22 - Behavior Kafka Decouple Closeout

## Summary

Behavior ingest Kafka decouple is merged to `main` via OSS PR #1023. The feature branch `feat/behavior-kafka-decouple-ingestion` was deleted on the remote. Post-merge verification was run from a detached `origin/main` worktree at merge commit `4e2bef61e875edc0366e002cc5f92c2f93c8fde4` using local host-first validation only; no local Docker was used.

## Completed

- `BehaviorCollectService.record` and `recordAnonymous` now enqueue through `BehaviorIngestPublisher` instead of synchronously writing `ab_behavior_event`.
- `BehaviorIngestConsumer` persists accepted events asynchronously; `BehaviorEventPersister` keeps `(tenant_id,event_id)` idempotency and routes malformed/constraint failures to quarantine.
- `BehaviorQuarantineConsumer` persists `aura.behavior.quarantine.v1` into `ab_behavior_quarantine`.
- `ab_behavior_event` and `ab_behavior_quarantine` are in the tenant ignore-list because MQ consumer paths carry explicit `tenant_id`.
- Native Kafka IT covers async round-trip, idempotency, malformed quarantine, and constraint quarantine when `localhost:9092` is available.
- Browser goldens cover public keyed collect, site-key registry, dashboard UV, and quarantine behavior.
- CI/schema scripts were adjusted for local-first schema validation and CI Flyway drift parity.
- Closeout docs added:
  - `docs/operations/behavior-kafka-ingest-runbook.md`
  - `docs/backlog/2026-06-22-behavior-kafka-post-merge-followups.md`
  - this handover

## Merge State

| Item | State |
| --- | --- |
| PR | #1023 `https://github.com/AuraBootTeam/auraboot/pull/1023` |
| PR state | merged/closed |
| Merged at | `2026-06-22T14:48:00Z` |
| Feature head | `978192ff6276de5d09ddea9b701b9d18b225eaa9` |
| Merge commit | `4e2bef61e875edc0366e002cc5f92c2f93c8fde4` |
| `origin/main` | `4e2bef61e875edc0366e002cc5f92c2f93c8fde4` |
| Remote feature branch | deleted (`git ls-remote --heads origin feat/behavior-kafka-decouple-ingestion` returned empty) |

## Post-Merge Verification

Worktree: `/Users/ghj/work/auraboot-behavior-kafka-main-verify`

Runtime: `behavior-kafka-main-verify-69`, slot 69, backend 6469, Vite 5169, BFF 6169, DB `auraboot_69`. The runtime was destroyed after the browser golden run.

Commands and results:

```bash
./scripts/check-schema-sql.sh --local
```

Result: passed; temp DB applied `schema.sql`, 311 tables created.

```bash
cd platform
./gradlew --no-daemon :compileJava :compileTestJava --console=plain
```

Result: passed.

```bash
cd platform
./gradlew --no-daemon :test \
  --tests 'com.auraboot.framework.behavior.ingest.*' \
  --tests 'com.auraboot.framework.behavior.service.BehaviorCollectServiceTest' \
  --tests 'com.auraboot.framework.behavior.controller.BehaviorCollectControllerIT' \
  --tests 'com.auraboot.framework.behavior.keyed.KeyedCollectIT' \
  --tests 'com.auraboot.framework.application.database.mybatis.MybatisPlusConfigTest' \
  --console=plain
```

Result: passed; Gradle `BUILD SUCCESSFUL` in 1m23s.

```bash
./scripts/oss-golden-stack.sh up behavior-kafka-main-verify-69 \
  --slot 69 \
  --ttl 2h \
  --plugin core-site-key \
  --plugin core-dashboard
```

Result: passed; backend/bootstrap/plugin import/frontend/auth warm all ready.

```bash
cd web-admin
eval "$(../scripts/oss-golden-stack.sh env behavior-kafka-main-verify-69)"
npx playwright test -c playwright.config.ts --project=chromium --no-deps \
  tests/e2e/behavior/anonymous-keyed-collect.golden.spec.ts \
  tests/e2e/behavior/site-key-registry.golden.spec.ts \
  tests/e2e/behavior/behavior-ingest-quarantine.golden.spec.ts \
  --reporter=line
```

Result: 9 passed in 48.2s.

```bash
cd web-admin
pnpm exec vitest run \
  packages/track/src/__tests__/public.test.ts \
  packages/track/src/__tests__/tracker.test.ts \
  packages/track/src/__tests__/envelope.test.ts \
  --reporter=verbose
```

Result: 3 files passed, 19 tests passed.

```bash
cd web-admin
pnpm --filter @auraboot/track build
node packages/track/scripts/verify-global-build.mjs
pnpm test:env-lint
```

Result: build passed; global bundle exposes `AuraTrack.init`; env-lint passed with all 21 hits in baseline.

## Pitfalls And Fixes

1. `gh pr merge --delete-branch` reported local failure because `main` was checked out in `/Users/ghj/work/auraboot-crm-lead-ux`, but the PR had already merged server-side. Resolution: verify with REST PR state plus `git fetch origin main`; do not infer merge failure from the local checkout cleanup error.
2. CI Flyway/schema drift required several parity fixes after local green: Flyway CLI path/version, executable bit, Postgres image vs `pg_dump`, and EOF newline normalization. Resolution: keep `check-schema-sql.sh --local` and CI drift normalization aligned.
3. Frontend env-lint caught direct `process.env.PGPASSWORD`. Resolution: use the test env baseline/`PG_ENV` path; keep `pnpm test:env-lint` in the closeout loop.
4. `KeyedCollectIT` needed contract adjustment after enqueue semantics: mixed valid + over-long event returns `accepted:2`; durable store gets the valid row, quarantine gets the bad row. The endpoint remains resilient and does not 500.

## Remaining Work

No remaining blocker for the Kafka decouple slice. Follow-ups are tracked separately in `docs/backlog/2026-06-22-behavior-kafka-post-merge-followups.md`, notably quarantine query/replay, metrics/alerts, production topic runbook, Avro/schema-registry hardening, load/backpressure tests, server outcome outbox, and consumer trace propagation.

## Operational Notes

- Local validation for this line remains host-first and no Docker. Use `docs/operations/behavior-kafka-ingest-runbook.md` for the repeatable command set.
- `aura.mq.type=memory` is still the local/golden default; `aura.mq.type=kafka` requires the `platform-mq-kafka` module and a native broker reachable at `localhost:9092` or configured `aura.mq.kafka.bootstrap-servers`.
- Quarantine replay is not implemented; use direct SQL only for inspection until BK-F1/BK-F2 are scheduled.

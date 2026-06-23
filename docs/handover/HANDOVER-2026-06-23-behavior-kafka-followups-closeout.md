---
type: handover
status: shipped
created: 2026-06-23
slug: behavior-kafka-followups-closeout
distilled_to:
  - auraboot-enterprise/docs/agent-rules/flyway-schema-change-and-local-bringup.md (schema snapshot pg_dump version-noise, ENT #652)
  - auraboot-enterprise/docs/agent-rules/engineering-gotchas/backend-spring-db.md (schema snapshot drift gotcha, ENT #652)
---

# Session Handover - 2026-06-23 - Behavior Kafka decouple follow-ups closeout

## Session Summary
Closed the Behavior Kafka decouple ingestion follow-up line end to end. OSS PR #1029 was merged into `main` with all required checks green, and the reusable schema snapshot drift lesson was codified in enterprise PR #652. This handover captures the completed work, verification evidence, runtime state, and the avoidable bends from the session.

## Tasks Completed
- [x] Refactored behavior collection so `record` / `recordAnonymous` enqueue through the ingestion path instead of persisting directly.
- [x] Added `ab_behavior_event` and `ab_behavior_quarantine` to the tenant ignore-list.
- [x] Added and verified the real-stack memory path for decouple round-trip, idempotency, quarantine, and guard-sync behavior.
- [x] Added native Kafka async integration coverage, Avro compatibility checks, golden admin coverage, and close-out docs.
- [x] Added quarantine sink persistence: Flyway migration, entity, mapper, service, replay, retention, and controller coverage.
- [x] Fixed the final CodeQL alert by clamping untrusted page parameters before arithmetic.
- [x] Fixed schema drift caused by local PG17 / `pg_dump` output differences and regenerated the OSS schema snapshot.
- [x] Codified the schema snapshot lesson in enterprise canonical docs through ENT PR #652.

## PR / Merge State
- **OSS #1029** `https://github.com/AuraBootTeam/auraboot/pull/1029`
  - Status: MERGED to `main`
  - Feature head: `c2561159dd5d66bc41576b8eb6d7eaf941982aad`
  - Merge commit: `e7393f82494c776dc8060dddb57a75c31b19f6cf`
  - Required checks were green before merge: Backend CI, Frontend CI, Docker Quickstart, schema-flyway, CodeQL, Docs, Gitleaks, OSS Boundary, Permission gate, CLA.
- **ENT #652** `https://github.com/AuraBootTeam/auraboot-enterprise/pull/652`
  - Status: MERGED to `main`
  - Commit: `b0509485e0471add9628d98202339be5f4cbf0a0`
  - Merge commit: `8cb87fb2cac0e23593f1051d551ee24a4b5a42c7`
- **OSS handover PR #1031** `https://github.com/AuraBootTeam/auraboot/pull/1031`
  - Status: OPEN at document update time; expected to be admin-merged in this same closeout session.

## Verification Evidence
- `git diff --check origin/main...HEAD`
- `./gradlew --no-daemon :compileJava :compileTestJava :test --tests ... --console=plain` passed for behavior ingest, publisher, persister, consumer, quarantine, controller, retention, replay, Avro compatibility, topic policy, keyed collect, and outbox suites.
- `./gradlew --no-daemon :test --tests com.auraboot.framework.behavior.controller.BehaviorQuarantineControllerTest --console=plain` passed after the CodeQL pagination fix.
- `pnpm exec vitest run app/framework/meta/utils/__tests__/canonicalizePageDsl.test.ts` passed, 19 tests.
- `bash scripts/check-docs.sh --strict` passed in OSS.
- `node scripts/check-docs-governance.mjs` passed in OSS.
- `./scripts/check-schema-sql.sh --local` passed, 312 tables created.
- `scripts/db/check-schema-drift.sh --edition oss` passed after filtering version-specific `pg_dump` noise.
- `node scripts/behavior-keyed-load-test.mjs --batches 10 --batch-size 50 --concurrency 4 --p95-budget-ms 5000` produced `expectedEvents=500 accepted=500 persisted=500 droppedGoodEvents=0 failedRequests=0 p95Ms=89 p95BudgetMs=5000`.
- E2E truth static scan passed: `skip_or_fixme=0 page_request=0 direct_p_goto=0 waitForTimeout=0 long_timeout_literals=0`.
- `PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts --project=chromium tests/e2e/behavior/behavior-quarantine-admin.golden.spec.ts --reporter=line` passed, 20 tests plus 1 setup skip.

## Files Changed
### Backend
- Behavior ingest publisher / consumer / persister / quarantine services and their tests.
- Behavior collection service and controller integration paths.
- Quarantine migration, entity, mapper, replay, retention, admin controller, and tests.
- Kafka topic policy, Avro compatibility, idempotency, and guard-sync coverage.

### Frontend / Golden
- Core-dashboard behavior quarantine admin DSL page and menu wiring.
- Behavior quarantine golden E2E and supporting fixtures.
- Canonical DSL rendering regression coverage.

### Scripts / Docs
- Behavior keyed load harness and operational docs.
- Runbook, alerts, backlog closeout, coverage report, and retro updates.
- Schema snapshot generator normalization for `pg_dump` version-noise.
- Enterprise canonical docs for the snapshot drift failure mode.

## Pitfalls & Workarounds
1. **Schema drift was not caught before the first PR push.** We ran `check-schema-sql --local`, but missed `scripts/db/check-schema-drift.sh --edition oss`; CI `schema-flyway` caught the drift. Prevention: every DDL migration PR must run schema create plus drift check locally before push.
2. **Local PG17 `pg_dump` emitted version-specific noise.** The local snapshot included `SET transaction_timeout = 0;`, while CI PG16 did not. The correct fix was to filter generator output and regenerate, not hand-edit the snapshot.
3. **CodeQL pagination alert was real enough to fix.** `Math.max(pageNum - 1, 0)` performed arithmetic on user-controlled input before clamping. The fix clamps with `PaginationSafetyUtils.pageNumber(pageNum)` before subtracting and covers `Integer.MIN_VALUE`.
4. **`gh pr merge --delete-branch` can merge server-side and still fail locally.** The command failed because another worktree had `main` checked out. The server-side merge was already complete, so the safe response was to verify PR state, delete the remote branch manually, and clean the local branch without retrying the merge blindly.
5. **GitHub GraphQL / REST calls occasionally returned EOF.** Retrying status queries and using REST merge endpoints was more reliable than assuming command failure meant merge failure.
6. **Long `continue` chains need live recalibration.** Re-read `git status`, PR state, checks, and runtime state after every transition; stale summaries are useful context but not source of truth.

## Reflection & Codify
### Bends / Rework
1. **Missed drift gate before CI** - root cause `[A gate coverage] + [D verification]`; the local verification set did not include the same schema drift check as CI. Fixed by adding the check to the session's final gate list and by documenting it in canonical rules.
2. **PG version-noise looked like a schema change at first** - root cause `[B diagnosis]`; the diff had to be classified as dump tooling noise, not DDL intent. Fixed in `scripts/db/generate-schema-snapshot.sh` and codified in enterprise docs.
3. **CodeQL surfaced after functional tests were green** - root cause `[D verification]`; security/static analysis can catch arithmetic hazards not exercised by behavior tests. Fixed with a boundary-value test.
4. **Merge command failure was ambiguous** - root cause `[B diagnosis]`; CLI local cleanup failed after server merge. Existing enterprise git workflow guidance already covers this trap, so no new canonical change was needed there.

### Solidified Lessons
- [x] `auraboot-enterprise/docs/agent-rules/flyway-schema-change-and-local-bringup.md`: DDL PRs must run schema drift check, and generator output should normalize `pg_dump` version-noise.
- [x] `auraboot-enterprise/docs/agent-rules/engineering-gotchas/backend-spring-db.md`: added the PG17 `transaction_timeout` snapshot drift symptom and prevention.
- [x] Existing enterprise git workflow docs already cover the `gh pr merge --delete-branch` multi-worktree trap; this session confirmed the rule.
- [x] This handover records exact verification commands and runtime state for the next continuation.

## Operational State
### Worktrees / Branches
- OSS behavior feature PR #1029 is merged; remote feature branch was deleted.
- Current OSS handover branch: `codex/behavior-kafka-session-handover`, based on latest `origin/main` as of 2026-06-23 09:00 CST.
- Enterprise codify branch `codex/behavior-kafka-session-codify` was merged through ENT #652; temporary worktree can be removed after final sync.
- Main OSS worktree `/Users/ghj/work/auraboot-crm-lead-ux` owns branch `main`, which is why feature-worktree `gh pr merge --delete-branch` could not finish local cleanup.

### Runtime / Ports
- Runtime name: `behavior-quarantine-followups-77`
- Slot: `77`
- Backend: `http://127.0.0.1:6477`
- Vite: `http://127.0.0.1:5177`
- BFF: `6177`
- Postgres DB: `auraboot_77`
- Redis DB: `13`
- Kafka bootstrap: `127.0.0.1:9092`
- Kafka topic prefix: `auraboot.77.`
- Status at closeout: backend health `UP`, Vite returns 302, BFF port configured. Kafka / Postgres / Redis / backend / frontend listeners were present. Runtime was left running for manual inspection; stop with `./scripts/oss-golden-stack.sh stop behavior-quarantine-followups-77` when no longer needed.

## Next Steps
1. Merge this handover PR and pull local `main` worktrees forward.
2. Keep the slot-77 runtime only if more manual inspection is needed; otherwise stop it explicitly.
3. Start any new Behavior telemetry line from current `main`, not from the closed follow-up branch.

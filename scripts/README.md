# `scripts/` — index & taxonomy (auraboot (OSS))

> Snapshot **2026-07-23**. Mechanically derived (git history + repo-wide name-reference scan); may lag reality between refreshes. **refs** = how many other tracked files (docs, `package.json`, other scripts) mention this file by name.

## Counts

| category | count | what it is |
|---|--:|---|
| **gate** | 42 | Correctness/quality checks (`check-*` / `validate-*` / `*-audit`). Run before push / in local gate runners. |
| **generator** | 4 | Regenerate a tracked artifact (manifests, snapshots). Output is committed; rerun when inputs change. |
| **entrypoint** | 24 | Self-contained runners invoked by hand / crontab (owner has no CI). `refs=0` is normal here — nothing imports them. |
| **pipeline/lib** | 9 | Shared library modules for the aura-pipeline / other scripts. Not run directly. |
| **tooling** | 65 | Reusable dev/ops helpers referenced by other scripts, package.json, or docs. |
| **test** | 19 | Co-located `*.test.mjs` unit tests for the scripts above. |

## Conventions

- **Gates** are named `check-*` / `validate-*` and must be falsifiable (green→inject defect→red). Wire them into `package.json` `validate:*` or a self-contained runner, not a dead `.yml`.
- **One-off migrations** (`migrate-*`, `*-split`, casing/backfill scripts) are **removed once the migration lands** — git history keeps them (`git log --all --full-history -- scripts/<name>`).
- Before adding a script, check the tables below for an existing one to extend.

## gate (42)

| script | refs | updated | purpose |
|---|--:|---|---|
| `check-agent-eval-boundary.mjs` | 2 | 2026-06-21 | OSS agent/RAG boundary gate. |
| `check-cache-eviction.mjs` | 3 | 2026-07-14 | check-cache-eviction — every @Cacheable cache must have *someone* who evicts it. |
| `check-capability-codes.mjs` | 0 | 2026-06-21 | Permission v2 capability-code drift gate. |
| `check-command-permissions.mjs` | 0 | 2026-07-23 | A command whose handler stage does real work must declare the permission that authorizes it. |
| `check-command-reachability.mjs` | 4 | 2026-07-23 | Gate: a declared command must have a way for a user to reach it. |
| `check-command-reachability.sh` | 0 | 2026-07-23 | Pre-push gate: every declared command needs a UI entry point. |
| `check-controller-authz.mjs` | 0 | 2026-07-15 | check-controller-authz.mjs — regression guard for the deep-review fail-open finding |
| `check-coverage-manifest-freshness.mjs` | 2 | 2026-07-23 | Gate: the committed coverage manifest must still describe reality. |
| `check-cs-widget-bundle.mjs` | 0 | 2026-07-14 | The widget bundle served to customers is a build artifact that lives on the Java classpath, so |
| `check-derived-field-writers.mjs` | 1 | 2026-07-23 | Gate: a declared derived field must not have a write path that bypasses its |
| `check-designer-boundary.mjs` | 3 | 2026-06-20 | Designer boundary checker (B3a — manifest-only minimal gate). |
| `check-designer-boundary.sh` | 0 | 2026-06-20 | Pre-push designer boundary gate (wraps check-designer-boundary.mjs). |
| `check-docs-governance.mjs` | 4 | 2026-06-21 | Documentation governance checker. |
| `check-docs-governance.sh` | 1 | 2026-06-11 | Pre-push docs governance gate (wraps check-docs-governance.mjs --git). |
| `check-docs.sh` | 2 | 2026-07-02 | Documentation quality gate. |
| `check-dsl-actions.mjs` | 3 | 2026-07-15 | check-dsl-actions.mjs — a workbench button may only ask for an action the runtime runs. |
| `check-dsl-command-convention.mjs` | 0 | 2026-06-19 | check-dsl-command-convention.mjs — regression gate for convention CRUD routing. |
| `check-dsl-render-types.mjs` | 1 | 2026-07-17 | check-dsl-render-types.mjs — a DSL page may only ask for a renderer that exists. |
| `check-dsl-status-colors.mjs` | 1 | 2026-07-13 | check-dsl-status-colors.mjs — DSL dict status color semantics gate. |
| `check-e2e-assertions.mjs` | 1 | 2026-07-14 | check-e2e-assertions — an assertion that something is ABSENT must be able to fail. |
| `check-e2e-spec-registration.mjs` | 3 | 2026-07-23 | Gate: no E2E spec may exist without being selectable by some project. |
| `check-e2e-spec-registration.sh` | 0 | 2026-07-23 | Pre-push gate: no E2E spec may exist without being selectable by some project. |
| `check-form-back-link.mjs` | 1 | 2026-07-23 | Targets that point outside the scanned page set — each needs a reason. |
| `check-hand-written-page-matrix.mjs` | 0 | 2026-07-24 | Gate: no new hand-written page-coverage matrices — the denominator is generated. |
| `check-i18n-hardcoded.mjs` | 0 | 2026-06-20 | check-i18n-hardcoded.mjs — i18n hardcoded-Chinese gate (ratchet). |
| `check-jsonb-typehandler.sh` | 3 | 2026-06-11 | check-jsonb-typehandler.sh — guard against the recurring "varchar→jsonb on insert/update" bug. |
| `check-no-secret-echo.mjs` | 1 | 2026-07-14 | check-no-secret-echo — refuse shell scripts that print a secret to stdout/stderr. |
| `check-no-secret-echo.sh` | 0 | 2026-07-14 | Wrapper so this gate shows up in `ls scripts/check-*.sh` — the repo's local-gate inventory. |
| `check-oss-boundary.sh` | 3 | 2026-06-18 | OSS / Enterprise boundary check. |
| `check-oss-no-internal-docs.sh` | 1 | 2026-07-02 | Fail if internal-process docs are tracked in the public OSS repo. |
| `check-public-record-id-contracts.sh` | 1 | 2026-06-24 |  |
| `check-public-record-openapi-contract.mjs` | 1 | 2026-06-24 | Validate public-record pid-only naming in a live or captured OpenAPI document. |
| `check-reset-init-contracts.sh` | 1 | 2026-05-28 |  |
| `check-scripts-index.mjs` | 2 | — | Falsifiable freshness gate for scripts/README.md (the scripts index). |
| `check-test-system.sh` | 0 | 2026-07-23 | Umbrella gate for the test system's own integrity. exit code = result. |
| `check-version-sync.sh` | 1 | 2026-06-18 | Gate: VERSION (release version, single source of truth) must equal |
| `db/check-db-matches-snapshot.sh` | 1 | 2026-07-23 | Answer one question about an EXISTING database: does it still match |
| `db/check-schema-drift.sh` | 1 | 2026-06-22 | Regenerate the schema snapshot from Flyway and diff it against the committed |
| `validate-permission-codes.mjs` | 2 | 2026-05-08 | Cross-source permission-code validator. |
| `validate-plugin-dashboards.mjs` | 1 | 2026-05-11 | Validates every plugins/<plugin>/config/dashboards/*.json against the Plan #8 |
| `validate-plugin-i18n.mjs` | 1 | 2026-04-14 | Validates every plugins/<plugin>/config/i18n.json against the plugin i18n contract. |
| `validate-public-record-id-contracts.mjs` | 2 | 2026-06-24 | Public dynamic-record id contract inventory and regression gate. |
| `validate-workflows.sh` | 0 | 2026-03-26 | Validate GitHub Actions workflow YAML files. |

## generator (4)

| script | refs | updated | purpose |
|---|--:|---|---|
| `db/generate-schema-snapshot.sh` | 3 | 2026-06-23 | Generate a deterministic schema-only snapshot from a freshly migrated DB. |
| `deploy/oss-remote/gen-admin-storage.mjs` | 1 | 2026-07-15 | Generate a Playwright admin storageState for a deployed AuraBoot instance, |
| `gen-coverage-manifest.mjs` | 2 | 2026-07-23 | Generate a coverage manifest from the DSL and the test tree. |
| `generate-plugin-routes.mjs` | 5 | 2026-04-26 | scripts/generate-plugin-routes.mjs |

## entrypoint (24)

| script | refs | updated | purpose |
|---|--:|---|---|
| `aurabot-scenario-golden-run.sh` | 0 | 2026-07-23 | aurabot-scenario-golden-run.sh — self-contained scenario golden for the |
| `backlog-stats.sh` | 0 | 2026-03-26 | Backlog dashboard stats — counts GAP statuses across all backlog files |
| `deploy/prod-deploy.sh` | 0 | 2026-06-18 | Production deploy orchestrator. Runs the schema migration + release ledger |
| `dev/ci-env-export.sh` | 0 | 2026-05-09 | ci-env-export.sh — env contract for generic CI runners. Defaults are |
| `dev/enterprise-env-export.sh` | 0 | 2026-05-09 | enterprise-env-export.sh — env contract for the enterprise overlay |
| `dev/ga-e2e-env-export.sh` | 0 | 2026-05-09 | ga-e2e-env-export.sh — env contract for the GA (GitHub Actions) E2E |
| `dev/rotate-license-keypair.sh` | 0 | 2026-05-09 | Rotate the AuraBoot commercial-license signing keypair. |
| `dev/run-agent-runtime-backend-gate.sh` | 0 | 2026-07-23 | Focused backend gate for the generic agent runtime architecture. |
| `dev/run-p0-e2e-docker.sh` | 0 | 2026-05-11 | run-p0-e2e-docker.sh — fully self-contained docker-only validation of the |
| `dev/xxl-job-true-stack-smoke.sh` | 0 | 2026-06-07 |  |
| `digital-employee-golden-run.sh` | 0 | 2026-07-23 | digital-employee-golden-run.sh — self-contained browser golden for the digital |
| `docker-bootstrap.sh` | 0 | 2026-06-08 | docker-bootstrap.sh — Import plugins into an already-running Docker E2E backend. |
| `e2e-compliance-check.sh` | 0 | 2026-03-26 | E2E Compliance Gate — scans test files for violations of testing constitution |
| `e2e-report.sh` | 0 | 2026-03-26 | e2e-report.sh — View E2E test run results by testRunId |
| `e2e-run.sh` | 0 | 2026-03-31 | Unified E2E Test Runner — GAP-169 |
| `host-e2e-up.sh` | 0 | 2026-06-08 | Host-mode E2E stack bring-up — host parity with docker-ga-e2e-up.sh. |
| `kb-ingestion-golden-run.sh` | 0 | 2026-07-13 | kb-ingestion-golden-run.sh — one command, whole knowledge-ingestion golden, exit code = verdict. |
| `local-pr-gate.sh` | 0 | 2026-07-18 | Local replacement for the required GitHub status checks. |
| `oss-init-env-only.sh` | 0 | 2026-05-11 | AuraBoot Quick Environment Initialization |
| `p1-verify-in-docker.sh` | 0 | 2026-05-08 | P1' ACP platformization — docker isolated stack verification. |
| `quick-filter-chip-golden-run.sh` | 0 | 2026-07-17 | quick-filter-chip-golden-run.sh — self-contained quick-filter view-chip browser golden runner. |
| `rbac-golden-run.sh` | 0 | 2026-07-04 | rbac-golden-run.sh — self-contained RBAC platform-baseline browser golden runner. |
| `suspended-tenant-login-ui-golden.sh` | 0 | 2026-07-17 | suspended-tenant-login-ui-golden.sh — E5, at the glass: what a user sees when their org is |
| `test-acp-runtime.sh` | 0 | 2026-05-09 |  |

## pipeline/lib (9)

| script | refs | updated | purpose |
|---|--:|---|---|
| `dev/lib/env-loader.sh` | 7 | 2026-05-22 | env-loader.sh — shell counterpart to web-admin/tests/helpers/environments.ts. |
| `dev/lib/env-registry.mjs` | 4 | 2026-05-22 |  |
| `dev/lib/health.sh` | 3 | 2026-05-22 | Sourceable health helpers for per-worktree dev stacks. |
| `dev/lib/process-manager.sh` | 3 | 2026-05-22 | Sourceable process helpers for per-worktree host dev services. |
| `lib/multi-worktree-guard.sh` | 5 | 2026-05-22 | Multi-worktree pre-flight guard |
| `lib/plugin-config.mjs` | 3 | 2026-07-23 | Read a plugin's config regardless of how it is laid out on disk. |
| `lib/repo-root.mjs` | 5 | 2026-07-23 | Resolve the repo the test-system gates should run against. |
| `lib/reset-init-common.sh` | 7 | 2026-05-17 | Shared reset/init primitives. This file is sourced by lifecycle scripts; keep |
| `lib/test-multi-worktree-guard.sh` | 1 | 2026-05-22 | Sanity tests for scripts/lib/multi-worktree-guard.sh |

## tooling (65)

| script | refs | updated | purpose |
|---|--:|---|---|
| `agent-git-guard.mjs` | 4 | 2026-06-24 |  |
| `agent-write-guard.mjs` | 2 | 2026-06-24 |  |
| `aps-fixtures/compare-strategies.sh` | 1 | 2026-05-28 | Compare APS V2 scheduling strategies on the fixture data set. |
| `behavior-keyed-load-test.mjs` | 0 | 2026-06-23 |  |
| `db/deploy-migrate.sh` | 1 | 2026-06-18 | Deploy-time schema step: migrate the target DB, validate, and record the |
| `db/flyway-common.sh` | 5 | 2026-06-18 | Shared Flyway environment + invocation for AuraBoot PostgreSQL schema governance. |
| `db/flyway-migrate.sh` | 4 | 2026-06-18 | Run Flyway migrate for AuraBoot. |
| `db/flyway-validate.sh` | 2 | 2026-06-18 | Run Flyway validate for AuraBoot (checks applied migrations against the |
| `db/write-platform-release.sh` | 1 | 2026-06-18 | Append a row to ab_platform_release after a successful Flyway migrate. |
| `deploy/oss-remote/deploy.sh` | 5 | 2026-07-23 | AuraBoot OSS — remote deploy via pre-built images ("image mechanism"). |
| `dev/cleanup-artifacts.sh` | 3 | 2026-05-12 | Targeted cleanup for slug/date-scoped Playwright artifacts. |
| `dev/cleanup-stack.sh` | 3 | 2026-05-12 | Targeted cleanup for one AuraBoot compose stack. |
| `dev/core-lite-it.sh` | 4 | 2026-06-01 | W2: shared core-lite integration-test harness. |
| `dev/doctor-disk.sh` | 6 | 2026-05-12 | Read-only disk pressure report for AuraBoot Docker/worktree artifacts. |
| `dev/env.sh` | 5 | 2026-05-22 | Unified per-worktree dev environment entrypoint. |
| `dev/host-env-export.sh` | 2 | 2026-05-09 | host-env-export.sh — symmetric counterpart to r2-env-export.sh. |
| `dev/import-isolated-plugins.sh` | 1 | 2026-05-17 | Compatibility wrapper for the old isolated-stack plugin import entrypoint. |
| `dev/list-isolated.sh` | 3 | 2026-05-12 | List all isolated dev stacks currently running. |
| `dev/maven-local-export.sh` | 1 | 2026-05-12 | Sourceable helper for per-worktree Maven local publishing. |
| `dev/plugin-runtime-import-guard.mjs` | 4 | 2026-07-17 | Guard a hybrid/config plugin import against the classic source/config/runtime/schema drift: |
| `dev/prepare-bugfix-demo.sh` | 2 | 2026-05-22 | Prepare a running daily bugfix environment for OSS demo debugging. |
| `dev/purge-private-pem-from-history.sh` | 1 | 2026-05-09 | Purge platform/src/main/resources/license/private.pem from the entire git |
| `dev/r2-env-export.sh` | 8 | 2026-05-22 | r2-env-export.sh — single-line `source` to set up the env for an |
| `dev/run-agent-runtime-full-gate-docker.sh` | 2 | 2026-05-21 | Fresh isolated Docker gate for the canonical agent runtime chain. |
| `dev/run-playwright-runner.sh` | 2 | 2026-05-12 | Run the optional Linux Playwright runner for an isolated stack. |
| `dev/start-dev-infra.sh` | 4 | 2026-05-22 | Start per-worktree infrastructure only for daily host-mode development. |
| `dev/start-isolated.sh` | 16 | 2026-05-26 | Start a per-worktree isolated docker dev stack. |
| `dev/start-production-like.sh` | 2 | 2026-05-12 | Start the B2 production-like frontend surface for an existing isolated stack. |
| `dev/stop-isolated.sh` | 10 | 2026-05-12 | Stop a per-worktree Docker dev stack. |
| `dev/test-dev-env-scripts.sh` | 1 | 2026-05-22 | Smoke tests for scripts/dev environment helpers. |
| `dev/test-gradle-guard.sh` | 1 | 2026-05-12 | Smoke test for platform/build.gradle multi-worktree Maven publish guard. |
| `dev/verify-quickstart.sh` | 1 | 2026-05-17 | verify-quickstart.sh — pre-launch sanity check for the README quickstart. |
| `docker-cleanup-batch-up.sh` | 1 | 2026-05-09 | Bring up the cleanup-batch isolated stack on shifted ports (postgres 6533 / |
| `docker-ga-e2e-bootstrap.sh` | 6 | 2026-05-20 | Bootstrap the GA-E2E docker stack for OSS Playwright runs. |
| `docker-ga-e2e-down.sh` | 2 | 2026-05-26 | GA Follow-up E2E stack teardown — thin wrapper over stop-isolated. |
| `docker-ga-e2e-logs.sh` | 1 | 2026-04-25 | Tail logs from the GA Follow-up E2E stack. |
| `docker-ga-e2e-up.sh` | 9 | 2026-06-07 | GA Follow-up E2E stack — thin wrapper over the converged isolated stack. |
| `docker-ga-showcase-e2e.sh` | 2 | 2026-05-26 | Run the GA showcase E2E gate from an isolated Docker Playwright runner. |
| `env/reset-and-init.sh` | 50 | 2026-05-17 |  |
| `faq-loop-golden-run.sh` | 3 | 2026-07-23 | faq-loop-golden-run.sh — one-click, self-contained golden for the conversation → FAQ loop. |
| `ga-e2e-prepare-deps.sh` | 3 | 2026-05-17 | Prepare pnpm dependencies for the GA Docker E2E frontend/runner containers. |
| `ga-showcase-e2e.sh` | 3 | 2026-05-10 | Run the GA community showcase E2E gate with isolated Playwright storage. |
| `host-oee-dashboard-golden.sh` | 1 | 2026-06-22 | Host-first OEE dashboard golden. |
| `import-plugins.sh` | 16 | 2026-06-13 | Import AuraBoot plugins into a running backend. |
| `import-templates.sh` | 2 | 2026-05-11 |  |
| `install-agent-git-hooks.mjs` | 2 | 2026-06-24 |  |
| `migrate-dsl-buttons.mjs` | 0 | 2026-03-26 | Migration script: batch-convert legacy button configs to unified action format. |
| `oss-golden-stack.sh` | 19 | 2026-07-23 | oss-golden-stack.sh — one-click host-first golden stack for OSS auraboot. |
| `oss-reset-and-init.sh` | 22 | 2026-07-13 | AuraBoot OSS Environment Reset and Initialization Script |
| `oss-test.sh` | 7 | 2026-06-22 | Run Playwright E2E tests restricted to the OSS scope defined in oss-scope.json. |
| `perf-ci/compare-baseline.sh` | 2 | 2026-03-26 | compare-baseline.sh — Compare k6 summary JSON files against a baseline. |
| `perf-ci/notify.sh` | 1 | 2026-03-26 | notify.sh — Send performance regression result notifications. |
| `perf-ci/run-perf-regression.sh` | 2 | 2026-05-11 | run-perf-regression.sh — Orchestrate performance regression checks. |
| `publish-repos.sh` | 1 | 2026-04-15 |  |
| `quickstart.sh` | 8 | 2026-07-13 | quickstart.sh — turn a freshly-started AuraBoot stack into a usable one. |
| `release/bump-version.sh` | 1 | 2026-06-18 | Bump the release version. Single source of truth = VERSION at repo root. |
| `reset-db.sh` | 13 | 2026-06-18 | AuraBoot Database Reset Script |
| `run-wf-e2e.sh` | 1 | 2026-06-22 | Thin wrapper over oss-test.sh for BPM / workflow-designer E2E suites. |
| `seed-acp-runtime-test.sh` | 1 | 2026-05-09 |  |
| `seed-marketplace.sh` | 5 | 2026-05-17 |  |
| `suspended-tenant-login-golden.sh` | 1 | 2026-07-17 | suspended-tenant-login-golden.sh — E5: a suspended organization cannot log in. |
| `sync-dsl-action-catalog.mjs` | 3 | 2026-07-15 | sync-dsl-action-catalog.mjs — the machine-readable contract for DSL button actions. |
| `sync-dsl-registry.sh` | 1 | 2026-07-16 | sync-dsl-registry.sh — Extract enums from backend Java DslRegistry and update dsl-registry.json |
| `sync-marketplace-catalog.sh` | 4 | 2026-05-18 | Synchronize the plugin marketplace catalog from plugin manifests. |
| `sync-platform-plugins.sh` | 5 | 2026-03-26 | Sync SSR platform plugin frontend/ dirs into web-admin/app/plugins/ |

## test (19)

Co-located `*.test.mjs`; run via the repo test task. Not listed individually.


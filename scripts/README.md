# `scripts/` — index & taxonomy (auraboot (OSS))

> Snapshot **2026-07-23**. Mechanically derived (git history + repo-wide name-reference scan); may lag reality between refreshes. **refs** = how many other tracked files (docs, `package.json`, other scripts) mention this file by name.

## Counts

| category | count | what it is |
|---|--:|---|
| **gate** | 43 | Correctness/quality checks (`check-*` / `validate-*` / `*-audit`). Run before push / in local gate runners. |
| **generator** | 5 | Regenerate a tracked artifact (manifests, snapshots). Output is committed; rerun when inputs change. |
| **entrypoint** | 31 | Self-contained runners invoked by hand / crontab (owner has no CI). `refs=0` is normal here — nothing imports them. |
| **pipeline/lib** | 10 | Shared library modules for the aura-pipeline / other scripts. Not run directly. |
| **tooling** | 67 | Reusable dev/ops helpers referenced by other scripts, package.json, or docs. |
| **test** | 20 | Co-located `*.test.mjs` unit tests for the scripts above. |

## Conventions

- **Gates** are named `check-*` / `validate-*` and must be falsifiable (green→inject defect→red). Wire them into `package.json` `validate:*` or a self-contained runner, not a dead `.yml`.
- **One-off migrations** (`migrate-*`, `*-split`, casing/backfill scripts) are **removed once the migration lands** — git history keeps them (`git log --all --full-history -- scripts/<name>`).
- Before adding a script, check the tables below for an existing one to extend.

### Owned OSS frontend restarts

`oss-golden-stack.sh up` and retained `resume` launch a stable registered Node
supervisor around the existing frontend command. Its inherited Node preload
registers each actual Web/BFF entrypoint before it can listen, including children
restarted by concurrently. pnpm/tsx helper processes never replace listener records.
Registration requires the current supervisor generation, complete owned ancestry,
the source cwd and runtime token; a still-live previous listener is not overwritten.
Use Workspace `runtime suspend` / `runtime resume` for whole-stack lifecycle.
Direct supervisor termination stops only its authenticated frontend tree and
prevents further child restarts; runtime databases and evidence remain retained.

### Product image gate ownership preservation

`application/run-product-release-image-gate.sh` refuses an existing work root,
including a symlink; recovery never implicitly removes the previous generation.
On explicit non-retained cleanup, a failed product lifecycle stop preserves the
work root and records `runtime-retained.txt` plus private `logs/product-stop.log`.
No container, image, network or work-root removal follows that failure. A prior
failure remains failed; otherwise cleanup failure returns environment exit2.
INT/TERM enter EXIT cleanup once and preserve130/143. Retained environments keep
the original review behavior; pause idle services through their product owner.

## gate (44)

| script | refs | updated | purpose |
|---|--:|---|---|
| `check-batch4-gates.mjs` | 0 | 2026-10-02 | Large-file and i18n CJK structural ratchets. |
| `check-agent-eval-boundary.mjs` | 2 | 2026-06-21 | OSS agent/RAG boundary gate. |
| `check-oss-remediation-t3.mjs` | 1 | 2026-10-05 | Enforce complete T3 family 80% line coverage, nested classes and parent size limits. |
| `check-batch4-gates.mjs` | 0 | 2026-10-03 | Check the large-file and i18n CJK structural ratchets. |
| `check-cache-eviction.mjs` | 3 | 2026-07-14 | check-cache-eviction — every @Cacheable cache must have *someone* who evicts it. |
| `check-capability-codes.mjs` | 0 | 2026-06-21 | Permission v2 capability-code drift gate. |
| `check-command-permissions.mjs` | 0 | 2026-07-23 | A command whose handler stage does real work must declare the permission that authorizes it. |
| `check-command-reachability.mjs` | 4 | 2026-07-23 | Gate: a declared command must have a way for a user to reach it. |
| `check-command-reachability.sh` | 0 | 2026-07-23 | Pre-push gate: every declared command needs a UI entry point. |
| `check-controller-authz.mjs` | 0 | 2026-07-15 | check-controller-authz.mjs — regression guard for the deep-review fail-open finding |
| `check-controller-authz.sh` | 0 | 2026-10-15 | Pre-push controller-authz fail-open gate (wraps check-controller-authz.mjs; replaces the disabled GitHub Actions workflow). |
| `check-airflow-provider-coverage.sh` | 0 | 2026-10-02 | Run hermetic provider tests and enforce 90% line and branch coverage per source file. |
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
| `check-dsl-render-types.mjs` | 1 | 2026-10-19 | Parse registry and table-column renderer capabilities using the pinned internal-api toolchain; `--self-test` verifies fixed fixtures. |
| `check-dsl-status-colors.mjs` | 1 | 2026-07-13 | check-dsl-status-colors.mjs — DSL dict status color semantics gate. |
| `check-e2e-assertions.mjs` | 1 | 2026-07-14 | check-e2e-assertions — an assertion that something is ABSENT must be able to fail. |
| `check-e2e-spec-registration.mjs` | 3 | 2026-07-23 | Gate: no E2E spec may exist without being selectable by some project. |
| `check-e2e-spec-registration.sh` | 0 | 2026-07-23 | Pre-push gate: no E2E spec may exist without being selectable by some project. |
| `check-form-back-link.mjs` | 1 | 2026-07-23 | Targets that point outside the scanned page set — each needs a reason. |
| `check-hand-written-page-matrix.mjs` | 0 | 2026-07-24 | Gate: no new hand-written page-coverage matrices — the denominator is generated. |
| `check-i18n-hardcoded.mjs` | 0 | 2026-06-20 | check-i18n-hardcoded.mjs — i18n hardcoded-Chinese gate (ratchet). |
| `check-jsonb-typehandler.sh` | 3 | 2026-06-11 | check-jsonb-typehandler.sh — guard against the recurring "varchar→jsonb on insert/update" bug. |
| `check-package-distribution.py` | 2 | 2026-10-02 | Pack all public OSS packages plus the private QR client; verify licenses, exports, README and clean consumers. Requires --enterprise-root and a fresh --out directory. Never publishes. |
| `check-license-boundary.sh` | 2 | 2026-09-27 | Fail when known copyleft dependency coordinates re-enter default distribution build files. |
| `check-no-secret-echo.mjs` | 1 | 2026-07-14 | check-no-secret-echo — refuse shell scripts that print a secret to stdout/stderr. |
| `check-no-secret-echo.sh` | 0 | 2026-07-14 | Wrapper so this gate shows up in `ls scripts/check-*.sh` — the repo's local-gate inventory. |
| `check-oss-scope-consistency.mjs` | 0 | 2026-10-02 | Verify OSS scope declarations against code anchors and plugin directories. |
| `check-oss-boundary.sh` | 3 | 2026-06-18 | OSS / Enterprise boundary check. |
| `check-oss-no-internal-docs.sh` | 1 | 2026-07-02 | Fail if internal-process docs are tracked in the public OSS repo. |
| `check-oss-scope-consistency.mjs` | 0 | 2026-10-03 | Verify OSS scope modules, plugin directories, and registry disjointness. |
| `check-public-record-id-contracts.sh` | 1 | 2026-06-24 |  |
| `check-public-record-openapi-contract.mjs` | 1 | 2026-06-24 | Validate public-record pid-only naming in a live or captured OpenAPI document. |
| `check-reset-init-contracts.sh` | 1 | 2026-05-28 |  |
| `check-scripts-index.mjs` | 2 | — | Falsifiable freshness gate for scripts/README.md (the scripts index). |
| `check-test-system.sh` | 0 | 2026-07-23 | Umbrella gate for the test system's own integrity. exit code = result. |
| `check-version-sync.sh` | 1 | 2026-06-18 | Gate: VERSION (release version, single source of truth) must equal |
| `application/run-product-release-archive-stage.sh` | 1 | 2026-10-25 | Run the product release archive stage and preserve its release evidence. |
| `project-delivery-demo.sh` | 1 | 2026-10-25 | Drive the project delivery walkthrough against an explicitly selected runtime. |
| `application/deployment-target-attestation.it.mjs` | 0 | 2026-09-25 | Verify target reservation and signed deployment observation through the protected executor. |
| `application/run-migrator-image-gate.mjs` | 0 | 2026-09-25 | Run the retained-container Linux migrator lifecycle gate and emit digest-bound evidence. |
| `db/check-db-matches-snapshot.sh` | 1 | 2026-07-23 | Answer one question about an EXISTING database: does it still match |
| `db/check-schema-drift.sh` | 1 | 2026-06-22 | Regenerate the schema snapshot from Flyway and diff it against the committed |
| `db/test-stage-pre1900-core-migrations.sh` | 0 | 2026-09-25 | Verify deterministic staging of immutable pre-#1900 Core migration bytes. |
| `digital-employee-capability-eval-run.sh` | 0 | 2026-07-25 | Managed capability-eval runner; full/focused/deterministic module-owned catalogs, native receipts and retained runtime. |
| `oss-e2e-gate-run.sh` | 0 | 2026-07-24 | oss-e2e-gate-run.sh — self-contained, one-command OSS E2E regression gate. |
| `provider-neutral-architecture-gate.sh` | 0 | 2026-07-31 | Fail closed when agent/runtime fixtures or goldens hard-code a model provider. |
| `permission-sql-registrations.mjs` | 0 | 2026-10-20 | Resolve permission registrations from migration SQL for permission validation. |
| `validate-permission-codes.mjs` | 2 | 2026-05-08 | Cross-source permission-code validator. |
| `permission-sql-registrations.mjs` | 1 | 2026-10-21 | Extract permission codes from SQL registrations for contract validation. |
| `validate-plugin-dashboards.mjs` | 1 | 2026-05-11 | Validates every plugins/<plugin>/config/dashboards/*.json against the Plan #8 |
| `validate-plugin-i18n.mjs` | 1 | 2026-04-14 | Validates every plugins/<plugin>/config/i18n.json against the plugin i18n contract. |
| `validate-public-record-id-contracts.mjs` | 2 | 2026-06-24 | Public dynamic-record id contract inventory and regression gate. |
| `validate-workflows.sh` | 0 | 2026-03-26 | Validate GitHub Actions workflow YAML files. |
| `gates/fixtures/workspace-control.mjs` | 0 | 2026-10-05 | Hermetic workspace-control fixture for gate tests. |
| `lib/golden-new-database.sh` | 0 | 2026-10-05 | Create an absent owned golden database without replacement. |
| `lib/golden-process-stop.mjs` | 0 | 2026-10-05 | Stop only identity-attested owned golden processes. |
| `lib/golden-private-redis.mjs` | 1 | 2026-10-21 | Start and attest an owned private Redis endpoint and persistent storage. |
| `lib/golden-frontend-supervisor.mjs` | 1 | 2026-10-18 | Keep a stable owned frontend parent across automatic listener restarts. |
| `lib/golden-frontend-registration.mjs` | 1 | 2026-10-18 | Register each authenticated Web/BFF generation before its entrypoint can listen. |
| `lib/golden-product-identity.mjs` | 0 | 2026-10-05 | Bind golden product environment and source identity. |
| `lib/golden-resume-state.mjs` | 0 | 2026-10-05 | Validate saved golden state before resume. |
| `lib/golden-runtime-identity.sh` | 0 | 2026-10-05 | Register declared golden source roots in workspace control. |
| `lib/golden-stack-state.mjs` | 0 | 2026-10-05 | Resolve owned current or legacy golden stack state. |
| `oss-golden-lifecycle.sh` | 0 | 2026-10-05 | Workspace suspend/resume lifecycle adapter for OSS golden stacks. |

## generator (5)

| script | refs | updated | purpose |
|---|--:|---|---|
| `db/generate-schema-snapshot.sh` | 3 | 2026-06-23 | Generate a deterministic schema-only snapshot from a freshly migrated DB. |
| `i18n-admin-browser-result.mjs` | 0 | 2026-10-04 | Validate current translation administration browser scenarios and execution provenance. |
| `i18n-admin-browser-run.sh` | 0 | 2026-10-04 | Run translation administration acceptance in an owned isolated runtime. |
| `gen-coverage-manifest.mjs` | 2 | 2026-07-23 | Generate a coverage manifest from the DSL and the test tree. |
| `generate-plugin-routes.mjs` | 5 | 2026-04-26 | scripts/generate-plugin-routes.mjs |
| `application/generate-product-extraction-inventory.mjs` | 1 | 2026-09-12 | Generate the BPM/CRM extraction owner, dependency, migration, route, and candidate-test denominator. |
| `application/create-release-screenshot-manifest.mjs` | 1 | 2026-09-14 | Create the checksum-bound screenshot manifest consumed by product release image gates. |

## entrypoint (31)

| script | refs | updated | purpose |
|---|--:|---|---|
| `oss-remediation-t3-run.mjs` | 1 | 2026-10-05 | Clean-commit managed regression with explicit PostgreSQL identity, fresh XML and coverage receipt. |
| `aurabot-scenario-golden-run.sh` | 0 | 2026-07-23 | aurabot-scenario-golden-run.sh — self-contained scenario golden for the |
| `backlog-stats.sh` | 0 | 2026-03-26 | Backlog dashboard stats — counts GAP statuses across all backlog files |
| `billing-it-run.sh` | 0 | 2026-07-31 | Exact-class billing integration-test runner with non-zero XML evidence checks. |
| `ci/prepare-library-candidate.sh` | 1 | 2026-10-23 | Prepare native runtime-library candidates under a frozen Jenkins context without application assembly or remote publication. |
| `deploy/prod-deploy.sh` | 0 | 2026-06-18 | Production deploy orchestrator. Runs the schema migration + release ledger |
| `dev/ci-env-export.sh` | 0 | 2026-05-09 | ci-env-export.sh — env contract for generic CI runners. Defaults are |
| `dev/ga-e2e-env-export.sh` | 0 | 2026-05-09 | ga-e2e-env-export.sh — env contract for the GA (GitHub Actions) E2E |
| `dev/run-agent-runtime-backend-gate.sh` | 0 | 2026-07-23 | Focused backend gate for the generic agent runtime architecture. |
| `dev/xxl-job-true-stack-smoke.sh` | 0 | 2026-06-07 |  |
| `digital-employee-golden-run.sh` | 0 | 2026-07-23 | digital-employee-golden-run.sh — self-contained browser golden for the digital |
| `docker-bootstrap.sh` | 0 | 2026-06-08 | docker-bootstrap.sh — Import plugins into an already-running Docker E2E backend. |
| `e2e-compliance-check.sh` | 0 | 2026-03-26 | E2E Compliance Gate — scans test files for violations of testing constitution |
| `e2e-report.sh` | 0 | 2026-03-26 | e2e-report.sh — View E2E test run results by testRunId |
| `e2e-run.sh` | 0 | 2026-03-31 | Unified E2E Test Runner — GAP-169 |
| `host-e2e-up.sh` | 0 | 2026-06-08 | Host-mode E2E stack bring-up — host parity with docker-ga-e2e-up.sh. |
| `hifi-golden-gate-run.sh` | 0 | 2026-10-02 | Run the fixed BI browser profile, reconcile every result, and retain its isolated runtime and evidence. |
| `gates/hifi-golden-results.mjs` | 1 | 2026-10-02 | Reject missing, duplicate, skipped, retried, or incomplete BI profile results; collection is never execution. |
| `check-batch4-gates.mjs` | 0 | 2026-10-02 | Enforce the existing large-file and CJK internationalization ratchets. |
| `check-oss-scope-consistency.mjs` | 0 | 2026-10-02 | Verify the OSS boundary registry against its declared modules and plugin directories. |
| `kb-ingestion-golden-run.sh` | 0 | 2026-07-13 | kb-ingestion-golden-run.sh — one command, whole knowledge-ingestion golden, exit code = verdict. |
| `local-pr-gate.sh` | 0 | 2026-07-18 | Local replacement for the required GitHub status checks. |
| `collab-trio-golden-run.sh` | 0 | 2026-07-25 | collab-trio-golden-run.sh — self-contained browser golden runner for the collaboration trio: Inbox, notification centre, IM/agent mentions. |
| `oss-backend-unit-ci.sh` | 1 | 2026-10-24 | Complete OSS Gradle test/bootstrap tasks with owned PostgreSQL/Redis/Kafka, fixed JVM arguments and ancestor-cgroup protected working-set admission. |
| `oss-backend-nightly-coverage.sh` | 0 | 2026-09-12 | Linux CI entrypoint for full backend coverage evidence. |
| `oss-init-env-only.sh` | 0 | 2026-05-11 | AuraBoot Quick Environment Initialization |
| `quick-filter-chip-golden-run.sh` | 0 | 2026-07-17 | quick-filter-chip-golden-run.sh — self-contained quick-filter view-chip browser golden runner. |
| `rbac-golden-result.mjs` | 0 | 2026-10-04 | Validate recent real RBAC browser receipts against the expected scenario inventory. |
| `rbac-golden-run.sh` | 0 | 2026-07-04 | rbac-golden-run.sh — self-contained RBAC platform-baseline browser golden runner. |
| `release/tag-release.sh` | 2 | 2026-07-25 | Gated OSS release tag entrypoint; runs capability and test-system gates on the exact release commit. |
| `run-open-platform-slo-gate.sh` | 1 | 2026-09-14 | Run smoke or production-threshold k6 checks for the Open Platform. |
| `run-open-platform-release-image-gate.sh` | 1 | 2026-09-14 | Linux x86_64 exact-ref release-image, two-domain protocol, production SLO, and Webhook drain gate (never pushes). |
| `open-platform-release-image-contract.test.mjs` | 1 | 2026-09-14 | Self-test the Open Platform CI gate's immutable-ref, no-push, SLO, and drain invariants. |
| `run-renderer-release-image-gate.sh` | 1 | 2026-09-14 | Build and verify the immutable renderer release image. |
| `suspended-tenant-login-ui-golden.sh` | 0 | 2026-07-17 | suspended-tenant-login-ui-golden.sh — E5, at the glass: what a user sees when their org is |
| `test-acp-runtime.sh` | 0 | 2026-05-09 |  |
| `application/application-cli.mjs` | 1 | 2026-09-27 | Validate application artifacts and publish an exact release through register, publication, and stable-channel CAS with a durable receipt. |
| `application/application-release-control.mjs` | 1 | 2026-09-27 | Publish a registered application release and move stable through replay-safe API operations backed by a durable local receipt. |
| `application/application-graph-adapters.mjs` | 1 | 2026-09-12 | Resolve typed Web contributions from local source roots or checksum-verified npm tarballs. |
| `application/audit-core-only-schema.sh` | 1 | 2026-09-12 | Fail closed when a migrated core-only database contains BPM/CRM tables or persisted product literals. |
| `application/create-release-screenshot-manifest.mjs` | 2 | 2026-09-13 | Build the immutable release screenshot manifest: stable IDs, image facts, and SHA-256 checksums for every captured screenshot. |
| `application/migration-composition-audit.mjs` | 0 | 2026-09-25 | Audit migration packages as one ownership-safe application composition. |
| `application/migration-payload.mjs` | 0 | 2026-09-25 | Package and verify reviewed SQL migration payloads with deterministic digests. |
| `application/migrator-entrypoint.mjs` | 0 | 2026-09-25 | Execute info, validate, or migrate against a verified migration payload. |
| `application/migration-ownership.mjs` | 1 | 2026-09-12 | Split legacy core migrations into deterministic core, CRM, and BPM ownership sets without Flyway version collisions. |
| `application/schema-ownership-audit.mjs` | 0 | 2026-09-25 | Audit declared migration ownership without rewriting released SQL. |
| `application/stage-core-only-artifacts.mjs` | 1 | 2026-09-12 | Stage a commit-bound core-only application artifact set and emit its catalog and lock. |
| `application/run-product-release-image-gate.sh` | 1 | 2026-09-12 | Linux CI Docker gate for immutable product release images and browser evidence. |
| `application/export-product-image.mjs` | 1 | 2026-10-22 | Export the tested OCI graph before native gate cleanup; checksum-bound strict archive consumption; immutable transport only. |
| `application/platform-artifact-package.mjs` | 1 | 2026-10-23 | Export/consume an explicitly selected native Core artifact package; original catalog/lock/source/OCI checks, no compilation or qualification. |
| `application/core-web-component.mjs` | 1 | 2026-10-24 | Capture declared Core Web inputs and verify/copy original Web/npm bytes with immutable producer provenance; no qualification. |

## pipeline/lib (12)

| script | refs | updated | purpose |
|---|--:|---|---|
| `lib/capability-eval-junit.mjs` | 1 | 2026-07-25 | Fail-closed JUnit receipt for the explicit digital-employee capability suite inventory. |
| `dev/start-web-stack.sh` | 0 | 2026-10-16 | Launch the existing Web/BFF stack in explicitly selected development or production mode. |
| `dev/lib/env-loader.sh` | 7 | 2026-05-22 | env-loader.sh — shell counterpart to web-admin/tests/helpers/environments.ts. |
| `dev/lib/env-registry.mjs` | 4 | 2026-05-22 |  |
| `dev/lib/health.sh` | 3 | 2026-05-22 | Sourceable health helpers for per-worktree dev stacks. |
| `dev/lib/process-manager.sh` | 3 | 2026-05-22 | Sourceable process helpers for per-worktree host dev services. |
| `lib/oss-ci-subnet.mjs` | 0 | 2026-10-04 | Select a non-overlapping subnet for isolated OSS CI Docker networks. |
| `lib/backend-ci-resources.mjs` | contract | 2026-10-24 | Admit the fixed JVM budget against every original cgroup soft/hard ancestor; credit conservatively clean file pages and explicitly reclaimable kernel slab, preserve raw/protected accounting, reject incomplete statistics, emit a task-owned plan. |
| `lib/multi-worktree-guard.sh` | 5 | 2026-05-22 | Multi-worktree pre-flight guard |
| `lib/plugin-config.mjs` | 3 | 2026-07-23 | Read a plugin's config regardless of how it is laid out on disk. |
| `lib/repo-root.mjs` | 5 | 2026-07-23 | Resolve the repo the test-system gates should run against. |
| `lib/reset-init-common.sh` | 7 | 2026-05-17 | Shared reset/init primitives. This file is sourced by lifecycle scripts; keep |
| `lib/runtime-process-owner.sh` | 4 | 2026-08-19 | Runtime-scoped process ownership and exact cleanup safeguards. |
| `lib/test-multi-worktree-guard.sh` | 1 | 2026-05-22 | Sanity tests for scripts/lib/multi-worktree-guard.sh |
| `lib/test-runtime-process-owner.sh` | 1 | 2026-08-19 | Fixture integration tests for runtime process ownership. |
| `lib/workspace-control.sh` | 2 | 2026-10-04 | Bind product lifecycle scripts to the canonical Workspace controller. |
| `lib/web-admin-node-modules.sh` | 2 | 2026-09-29 | Validate a reusable Web Admin dependency tree, including native-module ABI compatibility. |
| `application/application-contract.mjs` | 2 | 2026-09-12 | Shared manifest, lock, artifact identity, and checksum contract implementation. |
| `application/oci-layout.mjs` | 1 | 2026-09-12 | Build a digest-addressed Linux OCI layout with Docker on the admitted CI builder. |

## tooling (67)

| script | refs | updated | purpose |
|---|--:|---|---|
| `agent-git-guard.mjs` | 4 | 2026-06-24 |  |
| `agent-write-guard.mjs` | 2 | 2026-06-24 |  |
| `application/application-artifact-verifier.mjs` | 1 | 2026-09-12 | Dependency-free verifier for staged application lock identities, checksums, and migrations. |
| `application/application-release-requirements.mjs` | 0 | 2026-09-25 | Extract checksum-bound capability requirements from a registered application release. |
| `application/auraboot-core-env.sh` | 1 | 2026-09-12 | Lifecycle driver for a staged Core artifact set. |
| `application/deployment-capability-verifier.mjs` | 0 | 2026-09-25 | Verify deployment capabilities against application and platform release contracts. |
| `application/deployment-observation-attestation.mjs` | 0 | 2026-09-25 | Sign and verify expiry-bound deployment observation attestations. |
| `application/deployment-observation-fixture.mjs` | 0 | 2026-09-25 | Provide synthetic declarations and ephemeral keys for attestation tests. |
| `application/migration-execution-verifier.mjs` | 0 | 2026-09-25 | Verify a migration execution receipt against its exact invocation and result digests. |
| `application/registry-role-policy.mjs` | 0 | 2026-09-25 | Generate the reviewed database role policy for release registry tables. |
| `application/shadow-binding-role-policy.mjs` | 0 | 2026-09-25 | Generate the database role policy that prevents shadow writers from activating bindings. |
| `aps-fixtures/compare-strategies.sh` | 1 | 2026-05-28 | Compare APS V2 scheduling strategies on the fixture data set. |
| `db/cleanup-scheduler-residue.sh` | 0 | 2026-07-26 | Read-only-by-default cleanup for three exact retired system scheduler definitions. |
| `behavior-keyed-load-test.mjs` | 0 | 2026-06-23 |  |
| `db/deploy-migrate.sh` | 1 | 2026-06-18 | Deploy-time schema step: migrate the target DB, validate, and record the |
| `db/flyway-common.sh` | 5 | 2026-06-18 | Shared Flyway environment + invocation for AuraBoot PostgreSQL schema governance. |
| `db/flyway-migrate.sh` | 4 | 2026-06-18 | Run Flyway migrate for AuraBoot. |
| `db/flyway-validate.sh` | 2 | 2026-06-18 | Run Flyway validate for AuraBoot (checks applied migrations against the |
| `db/stage-pre1900-core-migrations.sh` | 0 | 2026-09-25 | Stage immutable pre-#1900 Core migration bytes for legacy checksum compatibility. |
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
| `dev/r2-env-export.sh` | 8 | 2026-05-22 | r2-env-export.sh — single-line `source` to set up the env for an |
| `dev/resolve-plugin-backends.mjs` | 3 | 2026-08-24 | Resolve requested plugin backends across the OSS root and explicit fallback roots for deterministic PF4J staging. |
| `dev/run-agent-runtime-full-gate-docker.sh` | 2 | 2026-05-21 | Fresh isolated Docker gate for the canonical agent runtime chain. |
| `dev/run-playwright-runner.sh` | 2 | 2026-05-12 | Run the optional Linux Playwright runner for an isolated stack. |
| `dev/start-dev-infra.sh` | 4 | 2026-05-22 | Start per-worktree infrastructure only for daily host-mode development. |
| `dev/start-isolated.sh` | 16 | 2026-05-26 | Start a per-worktree isolated docker dev stack. |
| `dev/start-production-like.sh` | 2 | 2026-05-12 | Start the B2 production-like frontend surface for an existing isolated stack. |
| `dev/stop-isolated.sh` | 10 | 2026-05-12 | Stop a per-worktree Docker dev stack. |
| `dev/test-dev-env-scripts.sh` | 1 | 2026-05-22 | Smoke tests for scripts/dev environment helpers. |
| `dev/test-gradle-guard.sh` | 1 | 2026-05-12 | Smoke test for platform/build.gradle multi-worktree Maven publish guard. |
| `dev/test-library-version.mjs` | 1 | 2026-10-23 | Run offline real-Gradle version/publication contracts and explicit mutation guards in fresh managed evidence. |
| `dev/verify-quickstart.sh` | 1 | 2026-05-17 | verify-quickstart.sh — pre-launch sanity check for the README quickstart. |
| `docker-ga-e2e-bootstrap.sh` | 6 | 2026-05-20 | Bootstrap the GA-E2E docker stack for OSS Playwright runs. |
| `docker-ga-e2e-down.sh` | 2 | 2026-05-26 | GA Follow-up E2E stack teardown — thin wrapper over stop-isolated. |
| `docker-ga-e2e-logs.sh` | 1 | 2026-04-25 | Tail logs from the GA Follow-up E2E stack. |
| `docker-ga-e2e-up.sh` | 9 | 2026-06-07 | GA Follow-up E2E stack — thin wrapper over the converged isolated stack. |
| `env/reset-and-init.sh` | 50 | 2026-05-17 |  |
| `extract-changelog-release.mjs` | 3 | 2026-09-27 | Extract one release section from CHANGELOG.md for publishing. |
| `faq-loop-golden-run.sh` | 3 | 2026-07-23 | faq-loop-golden-run.sh — one-click, self-contained golden for the conversation → FAQ loop. |
| `ga-e2e-prepare-deps.sh` | 3 | 2026-05-17 | Prepare pnpm dependencies for the GA Docker E2E frontend/runner containers. |
| `ga-showcase-e2e.sh` | 3 | 2026-05-10 | Run the GA community showcase E2E gate with isolated Playwright storage. |
| `import-plugins.sh` | 16 | 2026-06-13 | Import AuraBoot plugins into a running backend. |
| `import-templates.sh` | 2 | 2026-05-11 |  |
| `install-agent-git-hooks.mjs` | 2 | 2026-06-24 |  |
| `migrate-dsl-buttons.mjs` | 0 | 2026-03-26 | Migration script: batch-convert legacy button configs to unified action format. |
| `oss-golden-stack.sh` | 19 | 2026-10-04 | Host-first OSS stack. Stable runtime-owned state; `verify-artifacts` verifies registered sources, staged Core/PF4J bytes, actual listeners, database/Redis and BFF upstream before publishing the public product manifest. |
| `oss-reset-and-init.sh` | 24 | 2026-08-28 | AuraBoot OSS Environment Reset and Initialization Script. Requires `AURA_RESET_ALLOW_TARGETS="<pg_db>,<be_port>"` (target designation gate; `@any` overrides). |
| `observability-grafana-browser.mjs` | 0 | 2026-09-12 | Browser verification helper for the observability Grafana surface. |
| `observability-real-stack-ci.sh` | 0 | 2026-09-12 | CI entrypoint for the real-stack observability gate. |
| `oss-test.sh` | 7 | 2026-06-22 | Run Playwright E2E tests restricted to the OSS scope defined in oss-scope.json. |
| `perf-ci/compare-baseline.sh` | 2 | 2026-03-26 | compare-baseline.sh — Compare k6 summary JSON files against a baseline. |
| `perf-ci/notify.sh` | 1 | 2026-03-26 | notify.sh — Send performance regression result notifications. |
| `perf-ci/run-perf-regression.sh` | 2 | 2026-05-11 | run-perf-regression.sh — Orchestrate performance regression checks. |
| `performance/home-readiness.mjs` | 0 | 2026-10-16 | Install browser-side complete-home readiness and timing observations. |
| `performance/measure-api.mjs` | 0 | 2026-10-16 | Measure authenticated API response times against an explicit performance target. |
| `performance/measure-crud.mjs` | 0 | 2026-10-16 | Measure authenticated CRUD operations on the isolated performance fixture. |
| `performance/measure-home.mjs` | 0 | 2026-10-16 | Measure browser home readiness and retain the native performance evidence. |
| `performance/seed-api.mjs` | 0 | 2026-10-16 | Seed fixtures through the explicit performance API target. |
| `publish-repos.sh` | 1 | 2026-04-15 |  |
| `prepare-quickstart-env.py` | 2 | 2026-10-18 | Prepare missing persistent protocol signing material without printing or rotating existing keys. |
| `quickstart.sh` | 8 | 2026-07-13 | quickstart.sh — turn a freshly-started AuraBoot stack into a usable one. |
| `release/bump-version.sh` | 1 | 2026-06-18 | Bump the release version. Single source of truth = VERSION at repo root. |
| `reset-db.sh` | 13 | 2026-06-18 | AuraBoot Database Reset Script |
| `seed-acp-runtime-test.sh` | 1 | 2026-05-09 |  |
| `seed-marketplace.sh` | 5 | 2026-05-17 |  |
| `suspended-tenant-login-golden.sh` | 1 | 2026-07-17 | suspended-tenant-login-golden.sh — E5: a suspended organization cannot log in. |
| `sync-dsl-action-catalog.mjs` | 3 | 2026-07-15 | sync-dsl-action-catalog.mjs — the machine-readable contract for DSL button actions. |
| `sync-dsl-registry.sh` | 1 | 2026-07-16 | sync-dsl-registry.sh — Extract enums from backend Java DslRegistry and update dsl-registry.json |
| `sync-marketplace-catalog.sh` | 4 | 2026-05-18 | Synchronize the plugin marketplace catalog from plugin manifests. |
| `sync-platform-plugins.sh` | 5 | 2026-03-26 | Sync SSR platform plugin frontend/ dirs into web-admin/app/plugins/ |

## test (20)

Quickstart signing-key regression: `node --test scripts/quickstart-signing-key.test.mjs scripts/quickstart-session.test.mjs` checks key transport and private generation; it does not replace the Docker Quickstart real-stack workflow.

| script | purpose |
|---|---|
| `application/verify-agent-capabilities-http.mjs` | Targeted api / real-stack / http suite: run with explicit origin, installed/not_installed expectation, fresh evidence directory, and AURA_TEST_EMAIL/AURA_TEST_PASSWORD for the isolated bootstrap admin. Verifies six cases: auth denials, installation, no-store and a provisioned member with only the implicit baseline read permissions. Completes real business-space selection; missing prerequisites or a missing case fail. |
| `quickstart-signing-key.test.mjs` | Hermetic key transport, private generation, no overwrite and no shared default contracts. |

Co-located `*.test.mjs`; run via the repo test task. Not listed individually.

## Package distribution self-tests

`pnpm test:open-platform-tools` validates the shared probe transport and independent webhook
receiver, requires an invalid-signature mutation to fail, and verifies restoration. See
[`ci/README.md`](ci/README.md) for receiver operation and the explicitly unverified deployed-target scope.

`pnpm test:package-distribution` runs `test-package-distribution.py`: rejects missing or competing license text, retired v1.3 text, metadata/identity/privacy drift, missing exports and README mismatch. The pack gate also executes `fixtures/open-platform-consumer/consumer.test.mjs` and typechecks `fixtures/open-platform-consumer/consumer-ts-check.ts` against a freshly installed tarball outside the repositories. These contract tests use a loopback fixture server; they do not certify a staging deployment.

| script | refs | updated | purpose |
|---|---|---|---|
| `fixtures/open-platform-consumer/consumer.test.mjs` | 1 | 2026-10-02 | Fourteen consumer contract cases driven by the distribution gate from a fresh out-of-repo installation. |
| `fixtures/open-platform-consumer/real-backend.mjs` | 1 | 2026-10-13 | Run the fixed thirteen real-backend SDK cases against the verified fresh installation; accept credentials only on private stdin. |
| `ci/prepare-open-platform-sdk.sh` | 1 | 2026-10-13 | Fresh-build, pack and install the SDK outside the checkout; execute the original fourteen loopback consumers and type positive/expected-error fixture. Never publishes. |

T3 acceptance: `node scripts/oss-remediation-t3-run.mjs --executor /absolute/path/to/aura --runtime OWNED_RUNTIME --database-url jdbc:postgresql://127.0.0.1:5432/OWNED_TEST_DB --database-user TEST_USER --out /absolute/fresh/evidence-directory`. The executor supplies owned dependency homes. The database must already be migrated; this runner never resets or migrates it. `TEST_DATABASE_PASSWORD` is inherited without being recorded. Test skips are reported explicitly; a coverage pass proves the fixed family line target, not execution of skipped scenarios. Gate self-tests: `node --test scripts/check-oss-remediation-t3.test.mjs`.

## Runtime and release gate support (2026-10-10 reconciliation)

| script | refs | updated | purpose |
|---|---:|---|---|
| `application/dynamic-publication-role-policy.mjs` | 0 | 2026-10-10 | Build the isolated operator role policy for dynamic model publication. |
| `application/verify-release-registration-payload.mjs` | 0 | 2026-10-10 | Verify the release registration payload and its image copy. |
| `ci/isolated-release-network.sh` | 0 | 2026-10-10 | Allocate an isolated release-image network and record its subnet. |
| `ci/sample-open-platform-db.sh` | 0 | 2026-10-10 | Sample job-scoped database waits without exporting query text. |
| `db/snapshot-schema-contract.mjs` | 0 | 2026-10-10 | Validate deparsed snapshot schema resource-type constraints. |
| `dev/oss-disk-preflight.mjs` | 0 | 2026-10-10 | Check the filesystem space floor before verification allocation. |
| `dev/oss-gate-catalog-results.mjs` | 0 | 2026-10-10 | Reconcile collected test identities with the fixed gate catalog. |
| `dev/oss-gate-results.mjs` | 0 | 2026-10-10 | Reject missing, failed, skipped or retried browser gate results. |
| `dev/oss-runtime-contract.mjs` | 0 | 2026-10-10 | Bind and validate the OSS runtime source and artifact contract. |
| `lib/oss-stack-identity.mjs` | 0 | 2026-10-10 | Verify runtime artifacts and listener ownership for the OSS stack. |
| `lib/java-source-roots.mjs` | 6 | 2026-10-22 | Enumerate owned Java source roots across Gradle modules for shared scanners. |
| `lib/oss-stack-lifecycle.mjs` | 0 | 2026-10-10 | Manage registered OSS source mappings and stack lifecycle. |
| `lib/web-admin-lock-contract.mjs` | 0 | 2026-10-10 | Compare installed Web dependency locks with the frozen source lock. |
| `oss-e2e-fixture-backend.sh` | 0 | 2026-10-10 | Launch the unchanged backend with separately hashed E2E fixture artifacts. |

| `ci/prepare_openapi_verifier.sh` | 1 | 2026-10-14 | Bootstrap exact hashed dependencies for the isolated OpenAPI verifier. |
| `ci/verify_openapi_artifacts.py` | 1 | 2026-10-14 | Validate actual generated OpenAPI structure, component schemas, payloads and formats with a fixed case receipt. |

| `ci/collect-open-platform-jfr.sh` | 1 | 2026-10-15 | Collect request-correlated JFR evidence from the owned Open Platform release container without changing latency thresholds. |

## Scenario performance entry points

These local measurement helpers require an explicitly configured runtime and credentials; this index does not certify performance results.

| script | purpose |
|---|---|
| `performance/measure-api.mjs` | Measure authenticated API latency with recorded source identity. |
| `performance/measure-crud.mjs` | Measure create, update, detail and delete request latency on test records. |
| `performance/measure-home.mjs` | Measure browser home-page loading in the configured environment. |
| `performance/seed-api.mjs` | Prepare the explicitly selected local API measurement fixture. |

## Runtime and performance helpers (2026-10-16)

| script | category | purpose |
|---|---|---|
| `dev/start-web-stack.sh` | tooling | Start the governed development or production Web/BFF entrypoint. |
| `performance/home-readiness.mjs` | tooling | Observe browser readiness marks for the home performance runner. |

## Internal Web Admin API contracts

| script | category | purpose |
|---|---|---|
| `internal-api/contract.mjs` | contract | Validate, normalize and select explicit internal OpenAPI operations without removing schema constraints. |
| `internal-api/discover.mjs` | contract | Reconcile frontend network-call candidates with a captured runtime catalog; retain unresolved and unmigrated entries. |
| `internal-api/type-origins.mjs` | contract | Associate bound inventory calls with actual compiler types and declaration origins; retain casts, unknowns and unresolved symbols without claiming wire compatibility. |
| `internal-api/source-graph.mjs` | contract | Trace Web Admin entries, router resources, source aliases and workspace package exports; retain dynamic/data boundaries and unproven candidates. |
| `internal-api/export.mjs` | contract | Export or compare the internal OpenAPI group and create a fresh source-bound export receipt. |
| `internal-api/toolchain/generate.mjs` | codegen | Generate or check TypeScript declarations using the isolated, locked OpenAPI toolchain. |

| `internal-api/browser-catalog.mjs` | contract | Reconcile independently enumerated spec tests, scene captures and JSON evidence declarations with the fixed catalog. |
| `internal-api/browser-evidence.mjs` | contract | Validate source/runtime/report identity, mandatory observations and exact original-image review; retain functional and visual verdicts separately. |
| `internal-api/browser.mjs` | contract | Run owned catalog journeys against a verified managed stack and collect fresh source-bound functional or reviewed evidence. |
| `internal-api/foundation.mjs` | contract | Verify canonical spec, generated declarations and the exact registered offline unit denominator; emit a fresh source-bound consistency receipt. |
| `internal-api/drift.mjs` | contract | Compare an observed release-container OpenAPI document with the frozen snapshot; caller retains source and container provenance. |
| `ci/capture-internal-document.py` | contract | Capture an initialized CI image's authenticated internal document and revoke the issued session without bootstrapping or persisting credentials. |
| `internal-api/response-shape.mjs` | contract | Validate declared JSON response structures with the locked JSON Schema validator; formats and business semantics require separate assertions. |

### Product image transport

The existing Linux product image gate accepts `AURA_RELEASE_EXPORT_PACKAGE=1` to retain the
tested OCI graph as `image.oci.tar` in its evidence directory before work-root cleanup.
The default remains `0`. Export runs after native acceptance and records the actual archive
digest in the original receipt; it neither builds another image nor grants deployment authority.
Missing/corrupt OCI blobs, a different loaded image, partial native acceptance, external
descriptors and existing output are rejected. Export failure fails the original gate.
The original qualification profile and source/builder identity must include this transport mode
before a publisher can consume it; historical receipts without an archive cannot acquire one
by merely editing JSON. Local file/CLI self-tests use synthetic OCI bytes without Docker and
do not represent Linux image qualification.

The independent published-library consumer in [fixtures/agent-artifact-consumer](fixtures/agent-artifact-consumer/README.md) validates core/agent/app and opt-in fixtures through actual Maven POM and Gradle metadata, without producer project substitution.

| `application/verify-agent-cli-mcp.mjs` | Real-backend CLI/MCP acceptance: explicit CLI root, origin, installed/not_installed expectation, fresh evidence directory and bootstrap credentials. Runs status plus stdio and Streamable HTTP profile discovery and ordinary model queries; base also refuses direct AI actions. Registered/executed counts must match. |
| `dev/plugin-assembly.mjs` | Shared plugin profile assembly decision: accept only verified v1 agent installation, omit declared optional AI resources from base profiles, and reject explicit/unknown required-module imports before any selected plugin writes. |

| `application/verify-agent-im-history.mjs` | Real-backend retained mixed IM fixture: create under full, verify under base, then restore full against the same database. Checks member identities, nullable legacy availability, all prior human messages and a new persisted human message in each phase. Requires bootstrap credentials and a known owned-tenant agent ID for creation. |
| `application/verify-agent-base-business.mjs` | Owned no-AI business acceptance: explicit origin, fresh evidence directory, bootstrap credentials and `core` or `workflow` mode. Verifies capability absence, department create/update/read and human IM persistence. Workflow mode additionally requires an installed BPM product and proves an ordinary update succeeds while a mixed LLM flow is refused before its first write. Retains all uniquely marked fixtures; no model or vendor calls, resets or retries. |

The targeted `web-admin/tests/e2e/platform/agent-assembly.spec.ts` suite uses the
isolated bootstrap with no enabled tenant or platform LLM provider. Pin
`AURA_TEST_AGENT_STATE=installed|not_installed` to the selected backend assembly.
`AURA_TEST_AGENT_PROVIDER_MODE=stub` additionally verifies the designer send
button against the original backend and its explicitly enabled local LLM stub;
this is transport/assembly evidence, not live-provider or generated-DSL quality.
The base run reports the full-only draft case as not applicable.

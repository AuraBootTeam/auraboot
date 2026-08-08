# OSS E2E Task Log

## 2026-08-08 — CRM ownership isolation and record sharing

- [x] State calibration: isolated worktree and branch restored; canonical `main` clean.
- [x] Contract/unit/real-PostgreSQL mapper checks: 28 CRM Node, 90 Java unit, 1 mapper IT.
- [x] Test denominator: two `crm_sales` personas; self/other list and detail; owner/non-owner share; read-share update deny; revoke.
- [x] OSS default member share/revoke dialog implemented; enterprise renderer contribution remains the advanced override.
- [x] Fresh-stack preflight and CRM import: slot 73, fresh DB, current-source CRM PF4J staged before backend, demo 13/13.
- [x] Real-stack API and non-admin browser journey: 1/1 Playwright, 11.4s final cold-start rerun.
- [x] Mutation red/green proof: shared predicate removed -> 2/15 fail; restored -> 15/15 pass.
- [x] Full relevant OSS regression and cold-import/plugin validation.
- [ ] Evidence reconciliation and verified PR.

Evidence summary:

- Fresh database materialized `model.crm_account_common.read` as
  `crm_account_common/read/self`; no compatibility bridge or data migration was used.
- Two `crm_sales` personas proved owner/non-owner list and detail, non-owner share deny,
  owner UI share, read-share update deny, owner UI revoke, and post-revoke deny.
- CRM config contracts 28/28; focused Java 90/90; real PostgreSQL mapper IT 1/1;
  reset/init contracts 29/29; typecheck, changed-file ESLint, permission drift and E2E scope audit passed.
- Checkstyle/PMD completed with `BUILD SUCCESSFUL`; repository-wide pre-existing warnings remain.
- Hybrid builds use the runtime-specific Maven/Gradle directories; no shared `~/.m2` publish is required.

Known baseline observed before the new E2E run: repository-wide command reachability reports the
existing `crm:submit_qdp_review` and `crm:release_qdp` entries, and coverage freshness reports the
existing QDP rows, as unregistered/stale. These files are outside this ownership/share slice; the
targeted gate must still execute and report them without converting them into a new baseline.

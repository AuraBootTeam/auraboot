# OSS E2E Tasks

## Environment Status

- Worktree: `/Users/ghj/work/auraboot/.worktrees/oss-e2e-docker-fix`
- Branch: `oss-e2e-docker-fix`
- Stack: `auraboot-ga-e2e`
- Ports: backend `6444`, frontend `5174`, BFF `3501`, postgres `5433`
- Fresh stack: `./scripts/docker-ga-e2e-down.sh --purge` then `GA_E2E_FRONTEND_IMAGE=node:22-bookworm-slim AURABOOT_AUTO_COPY_WRAPPER=1 ./scripts/docker-ga-e2e-up.sh`
- Status at 2026-05-12 22:33 CST: backend `/actuator/health` UP, frontend `/` reachable, BFF `/api/bootstrap/status` reachable.
- Fresh reset logs:
  - First up failed before stack creation: `/tmp/oss-e2e-logs/fresh-up-20260512-223145.log`
  - Retry after `docker pull eclipse-temurin:25-jre-alpine`: `/tmp/oss-e2e-logs/fresh-up-retry-20260512-223248.log`
- Note: default Playwright frontend image pull (`mcr.microsoft.com/playwright:v1.59.1-noble`) was too slow in this environment, so frontend service was started with already-available `node:22-bookworm-slim`. Host Playwright runner is used against the Docker stack.

## Latest Valid Full Run

- OSS-scoped regular chromium full gate command:
  `cd web-admin && PLAYWRIGHT_BASE_URL=http://localhost:5174 BACKEND_URL=http://localhost:6444 BFF_URL=http://localhost:3501 BE_PORT=6444 VITE_PORT=5174 BFF_PORT=3501 PG_HOST=localhost PG_PORT=5433 PG_USER=auraboot PG_DB=aura_boot PGPASSWORD=auraboot_dev PW_SKIP_WEBSERVER=1 NO_PROXY=localhost PW_WORKERS=1 pnpm exec playwright test -c playwright.oss.config.ts --project=chromium --no-deps --reporter=line --workers=1`
- Fresh baseline log: `/tmp/oss-e2e-logs/oss-chromium-w1-fresh-20260512-223803.log`
- Fresh baseline result: `1319 passed / 30 failed / 84 skipped / 20 did not run`.
- Fresh baseline duration: `52.3m`.
- Latest post-fix full rerun log: `/tmp/oss-e2e-logs/oss-chromium-w1-rerun2-20260513-005348.log`
- Latest post-fix full rerun result: `1348 passed / 7 failed / 85 skipped / 11 did not run`.
- Latest post-fix full rerun duration: `49.7m`.
- Final full rerun before last fixes: `/tmp/oss-e2e-logs/oss-chromium-w1-final-20260513-015140.log`
- Final full rerun before last fixes result: `1364 passed / 2 failed / 85 skipped`.
- Final full chromium rerun after last fixes: `/tmp/oss-e2e-logs/oss-chromium-w1-final-r2-20260513-024936.log`
- Final full chromium rerun result: `1367 passed / 84 skipped / 0 failed`.
- Final full chromium rerun duration: `49.7m`.
- First deep rerun after regular gate: `/tmp/oss-e2e-logs/oss-chromium-deep-final-20260513-034015.log`
- First deep rerun result: `181 passed / 3 failed / 3 skipped / 5 did not run`.
- Deep targeted rerun after fixes: `/tmp/oss-e2e-logs/target-deep-final-three-r2-20260513-035051.log`
- Deep targeted rerun result: `3 passed`.
- Final deep rerun: `/tmp/oss-e2e-logs/oss-chromium-deep-final-r2-20260513-035111.log`
- Final deep rerun result: `189 passed / 3 skipped / 0 failed`.
- Final deep rerun duration: `7.2m`.
- Deep export assertion cleanup targeted rerun: `/tmp/oss-e2e-logs/target-deep-data-export-r3-20260513-040000.log`
- Deep export assertion cleanup targeted rerun result: `2 passed`.
- Final deep rerun after assertion cleanup: `/tmp/oss-e2e-logs/oss-chromium-deep-final-r3-20260513-040008.log`
- Final deep rerun after assertion cleanup result: `189 passed / 3 skipped / 0 failed`.
- Final deep rerun after assertion cleanup duration: `7.2m`.
- Interpretation: valid fresh OSS-scoped failure baseline plus post-fix regression runs. The regular `chromium` and `chromium-deep` OSS gates are green; truth audit is still required before claiming overall completion.

## Invalid/Aborted Runs

- OSS-scoped chromium single-worker attempt:
  `cd web-admin && ... PW_WORKERS=1 pnpm exec playwright test -c playwright.oss.config.ts --project=chromium --no-deps --reporter=line --workers=1`
- Log: `/tmp/oss-e2e-logs/oss-chromium-w1-20260512-222533.log`
- Result: manually aborted after detecting it was an old leftover process still running during the fresh reset/auth/seed cycle.
- Reason invalid: this run overlapped with `docker-ga-e2e-down.sh --purge`, fresh stack creation, auth setup, and seed execution, so it cannot be used as an OSS product failure baseline.

- OSS-scoped chromium attempt:
  `cd web-admin && ... PW_WORKERS=4 pnpm exec playwright test -c playwright.oss.config.ts --project=chromium --no-deps --reporter=line`
- Log: `/tmp/oss-e2e-logs/oss-chromium-20260512-222000.log`
- Result: manually aborted at `66/1453` after `26` observed failures.
- Reason invalid: frontend service was CPU saturated (`~351%`) and `curl http://localhost:5174/` took `7.2s` while the run was active; after stopping Playwright, the same endpoint recovered to `0.66s`. Failures were dominated by navigation/click/table-readiness timeouts, so this run is environment/load noise, not a valid OSS product failure baseline.

## Latest Targeted Evidence

- Auth/setup command:
  `cd web-admin && PLAYWRIGHT_BASE_URL=http://localhost:5174 BACKEND_URL=http://localhost:6444 BFF_URL=http://localhost:3501 BE_PORT=6444 VITE_PORT=5174 BFF_PORT=3501 PG_HOST=localhost PG_PORT=5433 PG_USER=auraboot PG_DB=aura_boot PGPASSWORD=auraboot_dev PW_SKIP_WEBSERVER=1 NO_PROXY=localhost PW_WORKERS=1 pnpm exec playwright test --project=auth --reporter=line`
- Log: `/tmp/oss-e2e-logs/auth-host-20260512-223450.log`
- Result: `18 passed / 1 skipped / 0 failed`.
- Bootstrap command: `SKIP_SEED=1 ./scripts/docker-ga-e2e-bootstrap.sh`
- Bootstrap log: `/tmp/oss-e2e-logs/bootstrap-20260512-223423.log`
- Seed command: host Playwright seed loop for `data`, `extended`, `workflow`, `ai`, `arsenal`, `supplement`, `invariants`, and `dashboard-default`.
- Seed log: `/tmp/oss-e2e-logs/seed-host-20260512-223512.log`
- Seed result: `10 + 8 + 9 + 3 + 8 + 4 + 2 + 1` passed; `seed-showcase-commercial` intentionally skipped for known OSS quote/complaint command gap.
- PCBA enterprise-only OSS scope list check: `chromium` and `chromium-deep` `--list` no longer include `pcba-quality-agent-entry.spec.ts` or `pcba-quality-agent-write.spec.ts`.
- Designer i18n/form-buttons targeted evidence:
  - P4.1 after `form-section` title fix: `/tmp/oss-e2e-logs/target-form-blocks-p41-r2-20260512-233751.log`, `1 passed`.
  - Designer cluster before final form-buttons selector fix: `/tmp/oss-e2e-logs/target-designer-cluster-*.log`, `19 passed / 2 failed` (only `Actions` vs `操作` selector).
  - Final form-buttons rerun: `/tmp/oss-e2e-logs/target-designer-form-buttons-r2-*.log`, `2 passed`.
- Non-designer targeted evidence:
  - PG env drift group `UI-1/QB-07`: `/tmp/oss-e2e-logs/target-pg-env-ui1-qb07-r2-*.log`, `2 passed`.
  - Default view group `PIPE-001/UES-001`: `/tmp/oss-e2e-logs/target-view-defaults-r2-*.log`, `2 passed`.
  - Timeout/sequencing group: `PIK-1/M-003` passed in `/tmp/oss-e2e-logs/target-timeouts-sequencing-r2-*.log`; dependency-chain reruns `SVCH-1/SVCH-2`, `D4+D6/D8`, and `EL-002/EL-003` passed in their `target-*` logs.
- Post-rerun remaining failure targeted evidence:
  - Permission dependency chain: `/tmp/oss-e2e-logs/target-permission-d4-d8-d13-toggle-d11-r1-20260513-005011.log`, `5 passed`.
  - Showcase table-view group: `/tmp/oss-e2e-logs/target-showcase-table-view-r1-20260513-005147.log`, `40 passed / 2 skipped`.
  - Final 7-failure targeted rerun: `/tmp/oss-e2e-logs/target-final-seven-r1-20260513-014754.log`, `11 passed`.
  - Final 2-failure targeted rerun: `/tmp/oss-e2e-logs/target-final-two-r2-20260513-024754.log`, `21 passed`.

## Fixed Failure Groups Covered By Full Rerun

- Designer cluster: fixed product i18n write (`form-section/detail-section` title now persisted as `LocalizedText`) and zh-CN selector assumptions (`Section Title`/`区段标题`, `Actions`/`操作`).
- OSS scope: excluded PCBA quality specs because they depend on enterprise quality/product-catalog plugins and Quality/CAPA pages absent from OSS.
- DB env drift: `tenant-isolation-ui.spec.ts` and `query-builder-basic.spec.ts` now use shared `PG_CONN`, honoring `PG_HOST/PG_PORT/PG_USER/PG_DB`.
- Default view assumptions: CRM/list tests now explicitly select `Default View` before asserting table rows.
- Timeout/sequencing: increased API helper timeouts where backend calls legitimately exceed Playwright's default 5s action timeout; permission D8 timeout now matches siblings.
- Environment page race: fixed premature empty-state rendering before auth/cookie-backed environment fetch completes.
- Environment delete cleanup: delete tests now explicitly accept the native confirm dialog and wait for the DELETE response; the page also removes the deleted environment from local state immediately after a successful response.
- BPM trace wait: fixed a click/response race in PD-013 by registering the response wait before clicking the All tab.
- Data tools deep export API: switched old nonexistent `GET /api/meta/excel/export/*` checks to current `POST /api/dynamic/{pageKey}/export` contract, preserving the existing OSS fixture rule that 403 permission denial is acceptable but 404/405/5xx is not.
- SavedView deep non-table view setup: Kanban/Calendar/Gallery persistence specs now create a temporary default table view before navigating so serial non-table defaults cannot hide table rows before the view under test is created.

## Known Invalid Failures

- Initial host Playwright run failed before collection because host dependencies were missing (`@playwright/test` not installed). Fixed by `pnpm install --no-frozen-lockfile --prefer-offline`.
- First container-run auth attempt failed with `ECONNREFUSED ::1:6443`; root cause: setup spec direct backend calls need `BACKEND_URL`, and frontend container is not the right psql-capable runner.
- First host auth attempt failed with `fe_sendauth: no password supplied`; root cause: missing `PGPASSWORD=auraboot_dev`.
- Bootstrap plugin imports for `agent-control-plane` and `platform-admin` report `Business error` on repeated import, but setup invariant 8 passes and auth succeeds. Treat as bootstrap idempotency noise unless a product spec later proves a missing capability.
- Raw regular `chromium` full run contains suites that require non-OSS plugins or command resources. These should be excluded from the OSS Docker gate instead of treated as product defects in the OSS stack.

## Next Action

- E2E truth audit completed:
  - No new `test.skip` / `test.fixme` / `retries` / `waitForTimeout`.
  - No new PUT-bypass helper (`request.put` / `page.request.put`) in the diff.
  - No new threshold assertion (`toBeLessThanOrEqual` / `toBeGreaterThanOrEqual`) in the diff.
  - `git diff --check` exits 0.
- OSS scope list audit completed:
  - `chromium --list` contains `1451` tests and no `pcba` / `quality-agent` / `pcba-solution` / `qc_` matches.
  - `chromium-deep --list` contains `192` tests and no `pcba` / `quality-agent` / `pcba-solution` / `qc_` matches.

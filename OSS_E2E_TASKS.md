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

- Unified Designer Workbench V3 broader OSS no-deps repair pass (2026-05-21 CST):
  - Preflight: frontend `5237`, BFF `3564`, backend `6443` were listening; backend `/actuator/health` returned `{"status":"UP"}`.
  - Invalid command attempt: `PW_PROFILE=oss PW_ROLE_PROJECTS=1 pnpm exec playwright test --project=chromium --no-deps` failed before execution because `PW_ROLE_PROJECTS=1` exposes role projects, not `chromium`; classified as command/profile invalid, not product failure.
  - Partial broader run command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test --project=chromium --no-deps --reporter=line`
  - Partial broader run log: `/tmp/oss-e2e-logs/oss-chromium-nodeps-20260521-1044.log`.
  - Partial broader run result: manually stopped at `84/3574` after collecting valid early failures; this is not a full gate pass and not environment-invalid.
  - Observed valid failures and classification:
    - `DP-002/DP-003/DP-004` Data Permissions form showed title but no fields. Root cause: runtime still expected V2 top-level `form-section`, while runtime data can contain recursive V3-like `blockType=form` with nested blocks.
    - `PA-007` BPM Domain Config edit returned 422. Root cause: `ab_bpm_domain_config.*_fields` physical columns are JSONB while imported field metadata drifted to `text`, so command field mapping did not use `::jsonb`.
    - `ACT-001` Activity Timeline assertion could pick the newest `system` activity (`submit_order`) instead of the create activity. Root cause: test asserted the first system-like activity rather than existence of `create_order`.
  - Data Permissions fix: `canonicalizePageDsl` now normalizes recursive runtime root `form` blocks into current legacy form runtime sections/buttons without breaking plain V2 sections.
  - Data Permissions unit command: `cd web-admin && pnpm exec vitest run app/framework/meta/utils/__tests__/canonicalizePageDsl.test.ts`
  - Data Permissions unit result: `1 passed / 5 passed`.
  - Data Permissions targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test tests/e2e/admin/data-permissions.spec.ts --project=chromium --grep "DP-002|DP-003|DP-004" --no-deps --reporter=line`
  - Data Permissions targeted E2E result: `3 passed`.
  - Platform Admin JSONB fix: `CommandFieldMapExecutor` now unions model-declared JSON/JSONB fields with physical PostgreSQL JSONB columns from `information_schema`; `FormPageContent` treats `jsonb` as JSON-like; `platform-admin` BPM domain config field source now marks `process_keys/list_fields/filter_fields/sort_fields` as `jsonb`.
  - Backend unit command: `cd platform && ./gradlew :test --tests com.auraboot.framework.meta.service.impl.CommandFieldMapExecutorReferencePidCompanionTest`
  - Backend unit result: `3 passed`.
  - Frontend unit command: `cd web-admin && pnpm exec vitest run app/framework/meta/rendering/pages/__tests__/FormPageContent.test.ts`
  - Frontend unit result: `12 passed`.
  - Runtime verification setup: OSS worktree core published to per-worktree Maven repo with `./gradlew -Dmaven.repo.local=/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/.m2/repository publishToMavenLocal -x test`; 6443 was restarted from canonical enterprise with the same per-worktree repo, and classpath confirmed `auraboot-core` came from `.worktrees/unified-designer-workbench-v3/.m2/repository`.
  - Enterprise worktree boot attempt is environment-invalid for this pass: it failed before health due missing `StringRedisTemplate` bean in that worktree; canonical enterprise + per-worktree core was used to verify the OSS core fix without writing default `~/.m2`.
  - Platform Admin targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test tests/e2e/admin/platform-admin-crud.spec.ts --project=chromium --grep "PA-007" --no-deps --reporter=line`
  - Platform Admin targeted E2E result: `1 passed`.
  - Activity Timeline fix: `ACT-001` now asserts that a `create_order` activity exists anywhere in the returned timeline instead of assuming the first system-like activity is the create event.
  - Activity targeted command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test tests/e2e/activity/activity-timeline.spec.ts --project=chromium --no-deps --reporter=line`
  - Activity targeted result: `5 passed`.
  - Fresh continuation rerun after the runtime repairs:
    - `cd web-admin && NO_PROXY=localhost,127.0.0.1 pnpm exec vitest run app/framework/meta/utils/__tests__/canonicalizePageDsl.test.ts app/framework/meta/rendering/pages/__tests__/FormPageContent.test.ts` -> `17 passed`.
    - `cd platform && ./gradlew :test --tests com.auraboot.framework.meta.service.impl.CommandFieldMapExecutorReferencePidCompanionTest` -> `3 passed`.
    - `cd web-admin && NO_PROXY=localhost,127.0.0.1 pnpm typecheck` -> passed.
    - `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test tests/e2e/admin/data-permissions.spec.ts --project=chromium --grep "DP-002|DP-003|DP-004" --no-deps --reporter=line` -> `3 passed`.
    - `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test tests/e2e/admin/platform-admin-crud.spec.ts --project=chromium --grep "PA-007" --no-deps --reporter=line` -> `1 passed`.
    - `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test tests/e2e/activity/activity-timeline.spec.ts --project=chromium --no-deps --reporter=line` -> `5 passed`.
    - `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --no-deps --reporter=line` -> `61 passed`.
  - E2E truth continuation audit:
    - `unified-designer-workbench.spec.ts` contains `691` UI operation lines and `21` setup/helper `page.request` lines; the single `page.request.put` is named-query-field setup idempotency, not a product-path bypass.
    - `activity-timeline.spec.ts` is intentionally API-heavy for Activity API verification; the two `toBeGreaterThanOrEqual(2)` assertions are semantic lower bounds because setup creates at least the command activity plus a NOTE.
    - `test.skip/test.fixme/waitForTimeout/page.goto('/p/')/retries` scan for the audited specs returned no hits.
  - Broader full-sampling attempt after targeted repair:
    - Command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test --project=chromium --no-deps --reporter=line`.
    - Log: `/tmp/oss-e2e-logs/oss-chromium-nodeps-full-rerun-20260521-1132.log`.
    - Result: manually stopped after valid failures were collected; the process exited via interrupt (`code -1`). It reached at least `361/3574`.
    - Scope correction: this was a broad `fast/chromium` file sweep and included enterprise distribution directories such as `annual-plan` and `asset-management`; it is not the documented OSS Platform Gate command.
    - Relevant positive signal: the run passed the previously failing `Data Permissions`, `Platform Admin JSONB`, and `Activity Timeline` segments before moving into unrelated suites.
    - Collected failures:
      - `acp-form-crud.spec.ts CRUD-25` failed during the broad sweep because the created artifact row was not found. A direct targeted rerun of `CRUD-25` passed (`1 passed`), so this is not currently a stable standalone reproduction.
      - `announcement-lifecycle.spec.ts archive and delete announcement` failed in the broad sweep because the test looked for English `delete` while the actual localized row action is `删除`. Running only that final test is invalid because it depends on prior file-local lifecycle setup; the whole-file targeted rerun is the valid reproduction shape.
      - `annual-plan` and `asset-management` failures reported `Command not found` for enterprise distribution commands (`ap:create_annual_plan`, `pm:create_project`, `asset:create`). These belong to enterprise distribution/import scope, not the Unified Designer feature-slice gate.
    - Gate profile follow-up: `PW_PROFILE=oss PW_ROLE_PROJECTS=1 pnpm exec playwright test --list` and `PW_PROFILE=oss pnpm exec playwright test --list` initially listed only setup/auth-related `19` tests. `playwright.config.ts` has now been updated to implement the documented `oss/oss-deep`, `contract`, `enterprise-smoke`, and `enterprise-full` projects using directory boundaries from `e2e-scope-boundaries.md`.
    - Gate profile list verification after the config fix:
      - `PW_PROFILE=oss PW_ROLE_PROJECTS=1 pnpm exec playwright test --list` -> `1782` tests in `251` files.
      - `PW_PROFILE=contract pnpm exec playwright test --list` -> `904` tests in `105` files.
      - `PW_PROFILE=enterprise-smoke pnpm exec playwright test --list` -> `364` tests in `83` files.
      - `PW_PROFILE=enterprise-full pnpm exec playwright test --list` -> `1357` tests in `136` files.
    - These are collection/gate-boundary checks only; they do not claim the listed gates have passed.
    - Correct-profile execution slices after the config fix:
      - `PW_PROFILE=contract PW_WORKERS=1 pnpm exec playwright test --project=contract tests/e2e/action-system/action-types.spec.ts tests/e2e/activity/activity-timeline.spec.ts --reporter=line` -> `41 passed / 1 skipped`.
      - `PW_PROFILE=oss PW_WORKERS=1 pnpm exec playwright test --project=oss tests/e2e/designer/unified-designer-workbench.spec.ts --reporter=line` -> `79 passed / 1 skipped`.
    - These prove the new gate projects execute with setup/auth dependencies and that Unified Designer passes under the documented OSS project. They still do not replace a full `PW_PROFILE=oss` run.
  - Fresh OSS full-gate attempt after profile correction:
    - Command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=oss PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test --reporter=line`.
    - Log: `/tmp/oss-e2e-logs/oss-profile-full-20260521-continue.log`.
    - Result: stopped at `142/1782` on `announcement-lifecycle.spec.ts archive and delete announcement`; this is a real test failure, not environment-invalid.
    - Root cause: the test passed `delete` to `clickRowAction`, while the product UI and the rest of the same spec use localized labels (`发布`, `撤回`, `重新发布`, `删除`).
    - Fix: changed the final delete row action assertion to use `删除`.
    - Targeted rerun command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=oss PW_WORKERS=1 pnpm exec playwright test --project=oss tests/e2e/announcement/announcement-lifecycle.spec.ts --reporter=line`.
    - Targeted rerun result: `26 passed / 1 skipped`.
  - Fresh OSS full-gate attempt after the announcement fix:
    - Command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=oss PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test --reporter=line`.
    - Log: `/tmp/oss-e2e-logs/oss-profile-full-r2-20260521.log`.
    - Result: announcement lifecycle passed in the full run; the next valid failures were `aurabot/pcba-*` specs at `228/1782`.
    - Failure class: scope-boundary bug, not product runtime failure. The specs require enterprise PCBA menus/plugins such as `PCBA ERP`, `product-catalog`, and `pcba-solution`, but they live under `tests/e2e/aurabot` instead of enterprise distribution directories.
    - Fix: added `enterpriseDistributionAuxSpecPattern` for `tests/e2e/aurabot/pcba-*.spec.ts`, excluded it from OSS/OSS-deep, and included it in `enterprise-smoke`/`enterprise-full`.
    - List audit after the scope fix:
      - `PW_PROFILE=oss PW_ROLE_PROJECTS=1 pnpm exec playwright test --list` -> `1776` tests in `247` files; grep for `aurabot/pcba`, `pcba-solution`, and `tests/e2e/pcba/` returned no hits.
      - `PW_PROFILE=contract pnpm exec playwright test --list` -> `904` tests in `105` files.
      - `PW_PROFILE=enterprise-smoke pnpm exec playwright test --list` -> `366` tests in `85` files, including `pcba-procurement-agent-entry` and `pcba-quality-agent-entry`.
      - `PW_PROFILE=enterprise-full pnpm exec playwright test --list` -> `1363` tests in `140` files, including all four `aurabot/pcba-*` specs.
  - Remaining gate status: localized announcement failure and PCBA auxiliary scope leakage are fixed and audited; a fresh controlled `PW_PROFILE=oss PW_ROLE_PROJECTS=1` full rerun is still required before any new OSS full-gate completion claim.

- Unified Designer Workbench V3 post-fix verification pass (2026-05-21 CST):
  - Worktree: `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot`
  - Ports: frontend `5237`, BFF `3564`, backend `6443`.
  - Health check: `curl -I http://localhost:5237/unified-designer` returned HTTP 302 to login and `curl http://localhost:6443/actuator/health` returned `{"status":"UP"}`.
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Affected runtime unit slice command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__/RecursiveBlockRenderer.test.tsx -t "validates form field rules|validates repeater and subform|passes runtime form values|renders editable repeater|renders nested subform"`
  - Affected runtime unit slice result: `5 passed / 36 skipped`.
  - Affected E2E slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-022|UDW-030|UDW-038|UDW-039|UDW-061" --no-deps`
  - Affected E2E slice result: `5 passed`.
  - Page Manager entry command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/admin/page-schema-list.spec.ts --project=chromium --grep "PS-004" --reporter=line --no-deps`
  - Page Manager entry result: `1 passed`.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 145 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `61 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `79 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=781`, `expectCalls=1107`, assertion method calls `981`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `retries=0`, `page.request.put=1` named-query-field setup fallback only. Existing 4 `toBeGreaterThan(0)` checks are data non-empty assertions, not threshold looseners.

- Unified Designer Workbench V3 nested form validation evidence (2026-05-21 CST):
  - Worktree: `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot`
  - Ports: frontend `5237`, BFF `3564`, backend `6443`.
  - Health check: `curl -I http://localhost:5237/unified-designer` returned HTTP 302 to login and `curl http://localhost:6443/actuator/health` returned `{"status":"UP"}`.
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Targeted unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__/RecursiveBlockRenderer.test.tsx -t "validates repeater and subform"`
  - Targeted unit result: `1 passed / 40 skipped`.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 145 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-061" --no-deps`
  - Targeted E2E result: `1 passed`.
  - Serial contamination slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-030|UDW-061" --no-deps`
  - Serial contamination slice result: `2 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `61 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `79 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=781`, `expectCalls=1107`, assertion method calls `981`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `retries=0`, `page.request.put=1` named-query-field setup fallback only. Existing 4 `toBeGreaterThan(0)` checks are data non-empty assertions, not threshold looseners.

- Unified Designer Workbench V3 live namedQuery AI fill field backfill evidence (2026-05-21 CST):
  - Worktree: `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot`
  - Ports: frontend `5237`, BFF `3564`, backend `6443`.
  - Health check: `curl -I http://localhost:5237/unified-designer` returned HTTP 302 to login and `curl http://localhost:6443/actuator/health` returned `{"status":"UP"}`.
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Targeted unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__/RecursiveBlockRenderer.test.tsx -t "runtime AI fill suggestions"`
  - Targeted unit result: `1 passed / 39 skipped`.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 144 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-060" --no-deps`
  - Targeted E2E result: `1 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `60 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `78 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=799`, `hardAssertions=1096`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `retries=0`, `page.request.put=1` named-query-field setup fallback only. Existing 4 `toBeGreaterThan(0)` checks are data non-empty assertions, not threshold looseners.

- Unified Designer Workbench V3 AI fill field backfill evidence (2026-05-21 CST):
  - Worktree: `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot`
  - Ports: frontend `5237`, BFF `3564`.
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 143 passed`.
  - Targeted unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__/RecursiveBlockRenderer.test.tsx`
  - Targeted unit result: `1 passed / 39 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-059" --no-deps`
  - Targeted E2E result: `1 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `59 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `77 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=796`, `hardAssertions=1080`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `retries=0`, `page.request.put=1` named-query-field setup fallback only. Existing 3 `toBeGreaterThan(0)` checks are data non-empty assertions, not threshold looseners.

- Unified Designer Workbench V3 form span quick controls evidence (2026-05-21 CST):
  - Worktree: `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot`
  - Ports: frontend `5237`, BFF `3564`.
  - Health check: `curl -sS -I http://localhost:5237/unified-designer` returned HTTP 302 to login and `curl -sS http://localhost:6443/actuator/health` returned `{"status":"UP"}`.
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 142 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-058" --no-deps`
  - Targeted E2E result: `1 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `58 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `76 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=793`, `hardAssertions=1073`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `retries=0`, `page.request.put=1` named-query-field setup fallback only. Existing 3 `toBeGreaterThan(0)` checks are data non-empty assertions, not threshold looseners.

- Unified Designer Workbench V3 form action condition evidence (2026-05-21 CST):
  - Worktree: `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot`
  - Ports: frontend `5237`, BFF `3564`.
  - Health check: `curl -sS -I http://localhost:5237/unified-designer` returned HTTP 302 to login and `curl -sS http://localhost:6443/actuator/health` returned `{"status":"UP"}`.
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 142 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-057" --no-deps`
  - Targeted E2E result: `1 passed`.
  - Serial contamination slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-057|UDW-022" --no-deps`
  - Serial contamination slice result: `2 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `57 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `75 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=786`, `hardAssertions=1061`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `retries=0`, `page.request.put=1` named-query-field setup fallback only. Existing 3 `toBeGreaterThan(0)` checks are data non-empty assertions, not threshold looseners.

- Unified Designer Workbench V3 helper permission evidence (2026-05-20 CST):
  - Worktree: `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot`
  - Ports: frontend `5237`, BFF `3564`.
  - Health check: `curl -fsS http://localhost:3564/health` returned BFF healthy and backend UP; `curl -fsS -I http://localhost:5237/unified-designer` returned HTTP 302 to login, proving the feature-worktree frontend is reachable.
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 137 passed`.
  - Page Manager entry fix: `plugins/page-manager/config/pages.json` now opens page schemas in `/unified-designer?pageId={pid}` by default, preserves `/page-designer/{pid}` as `edit_legacy`, and redirects newly created page schemas into Unified Designer.
  - Page Manager entry test: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__/pageManagerConfig.test.ts`
  - Page Manager entry test result: `1 passed`.
  - Runtime resource refresh: reimported `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot/plugins/page-manager` through `/api/plugins/import/import-directory-sync`; result `success=true`, `PAGE UPDATE=2`.
  - Page Manager row-click E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/admin/page-schema-list.spec.ts --project=chromium --grep "PS-004" --reporter=line --no-deps`
  - Page Manager row-click E2E result: `1 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --grep "UDW-052" --reporter=line --no-deps`
  - Targeted E2E result: `1 passed`.
  - Helper/detail slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --grep "UDW-049|UDW-050|UDW-051|UDW-052" --reporter=line --no-deps`
  - Helper/detail slice result: `4 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --reporter=line --no-deps`
  - Workbench full-slice result: `52 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 VITE_PORT=5237 BFF_PORT=3564 PW_PROFILE=full PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test -c playwright.config.ts tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --reporter=line`
  - Workbench with setup/auth result: `70 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=694`, `hardAssertions=794`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `threshold/retries=0`. One `page.request.put` remains in beforeAll named-query-field idempotent setup and is not a product-path PUT bypass.

- Unified Designer Workbench V3 subform evidence (2026-05-20 CST):
  - Worktree: `/Users/ghj/work/auraboot/.worktrees/unified-designer-workbench-v3/auraboot`
  - Stack: `auraboot-unified-v3-api`
  - Ports: frontend `5185`, BFF `3512`, backend `6455`, postgres `5444`, redis `6490`
  - Health check: backend `http://localhost:6455/actuator/health` returned `UP`; frontend `http://localhost:5185/unified-designer` reachable and mounted to the feature worktree.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__/RecursiveBlockRenderer.test.tsx app/plugins/core-designer/components/unified-designer/__tests__/UnifiedDesignerWorkbench.test.tsx app/plugins/core-designer/components/unified-designer/__tests__/v3-utils.test.ts`
  - Unit result: `97 passed / 0 failed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5185 PW_STORAGE_DIR=tests/storage/unified-v3-api PW_ARTIFACT_DIR=test-results/unified-designer-subform-e2e PW_RESULTS_JSON=test-results/unified-designer-subform-e2e/results.json PW_REPORT_DIR=test-results/unified-designer-subform-e2e/html PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium --grep "UDW-039"`
  - Targeted E2E result: `19 passed / 1 skipped / 0 failed` including setup/auth dependencies and `UDW-039`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5185 PW_STORAGE_DIR=tests/storage/unified-v3-api PW_ARTIFACT_DIR=test-results/unified-designer-workbench-subform-full PW_RESULTS_JSON=test-results/unified-designer-workbench-subform-full/results.json PW_REPORT_DIR=test-results/unified-designer-workbench-subform-full/html PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --project=chromium`
  - Workbench full-slice result: `57 passed / 1 skipped / 0 failed`.
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill=396`, `page.request/request=17`, `skip/fixme=0`, `waitForTimeout=0`, `request.put=0`, `threshold/retries=0`, `git diff --check` passed.
  - Visual evidence:
    - `/Users/ghj/work/auraboot/unified-designer-subform-visual-20260520.png`
    - `/Users/ghj/work/auraboot/unified-designer-subform-runtime-visual-20260520.png`

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

- Unified Designer Workbench V3 next action:
  - Relation picker field authoring is now covered: backend model field `refTarget` is mapped into `ModelFieldDefinition`, relation/reference/lookup fields dropped from the model field palette become `component=picker` with model source/value/display/search defaults, browser test `UDW-053` verifies drag-to-form, Inspector configuration, local save, and reload, and `UDW-054` verifies drag-to-list-filter, V3 writeback, preview picker selection, and real table row filtering. Runtime unit coverage also verifies picker filter option loading and row filtering.
  - Field/filter/column block-level permission is now covered: Inspector exposes `props.permissionCode`; runtime gates form inputs, list filter controls, table headers, and table cells; browser test `UDW-055` verifies save/writeback and preview data gating.
  - Row action conditional behavior is now covered: Inspector exposes `props.visibleWhen` and `props.disabledWhen`; runtime evaluates rules against current row fields plus `current.row.*` / `current.rowId`; browser test `UDW-056` verifies V3 save/readback, disabled first-row action, and hidden second-row action.
  - Form action conditional behavior is now covered: browser test `UDW-057` configures action `visibleWhen/disabledWhen` from form values, verifies schema-driven Inspector JSON writeback, save/reload persistence, preview hidden/enabled/disabled states, and keeps `UDW-022` isolated by clearing inherited action conditions before validation testing.
  - Form field span quick controls are now covered at browser level: `UDW-058` changes a field from layout mode quick controls, saves, reloads, reads V3 back through `/api/pages/{pid}`, and asserts runtime preview grid style.
  - AI fill preview-time field backfill is now covered for both static Inspector suggestions and live namedQuery suggestions: `RuntimeAiFillBanner` writes `{ field, value }` suggestions into the current form context, `UDW-059` verifies static `props.suggestedFields`, and `UDW-060` verifies real `/api/meta/named-queries/{code}/execute` response values update the target form input.
  - Repeater/subform nested row validation is now covered: runtime validation now walks nested row containers, renders row-field errors, clears them when row values change, and `UDW-061` verifies action execution stays blocked until repeater and subform required fields are filled.
  - Keep expanding V3 runtime coverage toward production relation subform persistence and richer nested form validation; current `subform` evidence covers designer authoring, row editor preview, payload writeback, and persistence.
  - Product-integration gaps now sit outside generic workbench mechanics: real AI generation service, real BPM/timeline/field-history business queries, and backend-enforced data policy semantics beyond preview-time block conditions.
  - Before any completion claim for the whole Unified Designer effort, run `/e2e-truth` discipline against the final diff and then decide whether a broader OSS/enterprise gate is required beyond the workbench full-slice.
- Unified Designer Workbench V3 relation picker evidence (2026-05-21 CST):
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 139 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-053" --no-deps`
  - Targeted E2E result: `1 passed`.
  - Picker/relation slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-(027|030|032|053)" --no-deps`
  - Picker/relation slice result: `4 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `54 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `73 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=726`, `hardAssertions=986`, `page.request/request=28`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `threshold/retries=0`, `page.request.put=1` named-query-field setup fallback only.
- Unified Designer Workbench V3 field/filter/column permission evidence (2026-05-21 CST):
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 140 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-055" --no-deps`
  - Targeted E2E result: `1 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `55 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `73 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=760`, `hardAssertions=1029`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `retries=0`, `page.request.put=1` named-query-field setup fallback only. Existing `toBeGreaterThan(0)` checks are data non-empty assertions, not threshold looseners.
- Unified Designer Workbench V3 row action condition evidence (2026-05-21 CST):
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 141 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-056" --no-deps`
  - Targeted E2E result: `1 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `56 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `74 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=769`, `hardAssertions=1044`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `retries=0`, `page.request.put=1` named-query-field setup fallback only. Existing `toBeGreaterThan(0)` checks are data non-empty assertions, not threshold looseners.
- Unified Designer Workbench V3 form action condition evidence (2026-05-21 CST):
  - TypeScript command: `cd web-admin && pnpm typecheck`
  - TypeScript result: passed.
  - Unit command: `cd web-admin && pnpm exec vitest run app/plugins/core-designer/components/unified-designer/__tests__`
  - Unit result: `10 passed / 142 passed`.
  - Targeted E2E command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-057" --no-deps`
  - Targeted E2E result: `1 passed`.
  - Serial contamination slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --grep "UDW-057|UDW-022" --no-deps`
  - Serial contamination slice result: `2 passed`.
  - Workbench full-slice command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts --no-deps`
  - Workbench full-slice result: `57 passed`.
  - Workbench with setup/auth command: `cd web-admin && NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_ROLE_PROJECTS=1 PW_WORKERS=1 pnpm exec playwright test tests/e2e/designer/unified-designer-workbench.spec.ts`
  - Workbench with setup/auth result: `75 passed / 1 skipped`.
  - E2E truth audit for `web-admin/tests/e2e/designer/unified-designer-workbench.spec.ts`: `click/fill/drag/select=786`, `hardAssertions=1061`, `page.request/request=21`, `skip/fixme=0`, `waitForTimeout=0`, `page.goto('/p/')=0`, `retries=0`, `page.request.put=1` named-query-field setup fallback only. Existing 3 处 `toBeGreaterThan(0)` are data non-empty assertions, not threshold looseners.
- E2E truth audit completed:
  - No new `test.skip` / `test.fixme` / `retries` / `waitForTimeout`.
  - No new PUT-bypass helper (`request.put` / `page.request.put`) in the diff.
  - No new threshold assertion (`toBeLessThanOrEqual` / `toBeGreaterThanOrEqual`) in the diff.
  - `git diff --check` exits 0.
- OSS scope list audit completed:
  - `chromium --list` contains `1451` tests and no `pcba` / `quality-agent` / `pcba-solution` / `qc_` matches.
  - `chromium-deep --list` contains `192` tests and no `pcba` / `quality-agent` / `pcba-solution` / `qc_` matches.

## Fresh OSS Profile Gate Continuation (2026-05-21)

在把 Playwright profile 从旧 `chromium/chromium-deep` 扩展为文档化的 `oss/oss-deep/contract/enterprise-smoke/enterprise-full` 后，继续推进真正的 OSS Platform Gate。

### 已修复的 gate 阻塞点

| 阻塞点 | 根因 | 修复 | 验证 |
|--------|------|------|------|
| `announcement-lifecycle.spec.ts archive and delete announcement` | 测试最后一步查找英文 `delete`，但真实产品行操作标签是中文 `删除` | 将该步骤改为 `clickRowAction(page, TITLE, '删除')` | 整文件 targeted rerun `26 passed / 1 skipped`；后续 full run 已越过 announcement |
| `aurabot/pcba-*` specs 进入 OSS gate | 这些 specs 放在 `tests/e2e/aurabot`，但依赖企业 PCBA/quality/product-catalog 插件；原 scope 只排除了企业目录 | 新增 `enterpriseDistributionAuxSpecPattern = /\/tests\/e2e\/aurabot\/pcba-.*\.spec\.ts$/`，OSS 排除，enterprise gate 纳入 | `PW_PROFILE=oss --list` 为 `1776` tests / `247` files，grep 无 `aurabot/pcba`、`pcba-solution`、`tests/e2e/pcba/` |
| `crm-batch-ops.spec.ts BATCH-002` | CRM lead list 页面未开启 table 多选，测试和批量操作 UX 需要表头 select-all | `crm_lead_table.table.selection.mode = multiple`，并重新导入 `crm-starter` 页面资源 | CRM targeted rerun `27 passed / 1 skipped` |
| `crm-calendar-sync.spec.ts cal-01` | `/crm/settings/calendar-sync` 菜单路径没有注册静态 route，落入 catch-all 后提示菜单“日历集成”没有 PageSchema 配置 | 在 core route manifest 注册 CRM 静态页面：merge queue、inbound channels、web forms、web-form editor、calendar-sync | `pnpm typecheck` 生成 route types；重启 5237/3564 后 CRM targeted rerun `27 passed / 1 skipped` |

### 当前有效验证证据

```bash
cd web-admin
pnpm typecheck
NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=oss PW_WORKERS=1 pnpm exec playwright test --project=oss tests/e2e/announcement/announcement-lifecycle.spec.ts --reporter=line
NO_PROXY=localhost,127.0.0.1 PLAYWRIGHT_BASE_URL=http://localhost:5237 PW_PROFILE=oss PW_WORKERS=1 pnpm exec playwright test --project=oss tests/e2e/crm/crm-batch-ops.spec.ts tests/e2e/crm/crm-calendar-sync.spec.ts --reporter=line
```

结果：

- `pnpm typecheck` passed。
- Announcement targeted：`26 passed / 1 skipped`。
- CRM targeted：`27 passed / 1 skipped`。

注意：calendar-sync 修复后，旧 dev server 仍走 stale route manifest，失败页为 `Page Unavailable`。使用如下命令重启 worktree 服务后 targeted 通过：

```bash
VITE_PORT=5237 BFF_PORT=3564 BFF_ALLOWED_PORTS=5237 SPRING_BOOT_URL=http://127.0.0.1:6443 pnpm dev:full
```

### 当前结论

- Unified Designer feature slice 已有独立 targeted、unit、typecheck 与 E2E truth 证据。
- Fresh `PW_PROFILE=oss PW_ROLE_PROJECTS=1` full gate 已逐步清掉 announcement、PCBA scope、CRM batch/calendar-sync 阻塞。
- 仍未完成一轮全量 OSS profile 0 fail，因此不能声明 OSS Platform Gate 完整通过。

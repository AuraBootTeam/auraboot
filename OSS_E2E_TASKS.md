# OSS Docker System Test Tasks

## Environment Status

- Worktree: `/Users/ghj/work/auraboot/.worktrees/system-test-docker-fixes/auraboot`
- Branch: `codex/system-test-docker-fixes-20260520`
- Docker stack: `COMPOSE_PROJECT_NAME=auraboot-ga-e2e`
- Ports: backend `6444`, frontend `5174`, BFF `3501`, Postgres `5433`, Redis container-only
- Excluded scope: agent and page-designer specs

## Latest Valid Full Run

- Final filtered Docker system smoke, excluding agent / ACP / aurabot / page-designer specs:
  - Log: `/tmp/pw-docker-system-smoke-enterprise-r10-20260520.log`
  - Command scope: `638` Playwright tests from `/tmp/e2e-system-filtered.txt`
  - Result: `566 passed`, `72 skipped`, `0 failed`
  - Runtime: `17.5m`
  - Final backend health check: `{"status":"UP"}`

## Previous Full Runs

- `/tmp/pw-docker-system-smoke-enterprise-r2-20260520.log`: valid baseline after enterprise plugin import; `379 passed`, `63 skipped`, `57 failed`, `96 did not run`.
- `/tmp/pw-docker-system-smoke-enterprise-r3-20260520.log`: valid after first fixes; `421 passed`, `62 skipped`, `38 failed`, `74 did not run`.
- `/tmp/pw-docker-system-smoke-enterprise-r4-20260520.log`: environment-invalid; backend became unhealthy due Hikari pool exhaustion.
- `/tmp/pw-docker-system-smoke-enterprise-r5-workers2-20260520.log`: valid with workers=2 after backend leak fix; `512 passed`, `62 skipped`, `21 failed`, `45 did not run`.
- `/tmp/pw-docker-system-smoke-enterprise-r6-workers2-20260520.log`: valid after broad fixes; `556 passed`, `72 skipped`, `2 failed`, `8 did not run`.
- `/tmp/pw-docker-system-smoke-enterprise-r7-workers2-20260520.log`: first valid green full run after broad fixes; `566 passed`, `72 skipped`, `0 failed`.
- `/tmp/pw-docker-system-smoke-enterprise-r8-json-20260520.log`: JSON audit run for skip extraction; `565 passed`, `72 skipped`, `1 failed`. Not a green baseline.
- `/tmp/pw-docker-system-smoke-enterprise-r9-20260520.log`: validation run after PA-006 fix; `562 passed`, `72 skipped`, `2 failed`, `2 did not run`. Not a green baseline.

## Latest Targeted Evidence

- `setup + auth`: `/tmp/pw-auth-docker-system-20260520.log` — 18 passed, 1 skipped.
- `showcase seed`: `/tmp/pw-seed-docker-system-20260520-r4.log` — completed 8 phases.
- `last r6 failures`: `/tmp/pw-docker-system-targeted-r6-fixes-20260520.log` — 34 passed.
- `report-template`: `/tmp/pw-docker-system-targeted-report-template-20260520.log` — 20 passed, 9 skipped.
- `remaining targeted`: `/tmp/pw-docker-system-targeted-remaining-b-20260520.log` — 27 passed, 2 skipped.
- `PA-006 regression`: `/tmp/pw-docker-system-targeted-pa006-20260520.log` — 20 passed.
- `platform-admin-crud smoke slice`: `/tmp/pw-docker-system-targeted-platform-admin-crud-20260520.log` — 31 passed.
- `ML-01 login hydration regression`: `/tmp/pw-docker-system-targeted-ml01-20260520.log` — 20 passed.
- `PCBA IA false-positive regression`: `/tmp/pw-docker-system-targeted-pcba-ia-030405-r2-20260520.log` — 22 passed.
- Frontend static check: `pnpm -C web-admin exec tsc --noEmit --pretty false` — passed.
- Backend static check: `cd platform && ./gradlew compileJava --no-daemon` — passed.

## Remaining Stable Failures

- None in the final filtered Docker system smoke run.

## Known Invalid Failures

- `/tmp/pw-seed-docker-system-20260520.log`: invalid dependency state in fresh worktree; host `node_modules` missing `@playwright/test`.
- `/tmp/pw-seed-docker-system-20260520-r2.log`: invalid ordering; auth storage did not exist before seed.
- `/tmp/pw-docker-system-smoke-20260520.log`: invalid launcher; macOS shell did not support `mapfile`, so the filtered spec list was not applied.
- `/tmp/pw-docker-system-smoke-20260520-r2.log`: environment-invalid; Docker stack was bootstrapped with OSS `e2e` plugin profile while the filtered system suite includes enterprise modules such as annual-plan, project-management, asset-management, contract-cost, construction-process, and enterprise CRM commands.
- `/tmp/pw-docker-system-smoke-enterprise-r4-20260520.log`: environment-invalid; backend Hikari pool exhaustion traced to unreleased connections in prepared-plan cleanup.

## Fixed Issues

- `seed-showcase-data.spec.ts` and `seed-showcase-extended.spec.ts` passed datetime values to `crm_opp_expected_close_date`, but the CRM plugin field is `date` and the command runtime expects `yyyy-MM-dd`.
- Docker E2E scripts now support enterprise bootstrap/import knobs and optional enterprise plugin jar build (`PLUGIN_IMPORT_EDITION`, `ENTERPRISE_PLUGIN_ROOT`, `GA_E2E_BUILD_ENTERPRISE_PLUGIN_JARS`).
- `SchemaManagementServiceImpl.clearPostgresPreparedPlans()` released pooled connections correctly after clearing PostgreSQL prepared plans, fixing the Hikari exhaustion seen in r4.
- Default currency conversion now falls back to configured currency/exchange-rate tables when no enterprise `CurrencyConversionSpi` bean is available in Docker, fixing CRM opportunity multi-currency base amount assertions.
- CRM lead list selection config and runtime selection detection now support top-level `selection`, fixing batch selection in DSL list pages.
- Several plugin menu routes were aligned to canonical dashboard routes, including CRM, procurement, project-management, and PCBA manufacturing dashboards.
- Platform-admin scheduled task row delete action was corrected from state transition to command and re-imported into Docker.
- Test data and assertions were tightened for scheduler cron format, finance account code lengths/fiscal periods, report-template optional API availability, saved-view save responses, smart display detail fallback, PCBA/procurement/inventory command prefixes, and global search/PCBA navigation stability.
- `platform-admin-crud.spec.ts` form readiness now scopes to the main form content and PA-006 waits for `domain_code` / `domain_name`, fixing the r8 skeleton-form race where a global page input made the test proceed too early.
- `LoginPage.waitForFormReady()` now waits for login page hydration before filling controlled inputs, fixing the r9 fresh-context login race in ML-01.
- `pcba-navigation-ia.spec.ts` now checks error shells only inside explicit error containers, fixing the r9 false positive where business data containing `404` was treated as a 404 page.

## Skip Audit And Gate

- Audit file: `SMOKE_SKIP_AUDIT.md`
- Source JSON: `/tmp/pw-docker-system-smoke-enterprise-r10-json-20260520.json`
- Current categorized skipped count: `72`
- Category counts: `optional-plugin/profile=42`, `seed/config-gap=21`, `permission-gap=5`, `env-toggle=4`
- Smoke CI gate:
  - `failed = 0` is required.
  - `unexpected skipped = 0` is required.
  - Known skips are allowed only when recorded in `SMOKE_SKIP_AUDIT.md` with reason, category, owner, and action.
  - `permission-gap` and `seed/config-gap` should be reduced first; `optional-plugin/profile` cases should move to plugin/profile suites or get profile fixtures.

## Truth Check

- Excluded by user request: agent / ACP / aurabot / page-designer spec files. Filter lists:
  - Included: `/tmp/e2e-system-filtered.txt` (`433` spec files)
  - Excluded: `/tmp/e2e-system-excluded.txt` (`36` spec files)
- Final status wording must include skips: final run is `566 passed / 72 skipped / 0 failed`, not "all tests in repository passed".
- Skips observed are part of the filtered suite outcome. They are known-skipped candidates after audit, not failures and not excluded-directory tests counted by mistake.
- `/e2e-truth` diff audit:
  - Added `test.skip` / `test.fixme` entries are known-skipped optional/profile or dependency cases recorded in `SMOKE_SKIP_AUDIT.md`; no unexpected skip remains.
  - No added `retries:`, `waitForTimeout`, `request.put` / `page.request.put`, `toBeLessThanOrEqual`, or `toBeGreaterThanOrEqual` in the E2E diff.
  - Added `page.request` calls are GET-only metadata/data-source probes, not PUT/API write bypasses for UI flows.
  - No added direct `await page.goto('/p/...')` red-line hit in the E2E diff.
  - r10 skipped set exactly matches r8 audit extraction: `72` vs `72`.

## Next Action

- Review and commit the worktree changes from `codex/system-test-docker-fixes-20260520`, then merge via the normal worktree flow after code review.

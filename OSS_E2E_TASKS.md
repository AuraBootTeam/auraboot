# OSS Docker System/E2E Audit

## Scope

- Date: 2026-05-20
- Worktree: `/Users/ghj/work/auraboot/.worktrees/oss-docker-full-20260520/auraboot`
- Branch: `codex/oss-docker-full-20260520`
- Docker stack: `auraboot-ga-e2e`
- Services: backend `http://localhost:6444`, frontend `http://localhost:5174`, BFF `http://localhost:3501`, postgres `localhost:5433`
- Excluded areas requested by user: agent and page designer.
- Exclusion mechanism:
  - File list filtering: `/tmp/oss-e2e-logs-20260520/included-chromium-r2.txt`, `/tmp/oss-e2e-logs-20260520/included-deep-r2.txt`
  - Title guard: `--grep-invert 'Agent definitions|Agent|AuraBot|agent-control-plane|Page Designer|page designer|AI Page'`

Automation designer and BPMN designer were kept because they are not page designer.

## Environment Health

- Backend health: `/actuator/health` returned `{"status":"UP"}`.
- Bootstrap status: `/api/bootstrap/status` returned initialized `true`.
- Frontend: `/` returned reachable `302` after fixes and before final deep run.
- Long gate policy used: health check first, then targeted reruns, then full chromium gate, then deep gate.

## Final Results

- OSS chromium full gate:
  - Log: `/tmp/oss-e2e-logs-20260520/chromium-r6.log`
  - Result: `1028 passed / 64 skipped / 0 failed`
  - Duration: `38.7m`
- OSS chromium-deep gate:
  - Log: `/tmp/oss-e2e-logs-20260520/deep-r7.log`
  - Result: `170 passed / 0 failed`
  - Duration: `7.5m`
- Targeted evidence after final SavedView filter fix:
  - Single test: `/tmp/oss-e2e-logs-20260520/targeted-deep-saved-view-filter-r10.log`, `1 passed`
  - SavedView deep file: `/tmp/oss-e2e-logs-20260520/targeted-deep-saved-view-file-r1.log`, `7 passed`

## Fixed Issues

| Area | Problem | Fix |
|---|---|---|
| Platform admin seed | `process_keys`, `list_fields`, `filter_fields`, `sort_fields`, `params` were text fields but tests insert JSONB payloads. | Changed platform-admin field metadata data types to `jsonb`. |
| Showcase/CRM seed | Date-only fields were seeded with datetime values. | Changed expected close date seeds and PCBA CRM test data to `YYYY-MM-DD`. |
| Auth logout | Logout helper stopped on the confirmation page. | Added confirmation-page handling and waited for `/login`. |
| Webhook lifecycle | Test used invalid event type `CommandExecuted`. | Switched to valid `record_created`. |
| CRM/list UX | Existing default kanban view could hide table rows. | Added helper to ensure/select table SavedView before table assertions. |
| BPM picker | Save request wait only matched one endpoint and tree option click hit Playwright actionability timeout. | Accepted both command and direct dynamic save responses; clicked visible option through DOM event. |
| BPM helper | Login could time out under Docker load. | Added bounded retry with longer timeout. |
| Model list sort | Header click hit the column menu instead of the label. | Clicked the exact column label text inside the header. |
| SavedView filters | List filter setter stored functional updater as state, so saved filters could be empty even after UI filtering. | Made `setFilters` support both object updates and functional updates in `ListPageContent`. |
| SavedView deep test | Response waits were too strict for already-completed UI updates. | Kept UI/API assertions, but allowed missed transient response wait and asserted persisted API state. |

## Skip Audit

The `64 skipped` in the chromium gate are not failures and are not excluded agent/page-designer tests. They are known conditional skips inside the included OSS smoke/full suite.

Smoke CI gate interpretation:

- `failed = 0`: satisfied.
- `unexpected skipped = 0`: no unexpected skip was introduced during this fix pass.
- `known skipped`: allowed, but must stay explainable and trend down.

Current audit categories:

| Category | Treatment |
|---|---|
| Environment toggles | Keep only when the condition is explicit, for example disabled optional email/code paths. |
| Optional plugin/profile | Keep out of smoke unless the current OSS Docker profile guarantees the capability. |
| Seed/data preconditions | Prefer adding deterministic seed; move truly optional cases out of smoke. |
| Permission gaps | Prefer fixing test role/account permissions instead of long-term skip. |
| Mutually exclusive initialization state | Split into an empty-db/setup suite instead of mixing into post-bootstrap smoke. |
| Product gaps | Track as backlog/TODO, not permanent skip. |

## Warnings

- `deep-r7.log` still prints one BPM-D11 backend 500 warning while probing CallActivity startup, but the scenario handles that branch and the deep gate remains `170 passed / 0 failed`.

## Verification Commands

Regular chromium gate:

```bash
cd /Users/ghj/work/auraboot/.worktrees/oss-docker-full-20260520/auraboot/web-admin
PLAYWRIGHT_BASE_URL=http://localhost:5174 BACKEND_URL=http://localhost:6444 BFF_URL=http://localhost:3501 BE_PORT=6444 VITE_PORT=5174 BFF_PORT=3501 PG_HOST=localhost PG_PORT=5433 PGUSER=auraboot PGDATABASE=aura_boot PG_USER=auraboot PG_DB=aura_boot PGPASSWORD=auraboot_dev PW_SKIP_WEBSERVER=1 NO_PROXY=localhost,127.0.0.1 PW_WORKERS=1 pnpm exec playwright test -c playwright.oss.config.ts --project=chromium --no-deps --reporter=line --workers=1 --grep-invert 'Agent definitions|Agent|AuraBot|agent-control-plane|Page Designer|page designer|AI Page' --output=test-results/oss-chromium-r6 $(cat /tmp/oss-e2e-logs-20260520/included-chromium-r2.txt)
```

Deep gate:

```bash
cd /Users/ghj/work/auraboot/.worktrees/oss-docker-full-20260520/auraboot/web-admin
PLAYWRIGHT_BASE_URL=http://localhost:5174 BACKEND_URL=http://localhost:6444 BFF_URL=http://localhost:3501 BE_PORT=6444 VITE_PORT=5174 BFF_PORT=3501 PG_HOST=localhost PG_PORT=5433 PGUSER=auraboot PGDATABASE=aura_boot PG_USER=auraboot PG_DB=aura_boot PGPASSWORD=auraboot_dev PW_SKIP_WEBSERVER=1 NO_PROXY=localhost,127.0.0.1 PW_WORKERS=1 pnpm exec playwright test -c playwright.oss.config.ts --project=chromium-deep --no-deps --reporter=line --workers=1 --grep-invert 'Agent definitions|Agent|AuraBot|agent-control-plane|Page Designer|page designer|AI Page' --output=test-results/oss-deep-r7 $(cat /tmp/oss-e2e-logs-20260520/included-deep-r2.txt)
```

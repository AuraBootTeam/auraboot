# DecisionOps console — real-chrome visual golden harness

Standalone vite + Playwright harness that renders the real `DecisionOpsConsole` (F1–F8) component
tree in a real Chromium with mock data — a real-browser visual golden, strictly stronger than the
jsdom RTL tests (catches real-browser runtime errors / rendering that jsdom can't).

Why standalone: the full app-shell golden needs a running backend + auth + the chrome-devtools MCP
profile (contended). This harness needs neither — it mounts the console directly with a mock API
client + sample data.

## Run
    node_modules/.bin/vite --config golden-harness/vite.config.ts   # serves :5199
    node golden-harness/golden.mjs                                  # Playwright: screenshots + asserts

## Last result (2026-06-08)
- 7/7 tabs render (dashboard/definitions/designer/logs/model/permissions/connectors)
- Definitions self-fetch (react-query) renders rows; Dashboard derived match-rate = 75%
- **0 console errors** in real Chromium
- screenshots → /tmp/drt-golden/*.png

## Scope
Component-level real-chrome golden with mock data + no app global CSS. The deeper full-app golden
(real backend data + app styles + auth + routing at /decision-ops, which is build-verified into the
production bundle) is the documented follow-on needing a dedicated full stack.

## Full-app golden (real backend + auth + app CSS) — DONE 2026-06-08
`golden-harness/fullapp-golden.mjs` drives the **real** web-admin app against a **real** backend:
seals a `__session` cookie from a `/api/test/seed` JWT (same scheme as `tests/auth.setup.ts`,
default dev secret) → navigates `/decision-ops` in real Chromium.

Result: authenticated (no login redirect), `decisionops-console` mounts in the real app shell,
**7/7 tabs**, Definitions tab shows **real backend decisions** (order_routing / sla_deadline) via
chrome→vite→BFF→backend→DB, **0 console errors**. Caught + fixed a real bug: `decisionApi.ts`
double-prefixed `/api` (ApiService baseURL is `/api`) → 404.

Bringup (see also `aura-decision/docs/backlog/2026-06-08-decision-runtime-remaining-env-blocked.md`):
`./dev.sh runtime allocate auraboot drt-golden --slot 8` + `infra ensure` → apply schema.sql to the
runtime DB → `dev.sh run drt-golden -- AURA_ENV=test ./gradlew bootRun` → `pnpm dev:full` with
`SPRING_BOOT_URL=<backend>` → `POST /api/test/seed` to /tmp/drt-golden-jwt.txt → `node golden-harness/fullapp-golden.mjs`.

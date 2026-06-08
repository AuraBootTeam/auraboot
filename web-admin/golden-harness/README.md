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

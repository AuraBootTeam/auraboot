# OSS Web E2E — Operator Notes

Operational tips and current debt that the smoke-running playbook
cannot infer from the suite alone. Spec authoring conventions live in
the `/e2e` skill (and the AGENTS.md "E2E 测试 (Web)" red lines); this
file is for the kind of context that is true *now* but may not be true
later, so it lives next to the code rather than in long-form docs.

## Running

```bash
cd auraboot/web-admin
pnpm test                 # PW_PROFILE=fast — recommended local default
pnpm test:smoke           # smoke set (BACKLOG-CI-001 — same set as CI)
pnpm test:full            # full regression (slow; nightly / pre-release)
```

The runner expects a host BFF on `localhost:3500` proxying to a
backend on `localhost:6443`. Alternatively use the **GA-E2E docker
stack** to run a fully-isolated backend + postgres + vite + BFF on
non-default ports without touching your dev environment:

```bash
./scripts/docker-ga-e2e-up.sh           # boot stack on :5174 / :3501 / :6444 / :5433
./scripts/docker-ga-e2e-bootstrap.sh    # import OSS plugins + provision e2e users

cd web-admin
PLAYWRIGHT_BASE_URL=http://localhost:5174 PW_SKIP_WEBSERVER=1 \
  npx playwright test ...
```

Operator details, the five first-boot traps (wrapper jar, frontend
Dockerfile pnpm/npm mismatch, _public-routes stub, pnpm-lock cwd,
SSR BFF_INTERNAL_URL), and the diagnostic table live in
[`docs/operations/ga-e2e-docker-stack.md`](../../../docs/operations/ga-e2e-docker-stack.md).

Always tee the run log:

```bash
LOG=/tmp/pw-$(date +%Y%m%d-%H%M%S).log
npx playwright test ... 2>&1 | tee "$LOG"
```

A bare `... | tail` or `... | grep "passed"` drops everything in the
middle of the run — see the AGENTS.md tooling red lines.

## Known flaky workarounds — BACKLOG-FLAKY-001

These three specs use `expect.poll` to absorb a small, repeatable
non-determinism that has been stable to 10/10 across recent regression
runs. The polling buys reliability today but masks a real platform
gap (no stable test-id at the relevant DOM node) — the long-term fix
is to add `data-testid` at the framework layer rather than retrying.

| Spec | Approx. line | Symptom | Workaround | Long-term fix |
|------|--------------|---------|------------|---------------|
| `command-palette/global-search.spec.ts` | search-result render | Cmd+K opens, results animate in over ~120ms; click-before-render misses the row | `expect.poll` for the `[role="option"]` count | Stable testid on the result list root; `aria-busy` toggles during hydration |
| `showcase/validation-e2e.spec.ts` | D8.1 required validation | Inline error swap (parent → child input) races re-render | `expect.poll` on the error-message text | Move error binding from imperative DOM update to a controlled state slice with a stable testid |
| `showcase/all-fields/date-picker.spec.ts` | D4.3 date picker primitive | Calendar popover mounts after the input loses focus, so the next click occasionally hits the input again | `expect.poll` for the popover root | Replace the primitive with the framework `DatePicker` widget once it ships |

These are low-priority debt items — re-evaluate when (or if) any of
them starts failing intermittently. Removing the polls before adding
the long-term fix will reintroduce the original flake.

## Deferred items

### BACKLOG-SMOKE-001 — Sidebar-nav variant of the smoke spec (Deferred)

Today the smoke set uses `page.goto('/p/{model}')` direct-navigation
in eight places (audit-trailed in `/e2e-truth` per file-name
exception). A sidebar-nav variant would catch menu-registration
regressions that direct-nav silently passes through.

Decision (2026-04-25): defer until the GA-E2E CI workflow
(BACKLOG-CI-001) is operational and we have data on smoke run time.
Adding a parallel sidebar-nav suite roughly doubles smoke wall-clock
time; we do not yet know whether the headroom exists. Re-evaluate
after the first month of smoke CI data.

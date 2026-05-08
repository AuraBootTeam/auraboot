# r2 Full Suite — 170 Fail Categorization

**Date:** 2026-05-08
**Branch:** `fix/oss-suite-r2`
**Run log:** `/tmp/r2-full3-*.log` (16.4 m, 4 workers, 2c/2g frontend)
**Tally:** 1664 tests → 1123 passed / 170 failed / 82 skipped / 289 did-not-run

## Why this list exists

When the r2 isolated docker stack was first run end-to-end on 2026-05-08
it surfaced 221 failures vs the host's earlier 128. Triage showed the
gap was a mix of (a) a frontend container choking under 4-worker
concurrency (1 CPU / 1 GB) and (b) a class of cross-DB false-positives
that host-mode runs were silently masking — `psql` calls in test
helpers were hard-coded to `localhost:5432` so seeds wrote to one DB
while the backend read from another.

After both fixes (compose limits → 2c/2g, helpers env-aware via
`tests/helpers/pg-env.ts`):

- `commit 7b6e94dd`: full-suite drops to 170 fail / 16.4 m
- The 170 are **honest failures the host runs were hiding**

## Per-cluster table

| # | Cluster | Count | Error signature | Likely root | Disposition |
|---|---|---|---|---|---|
| C1 | `saved-view/*` | 40 | `expect(received).toBeTruthy()` (assertion, not timeout) | Conditional Format / Form View / Formula functions / Kanban grouping / Lookup field — features the spec asserts on may not be implemented yet (or partially) | product-gap audit |
| C2 | `showcase/*` | 39 | `page.waitForResponse Timeout 10000ms` | Resource / timing — even at 2c/2g frontend, deep showcase specs (page-creation-dispatch, runtime-rendering, widgets-misc) still sometimes hit waitForResponse 10s walls | timeout audit + maybe single-worker for these specs |
| C3 | `bpm-designer/*` | 20 | `GET /api/bpm/process-definitions/{pid} → 401` | Auth/tenant scope — JWT is fine for most endpoints but BPM seems to require a tenant scope context that the spec setup doesn't establish | scope: read AdminRoleInterceptor + BPM controller, fix tenant context |
| C4 | `notification/*` | 10 | `expect(...).toBe(...)` (assertion) | Product gap — notification feature spec expects values not produced | product audit |
| C5 | `aurabot/*` | 9 | mixed | Some need LLM key (e.g. ai-result-contract), some are state-after-action assertions | classify per spec |
| C6 | `workflow-demo/*` | 8 | `domain role "wd_manager" not found` | Missing seed — workflow-demo plugin needs `wd_manager` role provisioned, not done by current setup | extend setup spec or workflow-demo plugin to self-seed |
| C7 | `bpm/*` | 6 | mixed | Likely overlaps C3 (tenant context); some BPMN designer interaction flakes | bundle with C3 |
| C8 | `organization/*` | 4 | mixed | Team management / employee creation under r2 fresh DB | likely seed gap |
| C9 | `model/*` | 4 | mixed | Model CRUD on r2 — could be permission or seed | sample needed |
| C10 | `auth/*` | 4 | mixed | LN-002 / OTP-003 / LO-001 etc. — same as host residual | already in earlier flake backlog |
| C11 | `admin/*` | 4 | mixed | dict-management / env-layering / etc. — partial overlap with earlier 11-fail residual | dedupe with earlier backlog |
| C12 | `permission/*` | 3 | mixed | Permission depth tests — likely missing role bindings | sample needed |
| C13 | `command/*` | 3 | mixed | Command execution tests | sample needed |
| C14 | `query-builder/*` | 2 | known QB-07/QB-08 from smoke residual | already in flake backlog | dedupe |
| C15 | `agent-control-plane/*` | 2 | mixed | ACP smoke interaction | sample |
| C16 | misc | 5 | mixed | plugin-lifecycle / platform / header / dashboard / cross-field-validation — one each | individual triage |

## Recommended attack order (next session)

1. **C1 (saved-view, 40)** — biggest cluster, all assertion errors → grep
   for whether the asserted features (CF rules / FV metadata / FF
   functions count) actually have backend endpoints. If they don't,
   these specs document a roadmap, not bugs to fix today; classify as
   "feature-not-shipped" and exclude from OSS scope OR file P2 issues.

2. **C3 (bpm-designer 401, 20)** — single error pattern, likely one
   tenant-context fix unblocks all 20. Read `BpmProcessController` +
   how spec sets cookie/JWT.

3. **C6 (workflow-demo, 8)** — single seed gap (`wd_manager` role).
   Add to setup spec or workflow-demo plugin's bootstrap. Probably a
   30-min fix.

4. **C2 (showcase 39 timeouts)** — likely just need `--workers=2` for
   showcase project OR per-spec timeout bump. Cheap fix.

5. **C4 (notification, 10)** — assertion-style gaps, similar shape to
   saved-view. Audit endpoints.

6. **C10/C11/C14 dedupe** — these overlap with the earlier
   `2026-05-08-oss-suite-contention-flakes.md` backlog. Merge entries.

## Out of scope for "fix in this branch"

- **C1 saved-view product gaps**: if these are real unbuilt features
  (CF rules etc.), they're roadmap items not regressions. Removing
  them from OSS scope is a config change; building them is a feature
  decision.

- **C5 aurabot LLM-required**: needs ANTHROPIC_API_KEY in `r2-stack`
  env. Optional in dev; tag specs with `@requires-llm` and skip when
  key absent.

## What we've already proven

- Smoke (172 tests) reaches host parity at 166/11/5
- Targeted ai-memory-promotion-real: 12/12 pass after psql env fix
- Targeted aurabot/* dir: 81/9/2 (psql fix unblocked the menu seeds)
- Full suite reduces from 221 → 170 with the frontend bump

The remaining 170 is the honest baseline — we can build down from here
with categorized clusters, knowing the env infrastructure is sound.

# r2 Full Suite — Fail Categorization (refreshed)

**Date:** 2026-05-08 (refreshed after 85b43994 BACKEND_URL auto-derive)
**Branch:** `fix/oss-suite-r2`
**Latest run log:** `/tmp/r2-full4-*.log` (15.2 m, 4 workers, 2c/2g frontend)
**Latest tally:** 1664 tests → **1250 passed / 69 failed** / 82 skipped / 263 did-not-run

**Earlier runs preserved for trend visibility:**
- `/tmp/r2-full3-*.log` 16.4 m → 1123 passed / 170 failed (post 2c/2g frontend)
- `/tmp/r2-full2-*.log` 25.3 m → 1005 passed / 221 failed (post psql fix, 1c/1g)
- `/tmp/r2-full-*.log` 19.8 m → 1154 passed / 128 failed (host-DB false-positives masking)

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

## Per-cluster table (current state — 69 fail)

| # | Cluster | Latest | Earlier | Status | Disposition |
|---|---|---|---|---|---|
| C1 | `saved-view/*` | **4** | 40 | 🟢 36 fixed by 2c/2g frontend bump (timeouts) — residual 4 are likely product gap | P1 audit (CF/FV/FF/KG/LF backend endpoints) |
| C2 | `showcase/*` | **1** | 39 | 🟢 38 fixed by 2c/2g frontend bump | P3 (1 residual is timing flake) |
| C3 | `bpm-designer/*` | **0** | 20 | ✅ done — `85b43994` BACKEND_URL auto-derive | — |
| C4 | `notification/*` | **10** | 10 | unchanged — assertion errors, real product gap | **P0-2 next** |
| C5 | `aurabot/*` | **9** | 9 | unchanged — partial LLM key requirement | **P0-3 next** |
| C6 | `workflow-demo/*` | **2** | 8 | 🟢 6 fixed by `85b43994` (`wd-fixtures.ts` was in the 11) | P3 (2 residual flakes) |
| C7 | `bpm/*` | **3** | 6 | 🟢 partial fixed by C3 sibling | P3 |
| C8 | `organization/*` | **5** | 4 | similar (slight increase due to honest baseline) | P2 audit |
| C9 | `model/*` | **3** | 4 | 1 fixed | P3 |
| C10 | `auth/*` | **6** | 4 | similar | dedupe with smoke flake backlog |
| C11 | `admin/*` | **4** | 4 | unchanged — overlaps with 11-fail smoke residual | dedupe |
| C12 | `permission/*` | **3** | 3 | unchanged | P3 |
| C13 | `command/*` | **3** | 3 | unchanged | P3 |
| C14 | `query-builder/*` | **2** | 2 | unchanged | dedupe |
| C15 | `agent-control-plane/*` | **1** | 2 | 1 fixed | P3 |
| C16 | misc | **13** | 5 | (cross-field-validation 6 + dashboard / inbox / search / named-query / platform / automation 2 / workflow-demo 2 fragments) — earlier triage missed some | **P0-? scan** |

**Net improvement: 170 → 69 (-101 fails) via 2 fixes (`7b6e94dd` 2c/2g frontend, `85b43994` BACKEND_URL).**

## Recommended attack order (refreshed)

After 85b43994 the picture changed — biggest residual clusters are now
notification (10) and aurabot (9), not saved-view. New plan:

1. **C4 (notification, 10)** — assertion errors, product gap. Sample 1
   spec, look at backend; classify shipped vs not-shipped.

2. **C5 (aurabot, 9)** — mixed; some likely need ANTHROPIC_API_KEY.
   Tag `@requires-llm-runtime`, exclude from r2 default scope when key
   absent.

3. **C1 (saved-view residual, 4)** — likely product gap (CF rules / FV
   metadata / FF functions / KG grouping / LF lookup). grep backend
   endpoints; add to oss-scope.json#test_excludes if not shipped.

4. **C16 misc (13)** — covers cross-field-validation (6 cases),
   dashboard / inbox / search / named-query / platform / automation (2)
   / workflow-demo (2 residual). Mostly individual triage.

5. **C8 organization (5)** — likely seed gap or i18n; sample.

6. **C10/C11/C14 dedupe** — overlap with earlier
   `2026-05-08-oss-suite-contention-flakes.md` backlog. Merge entries
   so we don't double-track.

## Saved-view residual 4 — concrete signatures (post-r2 audit)

After C1 was reduced from 40 → 4 by `7b6e94dd` (frontend bump), the
remaining 4 are concrete product/spec drifts, not environment issues:

| Spec | Signature | Likely root |
|------|-----------|-------------|
| `saved-view-gantt.spec.ts:117` SV-040 | `dialog.getByText('E2E Gantt Timeline')` 5s timeout | Required saved-view fixture "E2E Gantt Timeline" not seeded; either add to setup spec or rewrite test to create it inline |
| `saved-view-gantt.spec.ts:135` SV-041 | (depends on SV-040 → cascade-fail) | Same root as SV-040 |
| `saved-view-quick-filters.spec.ts:25` QF-002 | `[data-testid=quick-filter-my_records]` lacks "My Records" label | i18n/label drift OR feature partially shipped |
| `saved-view-row-height.spec.ts:68` RH-002 | row height 57 px exceeds 50 px threshold | CSS/theme drift; spec threshold may be stale (theme bumped padding) |

Disposition: each has a contained fix (~30 min) but no shared root.
Tackle individually in a follow-up session OR add to oss-scope.json
test_excludes if classified as feature-not-yet-shipped.

## Out of scope for "fix in this branch"

- **Saved-view residual 4**: see table above — individual fixes,
  unrelated to the env/infrastructure work this branch focused on.

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

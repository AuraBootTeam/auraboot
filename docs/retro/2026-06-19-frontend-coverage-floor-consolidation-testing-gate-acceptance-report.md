---
type: retro
status: active
created: 2026-06-19
---

# Frontend (web-admin) vitest coverage — floor consolidation — testing-gate acceptance report

Wave 4 of the OSS coverage-to-80 initiative, frontend slice. Tracker:
`docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md` (frontend rows + §7 note).

## allowed_claim

`targeted pass` + measured coverage. The vitest **ratchet floors lagged the actual coverage
by ~5pt** (later rounds raised coverage but left the floors at the round-2 values). A fresh
full `pnpm test:unit:coverage` run measured **LINE 30.28% / Stmts 29.73% / Funcs 27.47% /
Branches 24.64%** (423 spec files, 4354 tests, all green). The floors are raised to
**28/27/25/22** to lock in the achieved coverage (~2–3pt flaky margin); the full run **passes**
at the new floors. Added 12 unit tests extending `ActionRegistry` handler coverage
(navigate / new / router.push / search / reset / setState + missing-context error branches +
registry API), lifting `ActionRegistry.ts` **18.6% → 26.6%**.

This is a **consolidation + small legit logic ratchet**, not a push past the vitest ceiling:
per the tracker §7 (owner task #14), the vitest logic layer (services/hooks/engines/utils) is
near-exhausted (~5 `.ts` modules with meaningful uncovered lines remain), and the remaining
~70% of `web-admin` lines are React presentation — covered by Playwright E2E, **not** vitest
(unit-testing them = mock-heavy brittle anti-pattern, AGENTS §2.2/§10). Reaching 80% line needs
the `coverage:e2e` harness merged with vitest (GA stack) or a redefined target — **owner
decision #14, not in scope here**.

## Claim level

`completion-claim` for the floor consolidation (measured + validated); `targeted pass` for the
ActionRegistry test additions.

## current_sot

- `docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md` (frontend baselines + §7 ceiling note + #14)
- `web-admin/vitest.config.ts` `coverage.thresholds`

## Test layer matrix

| Layer | Required? | Status |
|---|---|---|
| Frontend unit (vitest, logic) | yes (ActionRegistry handlers are dispatch logic) | DONE — 12 tests, side-effect assertions via mock `ActionContext`, no presentation |
| Coverage ratchet | yes (consolidation) | DONE — floors 25/24/22/19 → 28/27/25/22, full run passes |
| Web E2E | n/a here | owner task #14 (E2E coverage merge), needs GA stack |

## integration_coverage

vitest `coverage.thresholds`: lines 28 / statements 27 / functions 25 / branches 22 — measured
actual 30.28 / 29.73 / 27.47 / 24.64 (margins ~2.3 / 2.7 / 2.5 / 2.6 pt). Full
`pnpm test:unit:coverage` run is green at the new floors (no threshold violation).

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-06-19-frontend-coverage-floor-consolidation-testing-gate-acceptance-report.md
claim_level: completion-claim (floor consolidation) + targeted pass (ActionRegistry tests)
current_sot: tracker 2026-06-10 (frontend §7 + #14) + web-admin/vitest.config.ts
integration_tests: +12 ActionRegistry handler/registry unit tests (vitest, mock ActionContext, no UI)
integration_coverage: vitest floors 25/24/22/19 -> 28/27/25/22; measured 30.28/29.73/27.47/24.64; full run passes
e2e_specs: n/a (E2E coverage merge is owner task #14, GA stack)
feature_action_matrix: n/a (coverage consolidation)
browser_evidence: n/a
backend_evidence: n/a (frontend-only)
artifact_evidence: n/a
permission_negative: n/a
visual_feedback: n/a
skip_fixme_threshold_retry_audit: no skip/fixme/retry; thresholds raised (ratchet), not lowered
did_not_run: #14 E2E coverage merge (GA stack, owner decision)
remaining_blockers: none for this slice. Follow-up: #14 (E2E+vitest merge or target redefinition) — owner decision; ~5 .ts logic modules (ActionRegistry/BffProxyService/bpmWorkbenchService/useComputedFields/useDebugSession) for a future small ratchet
allowed_claim: targeted pass + floor consolidation — floors raised 25/24/22/19 -> 28/27/25/22, measured 30.28% lines, full run passes; ActionRegistry 18.6%->26.6%
```

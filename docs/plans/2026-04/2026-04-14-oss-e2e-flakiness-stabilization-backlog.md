# OSS E2E Flakiness Stabilization — Backlog

> Status: **backlog** (not scheduled)
> Logged: 2026-04-14
> Context: 2026-04-14 PM session verified `./scripts/oss-test.sh` baseline at ~92% pass (988–1003 passed, 13–18 failed per run) with different failure subsets across consecutive runs.

## Problem

The OSS Playwright suite has ~1% non-deterministic failure rate. Two consecutive full runs produce **overlapping but non-identical failure sets**:

- Run 1 (13 failures): admin-page-schema, admin-platform-admin-crud edit, webhook edit, auth login, automation toggle, bpm deploy, org team create, page-designer model query, showcase all-fields edit, showcase smart-input, search command-palette, studio dependsOn × 2
- Run 2 (18 failures): *different set* — picked up LM-007 remember-me, AUTO-04 toggle, AM-004 execution logs, NQ-E02 named query, ORG-DEPT-CRUD-03 delete dept, showLegend chart, but dropped studio dependsOn / search command-palette / page-designer query / admin-platform-admin-crud

This means single-test root-cause chasing has diminishing returns — fixing one flake often surfaces another. Stabilization requires a systemic approach, not whack-a-mole.

## Known Real Bugs (confirmed by diagnosis)

Root causes documented but not fixed in this session:

| Test | Root cause | Minimum fix |
|------|-----------|-------------|
| **LN-002 login** | React hydration race before `.fill()` | ✅ **FIXED** this session (commit `1885931`) — hydration probe in `loginViaUI` helper |
| PS-005 page-schema form label | `FormTemplate.ts:172` `f.label` undefined, no fallback | Add `f.label ?? f.displayName ?? resolveFieldLabel(f.field, model.modelCode)` |
| PA-007 domain config edit | Save response not validated; silent failure passes click but data doesn't persist | Assert `respBody.code === '0'` after `clickSaveAndWait` |
| SC-008 showcase edit | Same as PA-007 — missing response validation | Same fix pattern |
| WH-004 webhook edit | List not refreshed after save | `waitForResponse(/list/)` after save |
| TM-002 team create | Test selector `team-code-input` missing; fallback needed | Expand to `[data-testid="team-code-input"], [data-testid="form-field-code"], input[name="code"]` |

## Flaky / Environmental (not real bugs)

Tests that pass sometimes and fail sometimes under same code. Need stabilization, not per-test fix:

- `canvas-block-*` selector across 3 page-designer/studio tests — React state update race after quick-add click
- `address-province-sc_address` testid in showcase — testid may exist conditionally
- `command-palette` keyboard-shortcut test — CMD+K handler may not be wired or testid conditional
- Automation delete-confirm dialog (AUTO-05, AM-006) — dialog render race
- BPM PD-006 "deploy" action — may need plugin reimport for action to appear

## Proposed Stabilization Strategy (when picked up)

### Phase 1 — Measure (1 day)
- Run `./scripts/oss-test.sh` ≥ 5 times in a row
- Compute per-test flake rate (pass/fail across runs)
- Identify "stable pass", "stable fail", "flaky" buckets
- A test is **real bug** only if it's stable-fail across all 5 runs

### Phase 2 — Fix stable-fails (2-3 days)
- Each stable-fail test: root cause → minimum fix → rerun 3× to confirm stable-pass
- Reference diagnoses in this doc for the 5 candidate real bugs

### Phase 3 — Flake reduction (3-5 days)
- Audit flaky tests for common patterns: missing `waitFor` before assertion, DOM detach during click, React state batching
- Add shared `waitForHydrated(page)` helper to fixtures
- Forbid `networkidle` in OSS tests (SSE prevents settling)
- Forbid `page.waitForTimeout` without code comment explaining why

### Phase 4 — CI gate (1 day)
- Mark stable-pass subset as @critical with zero flake tolerance
- Run @critical on every PR
- Rest of suite weekly, with flake rate tracked in dashboard

## Exit Criteria (when picked up)
1. Stable-fail count = 0 across 5 consecutive runs
2. Flake rate ≤ 0.5% (≤5 flaky tests out of 1000)
3. `./scripts/oss-test.sh` documented as the canonical OSS CI gate

## Related Files

- `web-admin/tests/e2e/auth/auth-complete.spec.ts:60` — `loginViaUI` helper (just hardened)
- `web-admin/app/framework/meta/templates/generators/FormTemplate.ts:172` — PS-005 form label fallback location
- `web-admin/tests/e2e/platform/showcase-all-fields-lifecycle.spec.ts:860` — SC-008 edit validation
- `web-admin/tests/e2e/admin/platform-admin-crud.spec.ts` — PA-007 edit validation
- `web-admin/tests/e2e/admin/webhook-lifecycle.spec.ts` — WH-004 list refresh
- `web-admin/tests/e2e/organization/team-management.spec.ts:84` — TM-002 selector fallback
- `scripts/oss-test.sh` + `oss-scope.json` — the runner this backlog targets

# GAP-231 / GAP-232 Baseline Triage (2026-04-18)

Baseline run: `/tmp/baseline-1776516642.log` — partial (1400/3146 collected before stop).
Confirmation re-run after fix: `/tmp/retry-1776517145.log` — 35 specs, 29 passed, 1 failed (real bug).

## Root Cause Discovered

**The dominant cause of the "79 baseline failures" was NOT list-page-content selector
churn.** It was the `test-fixtures` plugin (providing `e2et_*` models / commands)
**not being imported** into the local environment.

`test-fixtures` is gated behind `AURA_ENV=test` or `IMPORT_TEST_FIXTURES=true`
in `oss-reset-and-init.sh`. Because the standard `oss-test.sh` runner did not
verify fixture availability, dozens of unrelated specs failed with
`Command not found: e2et:create_order` (and downstream variants), masquerading
as test bugs.

After importing `test-fixtures` once via
`POST /api/plugins/import/import-directory-sync`, 28/29 previously-failing
specs in the targeted re-run passed without any spec edits.

## Failure Categories (from partial baseline + targeted retry)

| Bucket | Count | Root cause | Action |
|---|---|---|---|
| `e2et:* command not found` | ~30 specs | `test-fixtures` plugin missing | Import plugin (preflight added) |
| `Field 'model_code' is required` | ~6 commands × N specs | Backend validation regression: SLA/webhook/bpm_domain/data_permission `create` commands flag `model_code` as required even though field bindings declare `required: false` | Backend bug — out of scope for test fixes |
| `aurabot-panel`/`aurabot-card` not visible | ~6 specs | UI component rendering issue (likely real regression in AuraBot panel) | Out of scope for test fixes |
| `enableMultiView` undefined after save (LUX-02) | 1 spec | Backend persistence bug for `extension.enableMultiView` toggle | Backend bug — out of scope |
| `quick-filters` testid not found | 1-2 specs | Real DOM drift / config issue (unblocked after fixtures import — needs verification) | Verify post-fixture |
| Misc (timeouts on bpm/designer specs) | ~10 | BPM-specific, not list-page-content related | Pre-existing GAP-x |

## Key Finding: `oss-test.sh` lacked preflight

Without a fixture probe, every developer running `./scripts/oss-test.sh` against
a freshly-`reset-and-init`-ed environment (without `IMPORT_TEST_FIXTURES=true`)
would observe ~30+ red specs that have nothing to do with their actual changes.
This noise was the source of the GAP-231 "79 baseline failures" framing.

## Specs Verified Green After Fixture Import

From `tests/e2e/saved-view/`, `tests/e2e/list-ux/`, `tests/e2e/platform/list-query-config.spec.ts`,
`tests/e2e/list-ux/ux-empty-states.spec.ts`:
- saved-view-table SV-001..SV-006 — passing
- saved-view-quick-filters QF-001..QF-006 — passing
- list-query-config (all 9 cases) — passing
- ux-empty-states UES-001..UES-005 — passing
- list-ux-enhancements LUX-01, LUX-03..LUX-06 — passing
- list-ux-enhancements LUX-02 — STILL FAILING (real backend bug, see above)

29/35 passed, 4 skipped (intentional), 1 failed (real bug), 1 did not run.

## Recommendations

1. **Land** preflight warning in `oss-test.sh` (this PR).
2. **Document** in `oss-reset-and-init.sh` README / CLAUDE.md that
   `IMPORT_TEST_FIXTURES=true` is required for full E2E pass.
3. **File backend bugs** for:
   - `model_code` validation regression (affects sla_config, webhook,
     bpm_domain_config, data_permission create commands)
   - `extension.enableMultiView` not persisted on Page Designer save
4. **GAP-232** (keyword search adaptations): no spec assertion drift was
   observed in the targeted run — search-related specs passed once fixtures
   were available. Defer further work pending evidence.

## Notes for follow-up

- BPM-related failures (`bpm-definition-lifecycle`, `bpm-publish-run-detail`,
  `picker-fill-roundtrip`, etc.) appear pre-existing and unrelated to GAP-231.
- ACP failures (`acp-*`) cluster on its own backend timing/data; out of scope.
- AuraBot panel rendering should be triaged separately — likely a frontend
  regression after `M5.10` (`app/components` → `app/ui`) refactor.

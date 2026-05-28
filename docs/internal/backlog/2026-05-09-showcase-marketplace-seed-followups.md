# 2026-05-09 Showcase + Marketplace seed-fix followups

Branch: `fix/2026-05-09-showcase-marketplace-seed` (auraboot OSS).

## Summary

Investigated the 19+19 fail clusters in `tests/e2e/showcase/` and
`tests/e2e/marketplace/` and split them into:

1. **Real seed-script bugs** (fixed in this branch, will manifest after
   next reset-and-init).
2. **UI ARIA gap** that broke marketplace cluster wholesale (fixed).
3. **Test-suite flakiness from cross-test interference** (no real bug,
   tests pass in isolation; out of scope here).
4. **Real product gaps** (logged below as P2 backlog items).

## Fixed in this branch

- `web-admin/app/plugins/core-platform/pages/plugins/index.tsx`
  - Tab buttons now expose proper ARIA semantics
    (`role="tablist"` / `role="tab"` / `aria-selected` / `aria-controls`).
    Previously `getByRole('tab', ...)` could not match the unified
    `/plugins` page tabs, causing 19 marketplace specs to fail in the
    `navigateToMarketplace()` helper.
- `platform/src/main/resources/seed/i18n-base.json`
  - `plugin.tab.discovery.en-US` "Discover" -> "Discovery"
    (matches spec regex `Discovery|发现`; helper navigation already
    works via zh-CN, but we want EN locale runs to pass too).
- `web-admin/tests/api/setup/seed-showcase-data.spec.ts`,
  `web-admin/tests/api/setup/seed-showcase-extended.spec.ts`
  - Renamed silently-failing BPM transitions:
    - `crm:propose_opportunity` -> `crm:advance_opp_to_proposal`
    - `crm:negotiate_opportunity` -> `crm:advance_opp_to_negotiation`
  - Real command codes from the published `crm_opportunity` model.
    The wrong names were swallowed by the seed's
    `.catch(() => undefined)` so opps stalled at `discovery` /
    `qualification` -> 6-stage distribution checks all failed.
- `web-admin/tests/api/setup/seed-showcase-data.spec.ts`
  - Added 3 more campaigns (planned / active / cancelled) to lift count
    from 3 -> 6 (threshold is 5) and added a `cancelled` transition
    branch via `crm:cancel_campaign`.

## Verification (host stack, not clean reset)

| Suite | Before | After (UI/i18n fix only) | Remaining |
|-------|--------|--------------------------|-----------|
| `tests/e2e/marketplace` | 19 fail / 7 pass | **2 fail / 24 pass** | both = `/api/marketplace/upgrades` 404 (product gap, see below) |
| `tests/e2e/showcase` | 22 fail / 110 pass | **10 fail / 122 pass** | 5 = seed-distribution (need fresh reset to verify seed-fix), 3 = list-ux flake (pass in isolation), 2 = product gap (see below) |

After the next full `reset-and-init.sh`, the 5 seed-distribution
failures (Entity counts; Opp 6-stage; B3+; A3 Arsenal; Seed Opp spread)
should all turn green because:
- Opp transitions now use real command codes -> 6 stages get populated.
- Campaign list will reach 6 records.
- (A3 / Arsenal already runs as part of `seed-showcase-arsenal.spec.ts`
  and was just blocked by the host stack not being re-seeded since the
  spec was added.)

## P2 product-gap backlog (do NOT pretend pass)

### MP-UPGRADE-API: `GET /api/marketplace/upgrades` is unimplemented

Two specs hit this:
- `marketplace-upgrade.spec.ts:40` "upgrade API returns valid response"
- `marketplace-upgrade.spec.ts:161` "upgrade API response structure is correct"

Currently returns `NoResourceFoundException` (HTTP 500). Needs:
- `MarketplaceBrowseController.getUpgrades()` returning
  `ApiResponse<List<MarketplaceUpgradeDTO>>` with shape `{ pluginId,
  installedVersion, latestVersion, ... }` for each installed plugin
  whose marketplace `latestVersion > installed`.
- Frontend `UpgradeBanner` already consumes this shape; only the
  server endpoint is missing.

### SHOWCASE-ACCOUNT-CONTACTS-EMPTY: B7.2 empty-state navigation gap

`showcase-ux-regression.spec.ts:199` navigates to
`/p/crm_account/view/<pid>#contacts` for an account with zero
contacts and expects `[data-testid="subtable-empty-state"]`. The
testid exists in `SubTableViewer.tsx` but the page-level rendering
path for the `#contacts` hash anchor on a fresh detail load may not
mount the subtable block when the relation list is empty. Needs a
~30 min UI investigation: confirm whether the contacts subtable
block renders for empty result sets, or whether the parent tab is
collapsed on first paint.

### SHOWCASE-P6.2: Form runtime rendering on `showcase_all_fields`

`runtime-rendering-e2e.spec.ts:316`. Independent feature regression
unrelated to seed data (tests against a fresh page). Needs separate
investigation.

### SHOWCASE-D5: Sub-table 3-mode rendering

`subtable-modes-e2e.spec.ts:306`. Independent rendering regression;
likely interacts with the page-snapshot/replace flow inside the test.
Needs separate investigation.

## Out of scope for this fix

- `list-ux-features.spec.ts` 3 tests in full-suite cluster: pass when
  run in isolation; cross-test sort-state leakage.
- Verifying seed fix end-to-end: requires `reset-and-init.sh`. Multiple
  worktrees currently share the host stack (red-line #11), so this run
  must be deferred to a docker isolated stack (or done after worktree
  cleanup).

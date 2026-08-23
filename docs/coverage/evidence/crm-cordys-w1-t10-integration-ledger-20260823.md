# CRM Cordys W1 T10 integration ledger

Status: active

This is the process ledger for the T10 integration branch. Product parity remains governed by the canonical competitive SoT; this ledger does not create a second product-gap matrix.

## Locked baselines

- OSS base: `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` (`origin/main` at T10 creation)
- CordysCRM: `v1.8.1` / `ab96c96f524985ea84f112c7a6b03970711f921e`
- Data migration: excluded during development
- Integration branch: `codex/crm-w1-integration-t10-20260823`
- Integration worktree: `/Users/ghj/work/auraboot/.worktrees/auraboot-crm-w1-integration-t10-20260823`
- Runtime policy: at most two development runtimes workspace-wide and one unique verification runtime; T10 currently owns no runtime
- Final product verdict until the canonical denominator closes: `Cordys full-product replacement = NOT MET`

## Dependency ledger

Live state captured on 2026-08-23. A dependency is eligible only when its remote branch and open PR resolve to the same stable head OID and its handoff identifies product, test/evidence, and shared-manifest commits.

| Task | Local branch | Base OID | Local HEAD | Dirty | Ahead | Remote head | PR | Shared files / JSON pointers | Integration state |
| --- | --- | --- | --- | --- | ---: | --- | --- | --- | --- |
| T03 | `codex/crm-w1-lead-lifecycle-t03-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `4f8e3b4c7af2418a48f5cb28a3ce6a8d45666d56` | no | 3 | same as local | [#1655](https://github.com/AuraBootTeam/auraboot/pull/1655) | `/[]` keys `crm.saved_view.*`; `/[]` saved views with `viewKey` `crm_lead_*`; generated lead rows in `/productSurfaces/*`; release denominator assertions | `integrated-product-test-shared` |
| T04 | `codex/crm-w1-account360-t04-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | same as base | no | 0 | absent | absent | pending handoff | `waiting-for-stable-head` |
| T05 | `codex/crm-w1-contact-t05-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `3f35de7c63770e561900cbcbf077af786d44af5e` | no | 3 | same as local | [#1656](https://github.com/AuraBootTeam/auraboot/pull/1656) | `/[]/code=crm_contact_common/extension/importPolicy/modes`; `/[]/code=crm_contact_common/extension/importPolicy/updateKeys`; generated contact rows in `/productSurfaces/*`; release denominator assertions | `integrated-product-test-regenerated-shared` |
| T06 | `codex/crm-w1-followup-t06-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | same as base | no | 0 | absent | absent | pending handoff | `waiting-for-stable-head` |
| T07 | `codex/crm-w1-opportunity-t07-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `0e564e62740c74d20ef33cc10dd8d3a4d14bb27d` | no | 1 | same as local | [#1654](https://github.com/AuraBootTeam/auraboot/pull/1654) | generated opportunity rows in `/productSurfaces/*`; release denominator assertions | `integrated-split-product-test-shared` |
| T08 | `codex/crm-w1-search-bulk-t08-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | same as base | no | 0 | absent | absent | pending handoff | `waiting-for-stable-head` |
| T09 | `codex/crm-w1-governance-t09-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `92eaa0c73ac3d20759d25c8db4ba1b7492bd3d2f` | no | 3 | same as local | [#1653](https://github.com/AuraBootTeam/auraboot/pull/1653) | none | `integrated-product-test-report` |

## Merge-tree preflight

Initial preflight found no leaf delta to merge: all T03-T09 heads equaled the locked base. Therefore that conflict status was `not-applicable-yet`, not `clean`.

The first stable wave (T03, T07, and T09) was rechecked against the current integration head. Merge-tree emitted no conflict markers. T07's mixed commit was split on T10 into product, test, and generated/shared layers. T05 then reported conflicts only in the generated manifest and its count assertions; its product and test commits were integrated, while its leaf-generated manifest commit was replaced by regeneration from the combined tree. No leaf manifest was accepted wholesale. The integrated denominator is currently `735 pass / 2763 untested` across `3498` product rows; this is a denominator snapshot, not a product completion percentage.

For each stable leaf head, T10 will record:

1. `git merge-tree $(git merge-base <current-integration-head> <leaf-head>) <current-integration-head> <leaf-head>` result.
2. Exact product commits selected first.
3. Exact test/evidence commits selected second.
4. Shared-file patches applied last, with JSON pointers for `plugins/crm/config/models.json`, `dicts.json`, `i18n.json`, verifier registration, and generated manifest rows.
5. Conflict decisions at pointer/hunk level. Whole-file acceptance from either side is prohibited.

## Integration order and verification ledger

Integration order: product implementation -> tests/evidence -> shared registries/manifests.

Verification order: static/schema/i18n/reachability/registration -> backend unit/plugin test/jar -> Web typecheck/component -> T03-T09 targeted journeys -> W1 slice -> fresh full CRM gate -> Cordys side-by-side through the T02 SSH tunnel -> coverage/trust/mutation.

Execution requirements for the verification runtime: single worker, retry `0`, fresh reset/seed, and source/PID/cwd/port/DB evidence. Any full failure must be classified as `product-failure`, `test-flaky`, `environment-invalid`, `seed-invalid`, `data-pollution`, `test-drift`, or `pre-existing-stale`. A targeted rerun cannot replace the original full verdict.

## Current allowed claim

`T10 integration initialized; T03-T09 dependencies are untested and waiting for stable heads. No W1 integration or product parity claim is allowed.`

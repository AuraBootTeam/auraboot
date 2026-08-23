---
type: artifact
status: active
created: 2026-08-23
---

# CRM Cordys W1 T10 integration ledger

Status: active

This is the process ledger for the T10 integration branch. Product parity remains governed by the canonical competitive SoT; this ledger does not create a second product-gap matrix.

## Locked baselines

- OSS base: `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` (`origin/main` at T10 creation)
- CordysCRM: `v1.8.1` / `ab96c96f524985ea84f112c7a6b03970711f921e`
- Data migration: excluded during development
- Integration branch: `codex/crm-w1-integration-t10-20260823`
- Integration worktree: `/Users/ghj/work/auraboot/.worktrees/auraboot-crm-w1-integration-t10-20260823`
- Runtime policy: at most two development runtimes workspace-wide and one unique verification runtime
- T10 runtime: `crm-w1-integration-t10`, source `/Users/ghj/work/auraboot/.worktrees/auraboot-crm-w1-integration-t10-20260823`, backend `6410`, Vite `5110`, BFF `6110`, database `auraboot_10`, evidence root `/Users/ghj/work/auraboot/.workspace/evidence/crm-w1-integration-t10`
- Cordys reference runtime: `ssh dev`, image pulled through China mirror `hub.1panel.dev`, digest pinned to `sha256:6ccfdfd5f89e7599727528908dcb3820f3c7e843751a558989bc1997c1e209f8`, loopback ports only
- Final product verdict until the canonical denominator closes: `Cordys full-product replacement = NOT MET`

## Dependency ledger

Live state captured on 2026-08-23. A dependency is eligible only when its remote branch and open PR resolve to the same stable head OID and its handoff identifies product, test/evidence, and shared-manifest commits.

| Task | Local branch | Base OID | Local HEAD | Dirty | Ahead | Remote head | PR | Shared files / JSON pointers | Integration state |
| --- | --- | --- | --- | --- | ---: | --- | --- | --- | --- |
| T03 | `codex/crm-w1-lead-lifecycle-t03-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `4f8e3b4c7af2418a48f5cb28a3ce6a8d45666d56` | no | 3 | same as local | [#1655](https://github.com/AuraBootTeam/auraboot/pull/1655) | `/[]` keys `crm.saved_view.*`; `/[]` saved views with `viewKey` `crm_lead_*`; generated lead rows in `/productSurfaces/*`; release denominator assertions | `integrated-product-test-shared` |
| T04 | `codex/crm-w1-account360-t04-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `568fc13e7d91dbb3969f71d2aae4fbaff9bc4f9b` | no | 1 | same as local | [#1660](https://github.com/AuraBootTeam/auraboot/pull/1660) | generated OSS manifest CRM command row `crm:save_account_relation` and stats | `integrated-split-product-test-regenerated-shared` |
| T05 | `codex/crm-w1-contact-t05-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `c32d745cd57f0c1ae717d249a37e0fe21e7782dc` | no | 6 | same as local | [#1656](https://github.com/AuraBootTeam/auraboot/pull/1656) | `/[]/code=crm_contact_common/extension/importPolicy/modes`; `/[]/code=crm_contact_common/extension/importPolicy/updateKeys`; generated contact rows in `/productSurfaces/*`; release denominator assertions | `integrated-product-test-regenerated-shared` |
| T06 | `codex/crm-w1-followup-t06-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `5d685b3e76d04a550c541095537dfd277703e3dc` | no | 6 | same as local | [#1659](https://github.com/AuraBootTeam/auraboot/pull/1659) | `/[]` keys `command.crm:delete_follow_*`; generated activity rows in `/productSurfaces/*`; OSS manifest CRM rows; release verifier evidence contracts and denominator assertions | `integrated-product-test-regenerated-shared` |
| T07 | `codex/crm-w1-opportunity-t07-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `0e564e62740c74d20ef33cc10dd8d3a4d14bb27d` | no | 1 | same as local | [#1654](https://github.com/AuraBootTeam/auraboot/pull/1654) | generated opportunity rows in `/productSurfaces/*`; release denominator assertions | `integrated-split-product-test-shared` |
| T08 | `codex/crm-w1-search-bulk-t08-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `dd47c97c63bd35f0cd3e90cf8870b51b516f042c` | no | 3 | same as local | [#1658](https://github.com/AuraBootTeam/auraboot/pull/1658) | none | `integrated-product-test-runtime-evidence` |
| T09 | `codex/crm-w1-governance-t09-20260823` | `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043` | `09954bb6dca9a8490a63bacf448e47124932d8b5` | no | 4 | same as local | [#1653](https://github.com/AuraBootTeam/auraboot/pull/1653) | none | `integrated-product-test` |

## Merge-tree preflight

Initial preflight found no leaf delta to merge: all T03-T09 heads equaled the locked base. Therefore that conflict status was `not-applicable-yet`, not `clean`.

The first stable wave (T03, T07, and T09) was rechecked against the current integration head. Merge-tree emitted no conflict markers. T07's mixed commit was split on T10 into product, test, and generated/shared layers. T05 then reported conflicts only in the generated manifest and its count assertions; its product and test commits were integrated, while its leaf-generated manifest commit was replaced by regeneration from the combined tree. T06 added a follow-up evidence contract and changed shared i18n, verifier, CRM manifest, and OSS manifest surfaces. Its i18n and verifier semantics were preserved, while both manifests were regenerated from the combined tree. T04's only merge-tree conflict was the OSS generated manifest; its mixed commit was split and the manifest regenerated with `crm:save_account_relation` retained. No leaf manifest was accepted wholesale. The final combined denominator is `736 pass / 2781 untested` across `3517` product rows. The pass count decreased from the pre-T04 integrated snapshot (`739`) while the denominator grew; those rows remain untested pending evidence reconciliation and are not hidden or normalized away. This is a denominator snapshot, not a product completion percentage.

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

The fresh T10 golden stack imported `org-management` and `crm`, then completed all nine startup stages. Static/schema/i18n/reachability/registration gates passed; the full CRM Node suite passed `96/96`; CRM backend and targeted platform authorization/import tests passed; the selected frontend component suite passed `20/20`; release-coverage mutation checks passed `5/5`.

The unified W1 Playwright journey ran with one worker and retry `0`: the final full rerun passed `32`, skipped `1`, failed `0`. The skip is the fixture-import setup guard and does not hide a product action. The separate CMM-10 multimodel import journey passed `1/1` with retry `0`. Earlier full-run failures were retained and classified: three were `test-drift`; the remaining `400` exposed a real product configuration defect (`foreignKey` was ignored by the runtime) and was fixed by using `parentField` before the complete rerun.

Cordys `v1.8.1` is healthy on `ssh dev` after using the China mirror, but authenticated side-by-side evidence is still pending the browser-enforced password-change submission. No cluster verification was run, by scope. The canonical denominator remains `736 pass / 2781 untested` across `3517` rows, so the allowed conclusion is: `W1 selected integrated journeys = VERIFIED; Cordys full-product replacement = NOT MET.`

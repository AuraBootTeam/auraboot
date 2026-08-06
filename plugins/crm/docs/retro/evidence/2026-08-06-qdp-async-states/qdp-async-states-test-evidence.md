# QDP async-state current-source test evidence

Run date: 2026-08-06

Allowed claim: targeted-tested APP-01 QDP async compilation and visible page-state slice. This is not APP-01, assembled DQ, L1/L2, or AMOS readiness.

## Exact source and runtime identity

- Core branch / starting head: `codex/amos-qdp-release-center-public-crm` / `454034b12c017443b55def09f27fc088aaa5bc54`
- Plugin adapter branch / head: `codex/amos-qdp-release-center` / `598454bbee9a2177ce892d29756993a08740667c`
- Composition: `amos-qdp-async-states-s59`
- Backend / Web / BFF: `6459 / 5159 / 6159`
- PostgreSQL / Redis: `auraboot_59 / DB 10`
- CRM jar SHA-256, built and runtime: `6e3f49ec2abc9fd1c7e6c709a3b23e3b3524a8f6be5e7d6081d9c153ee1a71d2`
- Imported current-source roots: Core `plugins/crm`; Plugin `pcba-crm`
- Import/reference result: both imports `OK`; `Reference-integrity sweep: OK (no dangling cross-plugin references)`

The fresh host used the current Core working-tree implementation and the exact clean Plugin adapter head. HTTP and browser checks reached the real boot jar, Web/BFF, PostgreSQL and async task executor; no mock server, static response, retry, skip, threshold relaxation, production system or stale composition supplied a pass.

## Automated result matrix

| Axis | Result | Identity |
| --- | --- | --- |
| QDP config contract | 7/7 pass | `node --test plugins/crm/tests/crm-qdp-release-config.test.mjs` |
| Core CRM + Plugin PCBA cross-repo contract | 11/11 pass | current Core config + `pcba-crm/tests/qdp-reference-config.test.mjs` |
| CRM Node regression | 24/24 pass | five `plugins/crm/tests/*.test.mjs` files |
| CRM backend | 159/159 pass; QDP handler 35/35 | Gradle JUnit XML, 0 failure/error/skip |
| CRM JSON parse | 217/217 pass | all current CRM JSON documents |
| Plugin PCBA adapter | 13/13 pass | `pcba-crm/tests/qdp-reference-config.test.mjs` |
| Host import/reference | pass | canonical host-first importer; two exact source roots |
| HTTP true stack | 21/21 pass | `qdp-release-center-true-stack-20260806-231048.json` |
| Browser full golden | 6/6 pass | 1 worker, 0 retry, 3.6m; `qdp-release-center-browser-20260806-231048.json` |
| Identity screenshot alignment | 1/1 pass | same current-source host; isolated evidence output; 0 retry |
| Acceptance manifest | 17 rows | 15 pass, 1 partial, 1 untested; 0 gap |

## True-stack assertions

The 21 checks include exact release-duty permission, qualification, Requirement Version and File Package Hash confirmation binding, direct-write denial, stale/no-permission/cross-tenant/replay/changed-intent conflicts, asynchronous running/completed task states, validation-failed persistence and corrected retry, partial-success outcome, Draft/Review/Released/Superseded compatibility, external file failure/recovery, legacy command compatibility, all 20 legacy writer fields and 44 audit records.

## Browser assertions and original PNG review

The full browser pass requires all six scenario markers: list/released detail, empty state, async loading/validation failure/partial success/recovery, stale feedback, external failure/recovery/release, and no-permission. The machine record is fail-closed: absent scenario markers produce `incomplete` rather than pass.

Original 1280×720 PNGs were opened locally at original resolution. Together they visibly show the QDP title/code, Released and GT-D04 states, revision, 64-character content hash, human-readable diff, Requirement Version/customer confirmation, Pack Set, downstream impact, loading progress, empty table state, validation-recovery guidance, partial-success warning, stale correction and access-denied boundary. No raw `crm_qdp_*` field code, JSON, Java stack or source code is visible.

## Falsifiability probe

The product mutation changed the allowed compile states from `{draft, validation_failed}` to `{draft}` and reran:

`./platform/gradlew -p plugins/crm/backend test --tests com.auraboot.plugins.crm.handler.ReleaseQdpHandlerTest.failedAsyncCompilationPersistsValidationFailedAndSupportsCorrectedRetry --rerun-tasks --no-daemon --console=plain`

The mutated build collected one test and failed one at the corrected retry. After restoring the product condition, the exact same command collected one test and passed one. The assertion therefore detects loss of validation-failed recovery and is not an empty/always-green check.

## Explicitly unverified denominator

- Batch action, direct deep-link command invocation and Agent Tool caller parity remain untested.
- The old standalone plugin CLI remains partial for host-supported async command presentation properties; host import/reference/runtime is the executable authority for this slice.
- No writer cutover, destructive migration, legacy retirement, production release, PR merge or #270 business change was performed.

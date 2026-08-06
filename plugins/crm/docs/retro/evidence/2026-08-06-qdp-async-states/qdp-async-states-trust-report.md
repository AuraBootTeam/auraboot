# QDP async-state evidence trust report

Run: `20260806-231048`

Allowed claim: targeted-tested APP-01 QDP async compilation and visible page-state slice. This report does not establish complete APP-01, DQ Journey, L1/L2, or AMOS readiness.

## 1. Green-signal identity

The accepted signals all come from current-source Core plus the exact clean Plugin PCBA adapter:

- Core working tree over `454034b12c017443b55def09f27fc088aaa5bc54`
- Plugin adapter `598454bbee9a2177ce892d29756993a08740667c`
- fresh composition `amos-qdp-async-states-s59`
- backend/Web/BFF `6459/5159/6159`, PostgreSQL `auraboot_59`, Redis DB `10`
- runtime CRM jar SHA-256 equals built jar SHA-256: `6e3f49ec2abc9fd1c7e6c709a3b23e3b3524a8f6be5e7d6081d9c153ee1a71d2`
- Core CRM and Plugin PCBA imports `OK`; cross-plugin reference sweep `OK`

No mock, route interception, static page, production system, stale artifact, skip, retry, threshold, sampled subset disguised as full, or early-green marker is part of the accepted true-stack/browser verdict.

## 2. Denominator and verdict

The previous manifest had 14 rows: 10 pass, 1 partial, 1 gap, 2 untested. This slice splits the asynchronous lifecycle gap into executable compilation and validation-recovery rows, and splits the combined page-state denominator into Loading, Empty and Partial Success. The new denominator is 17 rows: 15 pass, 1 partial, 1 untested, 0 gap.

The denominator increased by three while executable pass rows increased by five. Caller parity remains explicitly untested; the old standalone CLI remains partial. Therefore the result is neither 100% nor a readiness claim.

## 3. Assertion strength and fail-closed behavior

- Handler tests inspect persisted lifecycle, progress stage, outcome, error message and corrected retry, not only HTTP status.
- HTTP checks dispatch through the platform async task API, poll real task state, read the QDP from the host API/PostgreSQL path and require 21 named checks.
- Browser checks use real login, list/detail routes, UI action clicks, real network responses and task modal state. The manifest verdict becomes `incomplete` if any of the six required scenario markers is absent.
- Permission, stale, cross-tenant, replay and external-failure paths assert explicit negative responses and preserved state.
- Browser visual checks combine executable DOM assertions with local original-resolution PNG inspection; vision alone does not establish persistence or authorization.

## 4. Mutation falsifiability

Controlled mutation: remove `validation_failed` from the set of states accepted by compile retry.

Command:

`./platform/gradlew -p plugins/crm/backend test --tests com.auraboot.plugins.crm.handler.ReleaseQdpHandlerTest.failedAsyncCompilationPersistsValidationFailedAndSupportsCorrectedRetry --rerun-tasks --no-daemon --console=plain`

- Mutated product: 1 collected, 1 failed at corrected retry with `IllegalArgumentException`.
- Restored product: the exact command produced 1 collected, 1 passed.

This proves the recovery test can turn red for the behavior it claims to guard. The mutation did not remain in the working tree.

## 5. Failure classification ledger

| Failed command/run | First root cause | Classification | Correction and accepted rerun |
| --- | --- | --- | --- |
| initial host import/start | current CRM PF4J jar had not yet been built/copied into the isolated runtime | environment assembly | built the current-source jar, copied it into the isolated PF4J directory, restarted/imported; jar hashes match and reference sweep passed |
| first browser fixture setup | fixture SQL wrote a non-existent `updated_time` column | test-driver defect | aligned the fixture with the actual host schema; subsequent real browser setup passed |
| browser r1 visual assertion | compilation outcome/stage were rendered as raw codes because the block lacked dictionary preload | implementation defect | changed the block to `form-section`, bound outcome/stage dictionaries and localized tags; current-source browser rerun passed raw-code leakage assertions |
| browser r2 async scenario | stale/reused fixture and visible-state expectations did not match the corrected lifecycle | test-driver/residual-data defect | created fresh run-scoped fixtures and required persistent state recovery; r3 and final full run passed |
| controlled handler mutation | removed the accepted retry state | expected trust red test | restored the condition; exact targeted test passed 1/1 |

Failed runs are not counted as accepted green results. Their final `.last-run` markers and intermediate duplicated evidence are intentionally excluded from the committed evidence pack; their causes and correction paths remain recorded here.

## 6. Accepted executable evidence

| Evidence | Result |
| --- | --- |
| CRM QDP config | 7/7 |
| Core CRM + Plugin PCBA cross-repo contract | 11/11 |
| CRM Node regression | 24/24 |
| CRM backend | 159/159; QDP handler 35/35; 0 failure/error/skip |
| CRM JSON parse | 217/217 |
| Plugin PCBA adapter | 13/13 |
| host import/reference sweep | pass |
| true stack | 21/21 |
| full browser golden | 6/6, 1 worker, 0 retry, 3.6m |
| identity screenshot alignment | 1/1, 0 retry |

## 7. Residual trust boundaries

1. Batch, direct deep-link command invocation and Agent Tool parity have no accepted executable evidence and remain `untested`.
2. The old standalone plugin CLI is `partial`; fresh host import/reference/runtime is authoritative for the host-supported command properties in this slice.
3. The Plugin worktree supplies only PCBA adapter/config evidence. It does not contain a parallel public CRM/QDP writer.
4. No writer cutover, destructive migration, legacy retirement, production release, PR merge or #270 business modification is covered or authorized.

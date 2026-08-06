# QDP Release Center current-source test evidence

Run date: 2026-08-06

Core branch: `codex/amos-qdp-release-center-public-crm`

Core stacked base: `132c619913d2a7de9c18367c614a3747a308d6df`

Plugin adapter base: `aed2b6356bb7abccab6c8276db43d59e6e19f418`

## Runtime identity

- Fresh composition: `amos-qdp-release-center-public-s58`
- Backend / Web / BFF: `6458 / 5158 / 6158`
- PostgreSQL: `auraboot_58`
- Redis DB: `9`
- Core CRM source: `/Users/ghj/work/auraboot/auraboot/.worktrees/amos-qdp-release-center-public-crm/plugins/crm`
- Plugin PCBA source: `/Users/ghj/work/auraboot/plugins/.worktrees/amos-qdp-release-center/pcba-crm`
- CRM jar SHA-256: `084f7a0c8259a2b59109cdfd687ba18a89bbb79769cdfcb9bde2a88cb3a27937`
- Tenant: `343713625321639936`
- Release-duty actor: `343713913034117120`

The host-first importer loaded the exact source roots above and completed a clean cross-plugin reference sweep. No mock server, static page fixture, old runtime jar, retry, skip, threshold, or production system is used.

## Automated results

| Axis | Result |
| --- | --- |
| QDP config | 7/7 pass |
| Core CRM + Plugin PCBA contract | 11/11 pass |
| CRM Node regression | 24/24 pass |
| CRM backend | 156/156 pass; QDP handler 32/32 |
| CRM JSON parse | 225/225 pass |
| DSL action/reference gates | pass |
| Host import/reference sweep | pass |
| HTTP current-source true stack | 19/19 pass |
| Browser current-source golden | 4/4 pass; 1 worker; 0 retry; 2.4m |
| Acceptance manifest | 14 rows: 10 pass, 1 partial, 1 gap, 2 untested; missing evidence 0 |

## True-stack identity and assertions

Machine record: `qdp-release-center-true-stack-20260806-191622.json`.

- Customer Request: `01KZBCKRS3HYJK7BPYQHVQ0BZ6`
- PCBA sidecar: `01KZBCKRXC2EPFNTDTNR0912A3`
- Released at HTTP-run end: `01KZBCKTFNF3ZJYWDMK367ZWDR`
- Superseded: `01KZBCKSHYFQYR3M1SZ1VXW9EV`
- Stale review fixture: `01KZBCKT6GNJEEZ1RKHW03MCV4`
- Legacy direct-release proof: `01KZBCKV435QSP4NGMMD73AFG7`
- Result: 19/19 checks, 9 audit rows in the fresh DB, all 20 ce55 legacy writer fields observed.

The 19 checks cover explicit release duty, qualification, direct-create denial, expected-version and intent replay conflicts, four-record binding, exact writer metadata, Draft → Review → Released → Superseded, stale source, real file failure/recovery, legacy API compatibility, cross-tenant denial and audit.

## Browser assertions and original-image review

Machine record: `qdp-release-center-browser-20260806-191622.json`; successful visual rerun identity `20260806-191622-public-core-visual-rerun2`.

Completed scenario markers:

1. Release Center list and released detail;
2. stale source with visible corrective feedback;
3. external file failure followed by recovery, release and supersede;
4. no-permission navigation/action denial.

The pass record requires all four markers. Original PNGs were opened at original resolution. They visibly show lifecycle labels, GT-D04, 64-character hashes, the Requirement/File Package/Pack Set diff, customer confirmation binding, `PCBA-MFG@browser`, `1 downstream object(s), 0 blocked`, external/stale feedback and the access-denied boundary. No raw `crm_qdp_*` name, JSON, Java stack, or source code was visible.

## Failure and correction ledger

| Failure | Classification | First cause | Correction |
| --- | --- | --- | --- |
| Direct Core-root Playwright invocation failed before tests | environment invocation | `@playwright/test` is not installed at the Core root | used the existing pinned Playwright runtime with the same Core config; no install/lockfile change |
| First visual rerun failed 1 test and did not run 3 | residual fixture / test driver | the chosen Released pid had been legitimately superseded by the preceding successful browser release | read current record status, selected the actual Released pid and used a new replay identity; complete 4/4 passed with 0 retry |

The failed run remains reported. It is not counted as a successful denominator and did not drive any production-code relaxation.

## Explicitly unverified denominator

- Asynchronous `Compiling` / `Validation Failed` orchestration is not implemented.
- Batch, deep-link command and Agent Tool parity are untested.
- Explicit Loading, Empty and Partial Success page states are untested.
- The old standalone plugin CLI remains partial against host-supported additive command properties.
- No writer cutover, destructive migration, legacy retirement, production release or PR merge was performed.
- This evidence supports this QDP Release Center slice only; it is not full APP-01, assembled DQ, L1/L2, or AMOS readiness.

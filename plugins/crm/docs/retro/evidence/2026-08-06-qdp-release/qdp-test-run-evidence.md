# QDP test run evidence — 2026-08-06

## Environment

- Runtime: `amos-qdp-row-version-s54`
- Core source: `/Users/ghj/work/auraboot/auraboot/.worktrees/amos-qdp-row-version`
- Original Plugin source: `/Users/ghj/work/auraboot/plugins/.worktrees/amos-dq1-qdp-release` (historical runtime provenance)
- Current canonical CRM source: `/Users/ghj/work/auraboot/auraboot/.worktrees/amos-dq1-qdp-release-public-crm/plugins/crm`
- PostgreSQL database: `auraboot_54`
- Web / BFF / backend: `5154 / 6154 / 6454`
- Core base commit: `9efdc7f4bc8cc185db71a24c986e1a8e75a76519`
- Plugin base commit: `36067e03e22e1cd2112afde29036e0e9abb876f0`
- Customer Request pid: `01KZAVFMPFZD0NR0TDEDH1JNAH`

Listeners were checked with `lsof`; Web, BFF and backend belonged to this runtime. `/actuator/health`, BFF `/health` and Web `/login` all returned healthy responses before the browser run.

After public CRM ownership consolidation, the CRM-owned QDP files and this evidence directory were relocated byte-for-byte into `AuraBootTeam/auraboot/plugins/crm`; the original source path above remains recorded because it is the source root that produced this historical run. The private Plugins repository retains only the PCBA sidecar adapter.

## Automated gates

| Gate | Result |
| --- | --- |
| CRM Java: `gradle :crm:backend:test --rerun-tasks --offline --no-daemon` | exit 0; 151 tests, 0 failures, 0 errors |
| QDP DSL/config: `node --test crm/tests/crm-qdp-release-config.test.mjs pcba-crm/tests/qdp-reference-config.test.mjs` | exit 0; 9/9 pass |
| QDP HTTP true stack: `crm/scripts/it/qdp_release_true_stack.py` against slot 54 | 15/15 semantic checks pass |
| Shared Web action contract + detail regression | exit 0; 66/66 pass |
| Web typecheck | exit 0 |
| Core related Java regression | 176/176 pass, including real PostgreSQL schema integration 3/3 |

## Public CRM ownership relocation verification

After Core PR #1596 established `AuraBootTeam/auraboot/plugins/crm` as the canonical CRM owner, the foundation CRM delta was relocated without changing its model/command codes or handler implementation. The following gates were rerun in the isolated public-CRM worktree:

| Gate | Result |
| --- | --- |
| Public CRM Node contracts: `node --test plugins/crm/tests/*.test.mjs` | 22/22 pass, including QDP 5/5 |
| Public CRM backend: `gradle clean test jar --no-daemon` | 151/151 pass; `ReleaseQdpHandlerTest` 27/27 |
| DSL action gate: `node scripts/check-dsl-actions.mjs plugins/crm` | pass |
| CRM JSON parse + true-stack driver compile | pass |
| Migrated implementation hash comparison | handler, handler test, QDP binding/command/fields/pages and config test match ce55 byte-for-byte |

The original host-first JSON and screenshots remain historical evidence from slot 54; they were not relabeled as a new runtime execution. Ownership relocation is therefore proven by exact-byte comparison plus current repository contracts/unit tests, while the original real-stack verdict keeps its original provenance.

## Falsifiability record

1. Before the L1 dynamic-row-version implementation, seven new contract tests failed because `mt_*` schema creation/read/mutation did not expose or advance `row_version`. The same focused and related suites were rerun after the fix and passed.
2. The first real browser upload reached the QDP handler but returned `Bad parameter`. Backend evidence identified the exact semantic failure: `crm_qdp_customer_request_id` was missing from the command payload even though the command target pid was present.
3. A focused Web test was added for `promptUpload + ${record.pid}`. Before the fix it failed with `crm_qdp_customer_request_id: undefined`; after resolving templates from the clicked detail record, it passed.
4. The same browser journey was rerun without weakening the QDP handler. It uploaded `qdp-browser-upload.csv`, produced `QDP-CR-20260806-003-R0003`, displayed the new revision immediately, and wrote a successful `completion` command audit row.

## Independent PostgreSQL readback

```text
crm_qdp_code                 revision  primary_filename                 status    row_version
QDP-CR-20260806-003-R0001    1         amos-qdp-20260806-141701.txt     released  1
QDP-CR-20260806-003-R0002    2         amos-qdp-20260806-141701.txt     released  1
QDP-CR-20260806-003-R0003    3         qdp-browser-upload.csv           released  1
```

Latest successful audit row:

```text
command_code     success  phase_reached  user_id             created_at
crm:release_qdp  true     completion     343637130251210752  2026-08-06 14:32:58.233964+08
```

## Environment/tooling caveat

The host-first import physically created/published the required CRM, PCBA and Product Catalog models and tables, and the tables carry `row_version`. The import wrapper's final status ledger still reports CRM as `previewing` and other packages as `missing` because `ab_plugin_import_log` was not populated. This is a status-ledger/tooling gap; it is not treated as a successful import-status assertion.

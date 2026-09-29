---
type: system-reference
status: active
created: 2026-09-28
---

# OSS full Playwright gate run — 2026-09-28

Full OSS-scoped Playwright regular project executed to completion on a fresh, isolated stack,
after repairing the structural debt left by the 2026-09-27 JWT-cookie convergence report.
The suite is **not green**: 714 passed / 86 skipped / 176 failed / 206 did-not-run out of 1182
collected. Every failure family below has a verified root cause and a fix path; the two largest
families are pre-existing contract debt documented here for the first time with runtime evidence.

## Run identity

```text
branch:            fix/oss-full-gate-debt-20260928 (a0f759a73 + b3633072d on 5f6f9d832)
runtime:           oss-full-gate-wt / slot 146 / PostgreSQL auraboot_146
backend:           127.0.0.1:6546 (boot jar built from main incl. #2073)
web / bff:         127.0.0.1:5246 / 127.0.0.1:6246 (BFF_ALLOWED_PORTS=5245-style injection via reset script)
plugin profile:    e2e (16 plugins incl. test-fixtures) via ./scripts/oss-reset-and-init.sh FORCE_HOST=1
playwright:        PW_PROFILE=oss + playwright.oss.config.ts (projects setup→auth→oss; deep blocked)
regular result:    1182 collected → 714 passed / 86 skipped / 176 failed / 206 did-not-run (1.5h)
setup + auth:      21/21 passed (separate phase, before the regular run)
deep project:      not run — blocking regular project remained red (gate discipline per 09-27 report)
results json:      web-admin/test-results/results-regular.json (this worktree)
log:               /tmp/oss-regular-146.log
```

## 2026-09-29 targeted follow-up: unified designer flat-contract migration

The 57-case `unified-designer-kind-and-binding.spec.ts` migration is now functionally green
against the live slot-146 backend/Web/BFF stack. This is a targeted regression result, not a
replacement for the 2026-09-28 full OSS gate result above.

```text
base commit:        07852d4db2051d0b760646665396c3092f8e36db + final widget drag stabilization
runtime endpoints:  backend 127.0.0.1:6546 / web 127.0.0.1:5246 / bff 127.0.0.1:6246
database:           auraboot_146
browser execution:  57 collected / 57 passed / 0 failed / 0 skipped / 0 did-not-run (2.0m)
unit regression:    4 files / 85 passed / 0 failed
browser artifacts:  web-admin/test-results/unified-designer-post-main-merge-final/
```

The migration now uses flat-v4-valid fixtures and verifies real pointer drag/drop, DOM order,
undo/redo, save requests, and API readback for sub-table, repeater, subform, action-bar,
form-section, table, filter-bar, widget, column, filter-field, and action subtrees/leaves. Root
container moves use top-level siblings; inside moves use compatible empty tabs. The outline and
canvas surfaces still feed the same `@dnd-kit` kernel, so switching long-list reorder gestures to
the compact outline surface removes auto-scroll geometry drift without bypassing drag/drop.

Trust boundary: `./aura runtime verify oss-full-gate-wt` is still invalid because this legacy
verification runtime has no immutable source-set descriptor, its live backend/Web/BFF PIDs are
unregistered, and no canonical product runtime manifest was published. The services are healthy
and the browser tests truly executed, but this result must be reported as targeted functional
evidence, not a trusted full-gate PASS. A fresh, correctly registered runtime plus the entire OSS
regular/deep gate is still required before closing the overall gate debt.

## Fixes delivered (this branch)

1. **OSS scope debt — independent applications** (`oss-scope.json`): excluded
   `approval/approval-workflow`, `approval/inline-approval-panel`,
   `model/model-publish-governance` (all drive `/api/bpm/*`; aura-bpm is an independent
   application absent from the OSS core runtime — verified 404 after auth).
2. **Capability-probe skips in mixed OSS files**: `designers.spec` F4-c BPMN section (9 cases,
   `/bpmn-designer` route absent), `unified-designer-workbench.spec` UDW-015 + beforeAll
   provisioning, `dashboard-widget-runtime.spec` DWR-008/009/010 (com.auraboot.crm plugin
   manifest absent; `/api/bpm` absent), `automation-designer-golden.spec`
   N-START-PROCESS / N-TRIGGER-BPM-EVENT (previously broke the whole serial file).
   All probes skip loudly with a reason; when the capability exists every assertion still runs.
3. **test-fixtures self-consistency (unblocks every fresh-stack reset)**: PR #2073 re-declared
   `e2et_crm_opp.create/update` commands without the model, so the import reference-integrity
   sweep rejected the whole plugin on any fresh install. Declared the legacy `e2et_crm_opp`
   model + name/stage/amount fields + stage dict + model-field bindings (mirroring
   `e2et_stage_record`, its stage-rail twin); recorded the two commands as API-only in
   `scripts/command-reachability.json`; regenerated `docs/coverage/oss-coverage-manifest.json`.
4. **Setup 01/03 race**: setup spec files run in parallel; 03's member lookup raced
   01-multi-role-users under load and failed the phase. `findMemberPidByEmail` now polls
   30s before failing loudly.
5. **BFF origin allowlist in slot mode**: `oss-reset-and-init.sh` now spawns the BFF with
   `BFF_ALLOWED_PORTS=<web>,<bff>`; without it, every origin-checked endpoint
   (`/api/auth/session-renew` …) 403s on any non-default port runtime.
6. **Unified-designer boot contract** (`unified-designer-kind-and-binding.spec.ts`, 57 cases):
   the file booted via `/unified-designer?pageKey=<key>`, but `GET /api/pages/page-key/{key}`
   is the runtime endpoint and serves published baselines only (mapper: `status = 'published'`
   by design), so drafts 404 and the page rendered 「无法恢复现场配置上下文」 instead of the
   workbench — every case died at the 90s timeout. Helpers now return the created pid and boot
   via `?pageId=`; readbacks use `GET /api/pages/{pid}`. The workbench now renders and the
   cases reach their final assertions.

All changes typecheck (`pnpm typecheck` clean) and `./scripts/check-test-system.sh` PASSes.

## Remaining failure families (verified root causes from the 2026-09-28 full run)

1. **v3→v4 flat-contract migration debt — 57 cases, `unified-designer-kind-and-binding.spec.ts`**
   (**resolved in the 2026-09-29 targeted follow-up; full OSS gate not yet rerun**).
   With the boot fixed, these reach their assertions and fail on one delta: the seeds still
   POST `schemaVersion: 3` tree documents and the assertions still expect the legacy
   `form_root`-nested readback, while the designer save persists the v4 flattened top-level
   block list (kind root implied). This file is #16 (largest, 14 v3 refs / 14 ROOT_BLOCK refs)
   of the 16-file migration queued in `HANDOVER-e2e-flat-contract-migration.md` (workspace
   root; file 1/17 `canvas-box-select-golden` already migrated and green, 24/24). Deletion is
   not recommended: the interactions under test (kind collapse + zh-CN copy, cross-kind guard
   rails, undo/redo container authoring) are the only coverage of those current-designer
   behaviors; no v4 equivalent exists yet. This is the single largest lever: converting it
   also removes most of the 206 did-not-run (serial-mode downstream skips).
2. **No LLM provider — ~35 cases** (`ai/knowledge-*`, `ai/form-draft-fill`, `aurabot/chat-bi-*`,
   `aurabot/behavior-*`, `aurabot/competitive-intelligence-*`). Runtime evidence:
   `"No LLM provider configured for agent: aurabot"`. These need a provider contract
   (real key or a scripted fake) or a dedicated LLM-gated profile. This is a mixed family,
   not a valid whole-file exclusion: the same 19 files also contain already-passing permission
   and safety cases, deliberate no-provider assertions, provider-independent UI/API coverage,
   and failures caused by unrelated contract drift. The 2026-09-29 audit therefore keeps all
   19 files in the OSS denominator until case-level selection or a separately executable,
   evidence-producing provider profile exists.
3. **`meta.command.execute` deny-by-default vs direct-backend API tests — ~15 cases**
   (`cross-field-validation` 6, `platform/command-pipeline` 5, `multi-tenant-isolation`,
   `rbac-platform-baseline`, `cross-tenant-grants`). These log into the backend directly and
   execute commands with the returned JWT; the endpoint gate (hardened in #2041) resolves the
   admin in the System tenant where only `platform_admin` applies, and denies
   `meta.command.execute` (403 "Access denied: required permission not found"). The same
   command through the BFF session path succeeds. Needs an owner decision: grant the
   endpoint-gate permission to a role the direct-API principal holds, or have these tests
   switch space before executing. Verified live: rule engine itself works (422 +
   `context.error: "Delivery date must be after order date"` via the BFF path).
4. **Automation designer drag contract — ~13 cases** (`automation-designer.spec`,
   `automation-enhanced/golden`, `designers.spec` F4-E10/E11). The tests probe
   `[draggable="true"]` HTML5 palette items; the current editor palette no longer exposes
   that attribute, so the probes time out while the pages themselves render (later cases in
   the same files pass). Mechanical test rewrite against the current drag mechanism.
5. **Scattered singles (~30)**: admin CRUD timeouts, saved-view one-per-file, showcase runtime
   cases, `inbox`, `auth/space-selection`. Not individually triaged yet; several are likely
   downstream of the same mid-run permission/space mutations that family 3 exposes.

## Falsifiability

- Scope-debt 404s: `curl -H "Cookie: __session=<admin>"` `http://127.0.0.1:<web>/api/bpm/process-definitions`
  → 404 with auth on the OSS core stack (logged in the run evidence); `plugins/crm/plugin.json`
  absent; `/bpmn-designer` absent from `web-admin/app/routes`.
- Fixture self-consistency: before the fix, `oss-reset-and-init.sh` on a fresh DB failed at the
  closing reference-integrity sweep ("references missing model: e2et_crm_opp"); after, the full
  reset exits 0 and `ab_meta_model` contains `e2et_crm_opp` (re-verified on slot 146).
- Setup race: reproduced once (2026-09-28 full run, "member not found for e2e-operator@test.com"),
  then setup+auth passed 21/21 after the poll fix.
- Designer boot: instrumented probe showed `GET /api/pages/page-key/<draft> → 404` and the
  error page body 「无法恢复现场配置上下文」; after the pid fix the same test reaches its
  readback assertion (visible in `test-results/artifacts`).
- The regular run above is reproducible from this branch with the runtime env file
  `.workspace/env/oss-full-gate-wt.env` + `./scripts/oss-test.sh` (deep phase included once
  regular families 1/3/4 land).

## Session hazards worth remembering

- The canonical checkout had uncommitted work destroyed twice today by a concurrent session
  pulling/resetting `main` (moved 4× during this session); the git guard also reverts branch
  switches there. All work now lives in the linked worktree `.worktrees/oss-full-gate-debt`
  and is committed per-file.
- `platform/plugins/` accumulated stale hybrid-plugin jars from other campaigns (including two
  versions of `edu-engine-plugin`), which PF4J rightly rejects at boot; cleared once manually —
  the reset script still only stages, never prunes, so this will recur.

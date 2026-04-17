# OSS BPM E2E Suite

This directory contains Playwright E2E specs covering the OSS BPM subsystem
(designer → deploy → runtime → task center). Spec 1 of the OSS BPM backlog is
the "designer + runtime full lifecycle" regression suite, aggregated below.

## Spec 1 Regression Suite (`@bpm-regression`)

Five specs carry the `@bpm-regression` tag and together form the aggregated
regression entry point for Spec 1:

| Spec file | Epic | Coverage |
|-----------|------|----------|
| `designer-gateway-lifecycle.spec.ts` | B1 + B2 | Exclusive gateway canvas edit → conditionExpression → deploy → start → verify correct branch activated + audit trail |
| `designer-usertask-form.spec.ts` | B3 | userTask with `formPageKey` configured in designer → deploy → start instance → verify task surfaces formPageKey binding (see known gap #11 below) |
| `designer-roundtrip.spec.ts` | B4 | designerJson save → reload → compare → re-save stability (no field loss on round-trip, including `config.formPageKey` on serviceTask) |
| `workflow-demo-leave-flow.spec.ts` | B5 | `workflow-demo` plugin `wd_leave_approval` full UI lifecycle: start → task center → approve → end (invokes `/approve` API directly for the completion step due to known gap #8) |
| `aura-policy-roundtrip.spec.ts` | C5 | `aura.*` policy round-trip: designer configures policy on userTask → deploy → initiator starts instance → policy-driven approve task appears |

### Run the suite

From `web-admin/`:

```bash
pnpm test:bpm-regression
```

From repo root:

```bash
bash scripts/oss-test.sh --bpm-regression
```

Both routes apply `--grep @bpm-regression` to the OSS-scoped Playwright config
(`playwright.oss.config.ts`). Output is tee'd to `/tmp/pw-oss-*.log` when going
through `oss-test.sh`.

### Run a single spec

```bash
cd web-admin
NO_PROXY=localhost npx playwright test \
  tests/e2e/bpm/designer-gateway-lifecycle.spec.ts \
  --config=playwright.oss.config.ts \
  --project=chromium
```

## Known Product Gaps

Each gap is reproduced by the regression suite; specs document the workaround
inline where invoked.

### Gap #8 — `approveTask` does not inject task result variables

- **Where**: `bpmWorkbenchService.approveTask` / `POST /api/bpm/tasks/{taskId}/approve`
- **Symptom**: Gateway `conditionExpression` like `${taskResult == 'approved'}` throws
  `MVEL null pointer or function not found: taskResult` at runtime, producing
  HTTP 500 on the approve call.
- **Expected fix**: the approve endpoint should read the userTask's DSL
  `taskActions[].resultVariable` / `resultValue` and inject the corresponding
  variables on complete.
- **Spec workaround**: `workflow-demo-leave-flow.spec.ts` opens the approve
  action menu via UI (proving the action is exposed) and then fires the
  completion call via API with `variables: { taskResult: 'approved' }` so the
  downstream chain can proceed. See inline comment around line 371.

### Gap #10 — Plugin `formPageKey` transform in designer import

- **Where**: designer-side plugin import of pre-authored processes.
- **Symptom**: `config.formPageKey` configured in the source plugin does not
  always survive the import/normalize path into designerJson.
- **Spec workaround**: `designer-roundtrip.spec.ts` exercises round-trip
  stability after an in-designer save (which is where the value must survive),
  explicitly asserting `formPageKey` on a serviceTask's opaque config block
  (lines ~280-332).

### Gap #11 — `ab_approval_task` vs SmartEngine task disconnect

- **Where**: `/api/bpm/process-instances` + ApprovalInbox.
- **Symptom**: `ab_approval_task` (what ApprovalInbox reads) is not populated
  as a side-effect of SmartEngine task creation, so `formPageKey` configured
  on the userTask does not currently bridge into the ApprovalInbox binding.
- **Spec workaround**: `designer-usertask-form.spec.ts` proves the chain
  designer → deploy → runtime task by reading the SmartEngine task list
  directly and asserting the userTask carries the expected `formPageKey`
  metadata (lines ~351-475). The ApprovalInbox-facing half of the binding is
  intentionally left as a product TODO until the bridge exists.

## Relationship to other specs in this directory

The non-tagged specs (e.g. `bpm-lifecycle.spec.ts`, `bpm-designer-ui.spec.ts`,
`task-center.spec.ts`, `sla-monitor-drill.spec.ts`) cover adjacent BPM
surfaces and are **not** part of the Spec 1 aggregation. They run as part of
the broader OSS suite (`bash scripts/oss-test.sh`), not under
`--bpm-regression`.

---
type: system-reference
status: active
---

# Rule Center Endgame Matrix Retro - 2026-07-19

## Scope

This retro covers `codex/decisionops-rc-ux-smoke-stability-20260719`, specifically the Rule Center / Strategy Studio / SLA / BPM / Automation / Permission close-out. It records the failures, fixes, and verification path from this iteration so the same mistakes do not recur.

## Failure Path

1. Focused tests passed, but the merged matrix exposed `Strategy Studio reuses user reference conditions...`.
   - Symptom: the test timed out waiting for `POST /api/decision/definitions/complaint_sla_deadline/versions`.
   - Evidence: the screenshot already showed `草稿已保存 · 主管审批 SLA`.
   - Root cause: when `selectedFragmentCode=null`, Strategy Studio still auto-picked a compatible historical fragment and used `fragment.decisionRefs[0]` to override the current scenario `decisionCode`. A user clicking the SLA scenario could save against a decision inherited from an old fragment.
   - Fix: only an explicitly selected fragment can override the scenario decision; an auto-loaded fragment can provide condition/name context but cannot rewrite the scenario target decision. A unit test now covers auto-loaded stale `decisionRefs`.

2. The second merged matrix exposed `Strategy Studio DMN round-trip preserves fact catalog valueLabels...`.
   - Symptom: after selecting `wd_req_type`, `dt-in-record_data_wd_req_type` did not appear.
   - Evidence: the page snapshot showed the fact catalog contained `record.data.wd_req_type`, the picker had closed, but the DMN input reverted to old `Leave days / data.leaveDays`.
   - Root cause: a late `listVersions` restore response overwrote the local DMN edit.
   - Fix: Strategy Studio now marks each scenario table dirty as soon as the user edits it; late restore responses skip dirty scenario tables. A unit test now covers late restore not overwriting local DMN edits.

3. SLA / Permission matrix stability depended on shared model-state isolation.
   - Risk: Permission ABAC tests mutate `wd_leave_request` field permissions while SLA tests create real leave records.
   - Fix: SLA and Permission specs now use the same file lock, clear stale `fieldPermission`, refresh model cache, and restore permissions in `finally`.

4. Condition fragment v2 impact evidence no longer depends on ambiguous seed labels.
   - Problem: old assertions relied on Manager/HR seed wording.
   - Fix: the E2E dynamically creates a decision and a real SLA consumer, rebuilds the usage index, and asserts impact against that consumer.

## Testing Lessons

- Focused green is insufficient for product chains. Both Strategy Studio failures appeared only in the merged matrix with async state and concurrent suites.
- API waits must follow the real product target. If the UI says saved but the test timed out, inspect target drift and async ordering before increasing timeouts.
- Screenshots are diagnostic evidence, not decoration. The first failure screenshot moved the diagnosis from "save failed" to "waited for the wrong response / decision drifted".
- Avoid `waitForTimeout`, retry, and API PATCH fallbacks. This iteration fixed product state and test setup instead.
- E2E that mutates low-code model permissions, caches, seeds, or usage indexes must explicitly isolate and restore shared state.

## Final Evidence

- Playwright merged matrix: 11 specs / 57 tests, `57 passed (3.0m)`.
- Vitest: 5 files / 105 tests passed.
- TypeScript: `pnpm exec tsc --noEmit --pretty false --incremental false` passed.
- Backend: `DecisionTableDmnXmlServiceImplTest` 6/6 passed.
- e2e-truth static scan: no `skip/fixme/.only/waitForTimeout/retries/PUT/PATCH` fallback; threshold hits were pixel tolerance or real business-count lower bounds.

## Guardrails

- New Strategy Studio / DMN / condition-fragment work must run focused tests and the merged matrix.
- Tests that touch low-code field permissions, caches, seeds, or usage indexes must include isolation and cleanup.
- If UI evidence shows the business action completed, first inspect the test wait condition and product state drift.
- JSONB fields must continue through entity TypeHandlers, BaseMapper, and existing services; do not reintroduce `JdbcTemplate` or handwritten JSONB SQL for these flows.

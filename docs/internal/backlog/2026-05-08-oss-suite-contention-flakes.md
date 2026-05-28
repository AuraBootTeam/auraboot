# OSS Suite — 6 Contention Flakes (Follow-up)

**Status:** open
**Date:** 2026-05-08
**Context:** today's full-suite cleanup landed across `3c5b2211`, `5676224b`, `513f6400`, `a51b69c8` (33 fail → 6 fail). Smoke is 100% green; these 6 are the residual full-suite flakes.

## Pattern

Each of the 6 specs **passes under isolated rerun** (`--workers=1 --retries=0` against the same backend). They fail only inside `./scripts/oss-test.sh` chromium / chromium-deep phases where ~1500 specs run in sequence and shared backend / DB / BFF state varies between specs. None of them showed a deterministic product defect on close reading; all could plausibly be either:

a) **Timeout under load** — assertions polling at default 5s / test budget 15s, while the relevant state propagation (`setSearchParams → useEffect → fetch → re-render`, or `commands/execute` queue drain) takes longer when the BFF queue is hot. Same shape as `unified-inbox` (5s → 15s) and `permission-matrix` (15s → 30s) which already shipped per-spec timeout bumps in `a51b69c8`.

b) **State leaked from a sibling spec** — earlier spec mutated a row, later spec asserts on a clean baseline.

The right fix per spec is most likely (a). Tracker entries below capture what to grep first.

## The 6 specs

| # | Spec:line | Test | Probable timeout knob |
|---|---|---|---|
| 1 | `web-admin/tests/e2e/agent-control-plane/acp-exception-feedback.spec.ts:405` | EXC-07 Edit mission success — toast appears | `commandResponsePromise = page.waitForResponse(/commands\/execute/, { timeout: 15_000 })` — bump to 30s; alternatively assert toast first then response |
| 2 | `web-admin/tests/e2e/aurabot/ai-memory-promotions-real.spec.ts:154` | MP-E2E-03 retract during shadow → PROMOTED_SHADOW → RETRACTED + memory soft-deleted | `expect.poll` waiting on memory list; needs longer poll deadline or use direct API check first |
| 3 | `web-admin/tests/e2e/automation/automation-enhanced.spec.ts:287` | AUTO-04 toggle automation enable/disable | toggle button label flip — likely needs `expect.poll` 15s instead of single assertion |
| 4 | `web-admin/tests/e2e/automation/llm-call-node.spec.ts:729` | LLM-005 Logs dialog shows llm_call + send_notification | `[data-testid=execution-log-dialog]` 8s → 20s; verify dialog is on the right execution row in mid-suite state |
| 5 | `web-admin/tests/e2e/platform/showcase-form-validation.spec.ts:106` | VAL-001 — Create form: submit with empty required field is blocked | the `expect(received).toBe(false)` is asserting the backend command did NOT execute. Under load, the spec saw `true` once. Tighten to `expect.poll` over 1s window with stable assertion, OR ensure the test waits for client-side validation to settle before clicking submit |
| 6 | `web-admin/tests/e2e/agent-control-plane/acp-lifecycle-deep.spec.ts:457` | LIFE-01 Mission active — Pause btn visible | `[data-testid=row-action-pause]` 8s timeout — bump or scope to a freshly-created mission rather than a mid-suite mutated row |

## Recommended approach for follow-up sweep

1. Run `./scripts/oss-test.sh` once on a freshly-reset backend.
2. For each of the 6 that re-fails, capture the exact wall-clock from spec start → assertion (the log already prints per-spec timing).
3. Apply per-spec `test.setTimeout(30_000)` and / or per-assertion `{ timeout: 15_000 }` matching the observed wall-clock + 50% headroom.
4. Verify each fix isolated AND in full-run.

Per AGENTS.md hard rule "禁止 retries:N 兜底" — DO NOT add `retries`. Per-assertion timeout bumps are acceptable when the observed real-world latency genuinely exceeds the default.

## Out of scope

- Group-chat agent-member REST endpoint (`ImConversationController` lacks `agent`-type member add) — separate ticket; the `test.fixme` in `group-chat-agent-reply.spec.ts` references it.
- Backend `flowConfig: {}` vs `null` for legacy automations — minor inconsistency surfaced during the designer cluster fix; non-blocking.

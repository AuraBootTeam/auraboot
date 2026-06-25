---
type: handover
status: active
created: 2026-06-25
---

# Toast Acknowledgement Feedback Closeout

<!-- no-precipitation: This session only closes a narrow toast interaction adjustment. No reusable platform rule or architecture contract was introduced. -->

## Summary

Simplified the global toast behavior for submitted background tasks. The toast now acts as immediate visual acknowledgement only, instead of looking like a long-running backend progress indicator.

The concrete UX decision for BOM submission feedback is:

- Use a short toast for acceptance feedback, such as `提交已受理`.
- Do not use the toast countdown bar as a processing indicator after navigation.
- Keep long-running processing state in the destination page, task status, or refreshable data area if product scope later requires it.

## Changed Files

- `web-admin/app/ui/Toast.tsx`
  - Removed the visible countdown/progress bar.
  - Shortened the default auto-dismiss duration to `2500ms`.
  - Kept manual close and entry/exit animation.
- `web-admin/app/contexts/ToastContext.tsx`
  - Preserved optional toast `duration` from imperative `aura:toast` events.
  - Passed the duration through to the rendered toast.
- `web-admin/app/contexts/__tests__/ToastContext.test.tsx`
  - Added coverage for event-dispatched toasts with a custom duration.
  - Asserted the toast no longer renders the countdown-bar styling.

## Verification

Ran in `/Users/ghj/work/auraboot/.worktrees/toast-ack-feedback/auraboot/web-admin`:

- `pnpm exec vitest run app/contexts/__tests__/ToastContext.test.tsx`
- `pnpm exec eslint app/contexts/ToastContext.tsx app/contexts/__tests__/ToastContext.test.tsx app/ui/Toast.tsx`
- `pnpm exec tsc --noEmit --pretty false`

Runtime reproduction notes:

- Opened the local BOM workbench route and confirmed the target page shape matched the reported screen.
- The screenshot's blue bar was traced to the global toast countdown UI, not real backend progress.
- Browser sandbox limitations prevented directly dispatching a real `CustomEvent` for visual toast injection, so the behavioral guarantee is covered by the React/Vitest test.

## Scope Boundary

This change intentionally does not add a task center, polling banner, route-level pending indicator, or background-job tracking UI. The current product decision is to keep the toast simple and use it only for customer-facing acknowledgement.


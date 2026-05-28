# AuraBot × AI Modeling Deep Integration — Backlog

> Status: **backlog** (not scheduled)
> Logged: 2026-04-14
> Predecessor: 2026-04-14 AuraBot header button (方案 A, shipped) — see session HANDOVER.md

## Problem

Today the AuraBot Panel has a header Sparkles button that **navigates** to `/meta/ai-modeling`. Users leave the chat context to model in a separate page. There is no way to model while staying in the chat.

## Proposed Direction

Convert `web-admin/app/plugins/core-meta/pages/meta/ai-modeling/index.tsx` into a **dual-mode component** that can render as:

1. Standalone page at `/meta/ai-modeling` (current behavior — unchanged)
2. Drawer-embedded inside the AuraBot Panel (new)

In Drawer mode the user:
- Tells AuraBot "帮我建一个订单管理系统"
- AuraBot opens the modeling drawer alongside chat
- Steps through: pick scenario → AI generates Model/Field/Page → preview → confirm import
- Never leaves the chat surface

## Scope (rough)

- Component split: strip page-level layout/breadcrumbs from `ai-modeling/index.tsx`; expose `<AiModelingWorkspace mode="page" | "drawer" />`
- State: share session + LLM stream between AuraBot Panel and Drawer
- UX: Drawer width/collapse/step indicator/error states; chat and drawer must stay visible simultaneously on ≥1280px screens
- E2E: cover standalone page path AND drawer-embedded path (two tests)

## Why deferred

- Medium-large frontend refactor; requires UX mockup review before code
- Current header button (方案 A) already gives a functional entry; the drawer is quality-of-life, not unblocking
- Higher-priority OSS cleanup ongoing

## Exit criteria (when picked up)

1. UX mockup approved by user (drawer layout, step flow, error states)
2. Component refactor lands behind a feature check so /meta/ai-modeling still renders unchanged
3. Both standalone and drawer E2E specs pass
4. Chat + drawer work in parallel (sending a chat message does not dismiss the drawer)

## Related files

- `web-admin/app/plugins/core-meta/pages/meta/ai-modeling/index.tsx`
- `web-admin/app/plugins/core-aurabot/components-shell/AuraBotPanel.tsx`
- `web-admin/app/plugins/core-aurabot/components-shell/` (drawer host location TBD)

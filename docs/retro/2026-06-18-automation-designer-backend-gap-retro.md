---
type: retro
status: closed
created: 2026-06-18
---
<!-- no-precipitation: the two durable lessons are recorded inline in §Durable lessons →
     (1) the FlowDesigner SDK now seeds undo history on mount even for an empty canvas; (2) a flow
     step-debugger must derive its step list from flowConfig, not the flat actions[]. Both are now
     enforced by code + golden tests in THIS repo. Cross-repo precipitation into the enterprise
     engineering-gotchas canonical (a different repo, not writable from this OSS worktree) is a
     tracked follow-up, not part of this OSS delivery. -->

# Automation Designer ↔ Backend gap-closure — completion review + retro (2026-06-18)

/aura-endgame on "分析 automation 设计器与后端联动:测试覆盖度 / UX 交互性(每个组件·属性·行动点·视觉反馈)→ 输出方案+gap → 修复全部 → 报告".
Branch `feat/automation-designer-gap-closure` (off `origin/main` `c23309270`). Isolated host-first
runtime `automation-designer-gap-71` (backend :6471, vite :5171, bff :6171, DB `auraboot_71`).

## Completion review — five items (P5 hard gate)

1. **方向 (direction)** — ✅ aligned. The engagement target was the designer↔backend linkage at the
   granularity of every component/property/action-point/visual-feedback. The work closed the *real*
   linkage gaps (debug-session, duplicate, undo/redo, send-webhook contract) and verified the rest
   already golden. No drift / no invented scope.

2. **进度 (progress)** — ✅ G1–G6 all resolved (see gap tracker
   `docs/backlog/2026-06-18-automation-designer-backend-gap.md` §RESOLUTION). G1 + G6 were phantom
   (re-verified green per §15); G2/G3/G4/G5 fixed with paired browser + backend evidence.

3. **gap (completeness critic)** — the feature was already comprehensively golden (15/15 node types ×
   happy/sad/edge/corner = 30 cases merged; IT 81.3%; all pickers exercised). The genuinely-missing
   surfaces were: the **debugger** (component family + 4 action points + visual states, 0 E2E, and
   broken for designer-built flows), **Duplicate** (dead capability), **Undo/Redo** (no E2E + first-edit
   bug). All now closed. No new gaps surfaced in the regression (see item 5).

4. **UX 交互完整落地 (screenshots)** — ✅ captured during driving:
   - Debugger fully rendered: 已暂停/Paused badge, 0/2 progress, 单步/继续/重新开始/停止 controls,
     2 derived action rows, Variables panel (automationPid/debugMode), Events panel.
   - Step → row shows ✓ (success) / ✗ + "Validation failed…" (real failed-state with error) — proves
     real backend execution + visual feedback.
   - Duplicate row button drives a real clone; Undo/Redo drive real node add/remove with disabled-state
     boundaries.

5. **测试完备性 (test completeness)** — backend `DebugSessionServiceImplTest` 29/29 (27 + 2 new
   derivation tests); shared SDK vitest 305/305 (no regression from the FlowDesigner change); new
   goldens `automation-debug-golden` (G2) + `automation-action-points-golden` (G4/G5) green; `automation-golden`
   send-webhook (G3) green; **full critical regression `automation-designer-golden` (34) + `automation-golden`
   (18) = 72/72 passed, 0 fail / 0 skip, ~4.4m**; bpm-designer smoke (shared FlowDesigner): palette-drag +
   property-edit green. No stub/mock placeholders left to backfill (no LLM-key path touched — the debug
   actions run the real CompositeActionExecutor against the real DB).

> **First regression pass surfaced a 5th, systemic, pre-existing defect (G7)** — see below. It was
> fixed in the same session; the 72/72 above is post-fix.

## Real product defects surfaced + fixed (the golden earned its keep)

1. **Designer-built automations couldn't be debugged** — `DebugSessionServiceImpl.step()` walked the
   flat `actions[]`, which a designer automation leaves empty (its steps live in `flowConfig`,
   compiled to BPMN at enable time). So clicking Debug in the designer showed "0 actions". Fixed:
   `deriveActionsFromFlow` walks the flow graph (BFS from the trigger) into an ordered action list
   when `actions[]` is empty. The debugger steps the designer's flow, executing each node via the same
   `CompositeActionExecutor` the runtime uses.
2. **First edit on a fresh designer canvas was not undoable** — `FlowDesigner` only seeded the undo
   history (`importData`) when `initialData` was truthy; a new `/automation/new` (or any new flow in
   any designer) started at `historyIndex:-1`, so `canUndo()` stayed false after the first edit. Fixed:
   always `importData` on mount (empty default), which also guarantees a clean slate instead of
   inheriting stale nodes from a previously-mounted designer (the store is a singleton). Shared across
   automation + BPM + any FlowDesigner-SDK designer.
3. **Duplicate was a dead capability** — `automationService.duplicate()` + `POST /{pid}/duplicate`
   existed but had no UI affordance. Wired a list-row Duplicate button.
4. **A stale send-webhook test** asserted "completed" against the removed eventType→dispatch path
   (`SendWebhookExecutor` now requires `url`, FINDING-10). Corrected to assert the real contract.
5. **(G7) All 5 inbound-webhook golden tests were silently broken by PR #557** (`feat: JWT-exempt
   webhook endpoint with fail-closed validation`, merged AFTER the golden suite). #557 made the
   endpoint refuse `validationMode:'none'` webhooks, but `N-TRIGGER-WEBHOOK / N-START-PROCESS / N-LOOP
   / N-LOOP-EDGE / N-DELAY` (designer-golden) + `trigger-webhook` (golden) all still configured
   `mode=none` and expected acceptance. They never failed loudly because the serial describe aborts at
   the first failure (the rest show as "did not run") and the automation golden suite isn't in a CI
   gate (Actions billing off). Fixed: a shared `configureWebhookToken` + `fireInboundWebhook` helper
   (token validation via the real property panel + `X-Webhook-Token`). This is the canonical hazard of
   a security-hardening PR landing without re-running the (un-gated) golden that exercises the path.

## Session detour / rework log

- **Two phantom gaps caught by §15 re-verification** (would have been wasted work / false report):
  the inherited "DebugSessionServiceImplTest 3 red" was already green; the Explore sweep's "no E2E for
  scheduled/execute-command/send-webhook/start-process" was false (all present in designer-golden).
- **Infra bring-up detours**: (a) per-runtime m2 lacked the local-only SmartEngine 4.0.2 artifacts →
  seeded from `~/.m2` (wrong level first: `/repository` vs the `maven.repo.local` root); (b) the
  reset-and-init 120s backend-wait is shorter than a cold boot — not a failure.
- **`cmd | tail` exit-code masking** bit once (gradle BUILD FAILED reported as exit 0 through a pipe) —
  switched to redirect + explicit `$?` + test-results XML.
- **Playwright oss config**: a directory positional arg runs the whole 1736-test suite (no filter);
  explicit spec-file args filter correctly; a shell-variable list of files matched nothing.
- **G2 golden test bugs (not product bugs)**: SSR hydration race (click before onClick attached →
  retry-click toPass); English panel-header assertion vs zh-CN UI (locale-robust regex); missing
  required `e2et_order_status` field (added `'draft'`); Stop exits debug mode (assert editor restored,
  not a "stopped" badge).

## Root-cause four-classification (per /handover Step 3 vocabulary)

- **A 门禁质量 (gate quality)**: the stale send-webhook test + the debugger gap survived because the
  merged golden gate never ran the debugger UI nor a designer-flow debug, and the send-webhook Layer-B
  test was "masked as did-not-run". Improvement: the new goldens close those; debugger now has E2E.
- **B 输入信息不足 (input gaps)**: handover docs marked DebugSessionServiceImplTest "3 red" — stale by
  the time of this session (fixed when the tenant guard landed). §15 re-verification is the guard;
  it held.
- **C 提示词/编排 (orchestration)**: the Explore sweep over-counted tests and emitted phantom E2E gaps
  — mitigated by independent `grep -c` and reading the actual spec titles before trusting counts.
- **D 验证纪律 (verification discipline)**: held — every "done" claim is backed by a re-run with the
  authoritative artifact (test-results XML / Playwright pass line / screenshots), inherited "red" and
  "missing" claims were both re-verified before acting.

## Durable lessons → codify (precipitation)

- **The FlowDesigner SDK now seeds undo history on mount even for an empty canvas** — first-edit-undo
  works in every designer (automation/BPM/etc.). (engineering-gotchas/frontend-ssr-build candidate.)
- **A step-debugger over a visual flow must derive its step list from the flow graph, not a flat
  actions[]** — otherwise designer-built flows debug as "0 actions". (engineering-gotchas/backend.)
- **Confirmed reinforcement** of existing canonical: §15 phantom-gap discipline (2 caught here);
  `cmd | tail` exit-code masking; oss playwright config positional-arg filtering quirk.

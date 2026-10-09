---
type: handover
status: closed
created: 2026-05-30
archived: 2026-10-09
source_stash: 0499dc7e20d208f3c190ed0167baba4ce2bf7ba3
source_sha256: bdb92bcbe37ecaf38a5c738aeef440e1a1491f7ea731745b4b47896ff699fb8e
---

<!-- no-precipitation: Recovered historical session snapshot; current canonical contracts and active BPM work are unchanged. -->

> 历史快照：下文保留 2026 年 5 月的原始记录，其状态、百分比、规则编号、环境和命令不能作为当前执行授权或验收结论。当前工作遵循 canonical AGENTS.md 与现行 SoT。

# Session Handover — 2026-05-29/30 设计器 T4 收口 + 黄金 E2E + Reliability spike

## Session Summary

延续 2026-05-28 设计器 T4 BPMN→SDK 统一系列。本会话 user 提的 4 问题:**B2d cutover / B1 真黄金 E2E / Automation gap 盘点 / P3 Popover hook**。3 全交付 + 1 (B2d) honest defer 留独立 session。期间踩坑两次 §15 verify-before-claim(主对话写 backlog 时 grep 范围漏 + 把 transient DOM 当稳态),已诚实记录入 PR commit message 公开;新升 1 条 canonical(smoke-first ENT #204)。Reliability 痛 1 完成 production-grade design doc(7 工作日 M1+M2+M3)等 owner sign-off。

## 本会话 5 PR 全 MERGED

| PR | SHA | 内容 |
|----|-----|------|
| [OSS #365](https://github.com/AuraBootTeam/auraboot/pull/365) | `c5bd67c5e` | bpm smoke 假阳性 close-out + P3 Popover v1 backlog |
| [OSS #367](https://github.com/AuraBootTeam/auraboot/pull/367) | `b4ed15379` | P3 Popover hook v2 真根因(vite-plugin-federation × resolve.dedupe 缺失 × popover 未 pre-bundle)— 留 owner runtime 验证 |
| [OSS #369](https://github.com/AuraBootTeam/auraboot/pull/369) | `733b36fc1` | **B1 真黄金 E2E**:`automation-golden.spec.ts` 345 行 / 3 runs × 0 fail / 0 假通过 / 揭 2 product gotcha |
| [ENT #204](https://github.com/AuraBootTeam/auraboot-enterprise/pull/204) | `c31afb03a` | **Smoke-first 纪律 canonical** — 跑任何 deep/golden/full E2E 前必须先跑 smoke spec |
| [ENT #218](https://github.com/AuraBootTeam/auraboot-enterprise/pull/218) | `6028b85ff` | **Reliability 痛 1 design doc** 453 行 / M1+M2+M3 = 7 工作日 production-ready 切片 / 10 decision points + 10 open questions |

## 4 个原始问题状态

| # | 问题 | 状态 | 详情 |
|---|------|------|------|
| 1 | B2d cutover(BPMN 用户层切 SDK)| ⏸ **HONEST DEFER** | mega subagent 实地调研后判断:`BPMNDesigner.tsx` 631 LOC 含 BPMN-specific UI(process name/key/metadata / aura policy / version banner / monitor input / save dialog),真 cutover ~1379 LOC 入口重写,需独立 session + 独立 stack + 独立 PR + bpm-workflow.spec.ts × 3 闸门。**store-level cutover 已在 B2c phase 2 完成**(BPMNDesigner.tsx:16 已 import useBpmFlowStore as useBPMNStore)。task #12 重新立项 |
| 2 | B1 真黄金 E2E | ✅ DONE | OSS #369 — full user flow(API create automation → enable → 浏览器导航 react-flow viewport 真渲染 → 真 fire record → 真 poll logs → strict assert status='success' + 无 failed/pending)3 runs 全绿 |
| 3 | Automation gap 清单 | ✅ DELIVERED | 主对话 7 类 30+ 项矩阵 + Reliability 痛 1 完整 design doc(ENT #218)— 痛 2/3/4 在矩阵但未深 spike |
| 4 | P3 Popover hook | ✅ v2 backlog MERGED | OSS #367 — 真根因找到,修是基础设施层动作(vite.config.ts dedupe + optimizeDeps)需 dev-mode runtime 验证,留 owner |

## Automation Gap 矩阵 — 7 类 30+ 项

(详见会话内主对话 spike 输出,已在 ENT #218 design doc 引用)

**最痛 4 项**(按优先级):
1. **Reliability** — retry / DLQ / 幂等 / error-handler 全缺(**痛 1,已 design 完,等 sign-off**)
2. **Wait/Delay 节点编译挂起** — T2 已知 limitation,需 SmartEngine timer 升级
3. **Dry-run / step debugger / mock 输入** — 0,designer UX 大缺口
4. **第三方 connector 生态** — 0

## Reliability 痛 1 — 3 个 game-changer 发现

(spike 主对话第一次以为是发明,实际是 wire-up + 修隐藏 bug)

1. **`AutomationAction.java:59-83` `RetryConfig` schema 已存在但 0 consumers**(dead code)→ M1 是 wire-up,不是发明
2. **`DebugSessionServiceImpl.java:170,243` 真消费 `continueOnError` 但 production 路径 `AutomationActionServiceTaskDelegate` 不消费** → 隐藏行为漂移 bug,M1 顺手修
3. **SmartEngine 自带 `smart-engine-extension-retry-{common,custom,mysql}` jar 在 m2 仓** 但 unwired,粒度 instance 级不匹配 action 级 → M1 不启用,保留 option

## B1 真黄金 E2E 揭出的 2 个 product contract gotcha

(`automation-golden.spec.ts` 注释里有,正面遵守 — 但都是 silent failure mode)

1. **`operationType` 必须 in command body** — 否则 `CompletionPhase` 发 `"unknown"` → `AutomationCommandEventBridge` 静默 filter(只接 create|update|state_transition) → automation 静默不触发(无 log,无 error)→ task #26 P2 backlog
2. **`flowConfig.nodes` 必须非空** — 否则 `AutomationServiceImpl.enable` 不调 `automationProcessRuntime.deploy` → 后续 run 报 `Process definition version not found for id: auto_<pid>` → task #26 P2 backlog

## 本会话 §15 verify-before-claim 双倍反面教材(自我诚实记录)

主对话(我)两次违规,subagent 救场:

1. **昨天写 P2 backlog**:`grep -rln "bpm_process_management"` 范围只 `core-bpm/`,漏 `platform-admin/` → 错误结论"page DSL 不存在"。修 P2 任务 subagent verify-before-claim 揪出 platform-admin 真有 1224 行完整 DSL。
2. **今天静态推论**:把 smoke fail 时 `main [ref=e696]` 完全空的 DOM snapshot 当稳态结论 → 写成 "candidate 4 = backend 返回 schema.blocks=[]"。诊断 subagent 真浏览器实测发现 main 真渲染 23377 bytes 含 2 行真数据,只是 P3 Popover hook 导致偶发 transient empty。

→ **§15 应扩到主对话 + backlog 写作 + 静态推论**(待升 canonical)。

## 5 条 canonical 升级累计(2 天)

| 升级 | 触发 | 文件 |
|------|------|------|
| Worktree count threshold N≥6/N≥8 | 2026-05-28 11 worktree 失控 | `agent-rules/multi-worktree-isolation.md` |
| Stacked PR depth ≤4 | 2026-05-28 5 层 stack rebase 反超原任务 | `agent-rules/git-workflow.md` |
| Subagent commit 协议 + 架构决策回路 + 主对话 preflight | 2026-05-28 3 subagent 没 commit + Option A→hybrid 翻盘越权 + B1 host stack 没 preflight 浪费 turn | `standards/core/agent-collaboration.md` |
| Handover 命名 date+slug | 2026-05-29 静态 HANDOVER.md 会覆盖 | `agent-rules/session-and-doc-workflow.md` + skill |
| **Smoke-first 纪律** | 2026-05-29 env var 名错跑全套 274 假阴性 | `agent-rules/oss-e2e-and-playwright.md` |

## Current State

### Git Status(主 worktree `/Users/ghj/work/auraboot/auraboot`)

```
M platform/build.gradle
M web-admin/app/plugins/core-dashboard/widgets/workbench/InboxWidget.tsx
M web-admin/tests/e2e/workbench/workbench-redesign.spec.ts
?? docs/handover/  (本文档新加)
?? platform/src/main/resources/db/
?? scripts/db/
```

**注**:M / ?? 都是 user 自己的工作(workbench redesign / db / scripts),非本会话产出,**不要动**。本文档是新增。

### Main HEAD (origin)

OSS: `733b36fc1` (B1 #369 latest) + downstream possibly more by other contributors
ENT: 含 #204 #218 + downstream

### Worktree 列表

4 user-owned 长跑 worktree + main:零本会话残留(全部 MERGED_AND_DELETED)

### Disk

最近实测 18-59 GB free 区间。本会话主对话主动清过 15GB(builder + image + volume + test-results)+ tear down 全部 isolated stack。

### 测试 baseline(下次 session 不应低于)

- Java unit + IT: 32/32(automation + tsport 包)
- 全套 vitest: 1619/1619
- bpm-workflow.spec.ts: 24 pass / 2 skip / 0 fail × 多次(注:基础 #347 是 25/1/0,中间 main 演化掉了 1 个 pass 进 skip,非我引入)
- automation-golden.spec.ts: 1 pass × 3 runs / 0 fail(本 PR #369 baseline)

## Next Steps(优先级排序,待 owner / 下次 session)

### 立即(等 owner sign-off 即可启)

1. **Reliability M1 实现**(3 天)— blocked by ENT #218 D1-D10 + Q1-Q10 共 20 个 owner decision points sign-off。task #23
2. **#26 P2 silent filter fix**(small,~50-100 LOC)— operationType silent filter + flowConfig empty 静默无 deploy 改 fail-fast + 友好 error。owner 通常会接受 silent→fail-fast

### 需独立 session

3. **B2d mount-side cutover**(~1379 LOC,4-8 hr)— BPMNDesigner.tsx 入口重写到 `<FlowDesigner>` slot pattern 或保留 wrapper。独立 stack + 独立 PR + bpm-workflow × 3 baseline + cutover-after × 3 ≥ baseline。task #12

### 后续(M1 之后链式)

4. **Reliability M2**(2d)— idempotency + replay + UI。task #24
5. **Reliability M3**(2d)— onError 节点 + DSL 暴露。task #25

### 待 owner / 独立验证

6. **P3 Popover hook fix**(vite.config.ts dedupe + optimizeDeps)— ENT #367 v2 backlog 已给 Option A,需 dev-mode 真浏览器 console 验证。task #21
7. **§15 扩展到主对话+backlog+静态推论** — canonical 待升

## Context for Next Session

### 必读(下次 session 开局)

1. 本 handover(`docs/handover/HANDOVER-2026-05-30-t4-closure-golden-e2e-reliability-spike.md`)
2. Reliability design `auraboot-enterprise/docs/plans/2026-05-29-automation-reliability-design.md`(M1/M2/M3 路线)
3. B2d 5 步设计 — B2b3 PR #325 报告 §9 + B2c followup PR #332 报告 §6
4. Memory active-work 2 条:
   - `project_designer_t4_unification_2026_05_28.md`(T4 系列 19 PR 完整账)
   - 本 session 主线归入同一项目

### 5 升 canonical 提醒下次开局对照

- N≥6 worktree → ban full-stack docker stack
- Stacked draft ≥4 → 强制 pause
- Subagent 默认 commit / 架构决策回主对话/owner / 主对话 60s preflight
- Handover 命名必 date+slug
- **任何 E2E 前必须先 smoke**(本会话刚升)

### 关键 caveat

- `start-isolated.sh` slug 算法用 worktree dir 名,**不是** `COMPOSE_PROJECT_NAME`(踩过 3 次)
- env 变量名 `PLAYWRIGHT_BASE_URL` 不是 `E2E_BASE_URL`
- bpm-smoke `wf-end-to-end-smoke.spec.ts` 偶发 transient main 空 — 真根因 P3 Popover hook,known-flake,不阻塞;真 BPMN gate 用 `bpm-workflow.spec.ts`(24 pass / 2 skip / 0 fail 稳)
- B2d cutover 不要捆绑 B1 黄金 / 其他任务,**独立 session 独立 PR**

### 不要做(已警告)

- 在已 honest defer 的 B2d 上自动 dispatch subagent("继续" 不等于 force B2d)
- 没 owner sign-off 启 Reliability M1(跳架构层决策回路)
- 直接改 vite.config.ts 修 P3(架构层动作需 runtime 验证)
- 触动 user 当前 ?? / M 文件(workbench redesign / db / scripts 等 user 自己的工作)

## 元决策

按 AGENTS.md §19 `敢说够了` 本会话主动 STOP,user 已确认。下次有新方向请明说,我从干净状态起手。

按 §15(扩展版本)— 本 handover 所有事实都附 PR / SHA / commit message / file:line 证据。我的 2 次违规已在 PR #365 + 本文档诚实记录。

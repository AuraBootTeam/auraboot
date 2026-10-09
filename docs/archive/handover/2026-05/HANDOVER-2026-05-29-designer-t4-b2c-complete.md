---
type: handover
status: closed
created: 2026-05-29
archived: 2026-10-09
source_stash: 0499dc7e20d208f3c190ed0167baba4ce2bf7ba3
source_sha256: 04196ed37ea1fef0c59200c1ee6b371d0db2ae34d50447b1d4a3886c8c6e105e
---

<!-- no-precipitation: Recovered historical session snapshot; current canonical contracts and active BPM work are unchanged. -->

> 历史快照：下文保留 2026 年 5 月的原始记录，其状态、百分比、规则编号、环境和命令不能作为当前执行授权或验收结论。当前工作遵循 canonical AGENTS.md 与现行 SoT。

# Session Handover — 2026-05-28 → 2026-05-29 设计器 T4 BPMN→SDK 统一系列

## Session Summary

回答用户问题"automation/bpmn 设计器架构统一了吗?"演化成多日 16 PR 大批次,**B2c COMPLETE 100%**(BPMN 9 节点 + 14 编辑器 + 2 picker + adapter + 全消费方迁完);剩 **B2d.2 mount-side cutover (~1379 LOC,~4 hr)** 留独立 session,因为是架构层决策 + 当前磁盘 96% / 长会话 8 worktree 已触边际收益临界点(AGENTS.md §19「敢说够了」)。

## Tasks Completed

### 主线 (16 OSS PR + 1 ENT PR 全 admin-squash MERGED)

- [x] **A1** PR #315 — flow-designer-sdk JSON Schema validator + CLI lint
- [x] **A2** PR #318 — G5 nodeStatus runtime overlay 后端(`ab_automation_node_execution` 表 + REST endpoint + AutomationActionServiceTaskDelegate 记状态)
- [x] **A3** PR #316 — BPM-shaped smoke PoC + T4 可行性报告
- [x] **B2a** PR #317 — SDK G7 useNodeNeighbors + G8 useNodeMonitorStatus
- [x] **B1-fix** PR #319 — automation-deep.spec.ts:222 no-op honest skip
- [x] **#9 fix** PR #321 — useFlowStore.addEdge id 碰撞(Date.now() 加 random)
- [x] **B2b1** PR #320 — 4 BPMN 节点端口
- [x] **B2b2** PR #323 — 4 节点 + 5 编辑器(G7 useNodeNeighbors 真实战)
- [x] **B2b3** PR #325 — CallActivity + 2 picker (真接 4 endpoint 零 mock) + shared.tsx 627 LOC 拆 3 SDK section
- [x] **B2c phase1** PR #327 — SDK 加 sub-flow drilldown + currentMonitorNodeIds selector
- [x] **B2c phase2** PR #332 — useBpmFlowStore adapter (hybrid A/B,useSyncExternalStore 双 store + .getState/.setState/.subscribe shim)
- [x] **B2c phase3 batch1** PR #342 — 3 消费方迁(useNodeMonitorStatus / UserTaskNode / ProcessStatusViewer)
- [x] **#344 P1 平台 bug** — Spring 6 ctor 陷阱根治(`@Autowired(required=false)` 单 ctor 失效 → Optional<TimeSeriesPort>)
- [x] **B2c phase3 batch2** PR #347 — 4 消费方迁(BPMNDesigner/Canvas/Toolbar/PropertyPanel)+ 2 adapter 真 bug 修(TS overload + microtask)+ **3 次 25/0 bpm-workflow.spec.ts 真验 #344 解锁**
- [x] **ENT #182** — 3 canonical 升级 + 会话反思 backlog

### Canonical 升级(2026-05-28 升)

- [x] `auraboot-enterprise/docs/agent-rules/multi-worktree-isolation.md` §「Worktree count safety threshold」(N≥6 ban full-stack docker / N≥8 暂停新 worktree / 节点 modules sibling symlink)
- [x] `auraboot-enterprise/docs/agent-rules/git-workflow.md` §「Stacked PR depth limit」(≥4 强制 pause + ladder merge)
- [x] `auraboot-enterprise/docs/standards/core/agent-collaboration.md` §「Subagent commit 协议」+「架构层决策回路」+「派 subagent 前主对话 preflight」

## Tasks In Progress

无 — B2c COMPLETE 100% 是天然停止点。

## Tasks Deferred (留下次 session)

### B2d.2 mount-side cutover (~1379 LOC, ~4 hr 估算)

把 BPMNDesigner.tsx 入口切到 `<FlowDesigner config={...} initialData={...} onSave={...} monitorMode={...} monitorData={...}>`,涉及:
- versioning panel 翻译
- monitor mode 接 G8 / nodeStatuses prop
- test-hooks bridge (`window.__bpmnDesignerStore`)
- sub-flow drilldown 接 SDK pushSubFlow/popSubFlow
- palette port 到 SDK NodeRegistry / `registerBpmSdkAll`

详细 5 步设计见 B2b3 报告 §9 (PR #325 内) + B2c batch2 报告 §6 (PR #347 内)。

### B2d.5 删 legacy (cutover 后)

- 删 `auraboot/web-admin/app/plugins/core-designer/components/bpmn-designer/` 整目录 (useBPMNStore.ts 707 LOC + 9 节点 + 14 编辑器旧版)
- 删 `auraboot/web-admin/app/plugins/core-designer/components/bpm-smoke/` (A3 PoC 一次性遗产)
- **依赖**:cutover 完成 + bpm-workflow.spec.ts 真绿 + adapter 内部从 bpmn-designer/types|constants 迁出

### B1 真黄金 E2E

`automation-golden.spec.ts` — 画图→保存→trigger fire→断言 nodeStatus + 副作用。B2d 同 session 顺手做,起 1 次 isolated stack 跑两类 spec。

## Key Decisions

| Decision | Chosen Approach | Rationale | Alternatives Considered |
|----------|----------------|-----------|------------------------|
| Designer 收敛模式 | DDR-2026-05-23 Option B (前端收敛到 flow-designer-sdk) | 9 节点 / 14 编辑器 JSON 文法 ~95% 相同,统一比双轨低维护成本 | Option A: thin-core 并存(浪费 SDK 已有底子) |
| BPMN→SDK 端口策略 | Double-write (port 进 bpm-designer-sdk/ + 老 bpmn-designer/ 不动) | E2E gate 通过前回滚成本 0 | 直接覆盖(回滚成本高) |
| 节点 port 顺序 | Tier 1 drop-in 最简(startEvent/endEvent/parallelGw/serviceTask)优先 | 证模式可行 + 减早期 SDK gap 暴露 | 按字母序 / 按 LOC |
| shared.tsx (627 LOC) 处理 | 拆 3 SDK section(MultiInstanceSection / FormBindingSection / HookConfigSection) | 各 section 单一职责 + G2 patch contract 直接消费 | 整体 port(630 LOC 单文件不利复用) |
| useBPMNStore adapter 模式 | Hybrid A/B(useSyncExternalStore 双 store + .getState/.setState/.subscribe shim) | 纯 Option A getter 不订阅 render + immer middleware 难复合 + setState 模式不适应 | 纯 Option A (composite zustand delegate) |
| TimeSeriesPort 修复 | `Optional<TimeSeriesPort>` + `port.orElse(null)` | Spring 6 canonical 解(@Autowired(required=false) 单 ctor 已知陷阱) | `@ConditionalOnBean` controller(scope 大)/ no-op default(改 platform 契约) |
| B2d cutover 是否本会话做 | 不做 — 留独立 session(架构层决策) | ~1379 LOC + 96% disk + 长会话 + 守新升 agent-collaboration.md §「架构层决策回路」 | 一气呵成(回归风险高,subagent 主动 checkpoint) |
| Stop 时机 | B2c COMPLETE 100% 后停 | AGENTS.md §19「敢说够了」条 (1) 原始 ask 已完成 + (3) 边际收益 < 回归风险 | 继续做 B2d (subagent 越权 + 违反刚升 canonical) |

## Files Changed (本次 session 续做范围, 2026-05-29 part)

### Frontend / SDK
- `web-admin/app/plugins/core-designer/components/bpm-designer-sdk/store/useBpmFlowStore.ts` — adapter TS overload 顺序修(generic 后置)
- `web-admin/app/plugins/core-designer/components/bpmn-designer/hooks/useNodeMonitorStatus.ts` — useBpmFlowStore 迁
- `web-admin/app/plugins/core-designer/components/bpmn-designer/components/nodes/UserTaskNode.tsx` — useBpmFlowStore 迁
- `web-admin/app/plugins/core-designer/components/bpmn-designer/components/ProcessStatusViewer.tsx` — useBpmFlowStore .setState shim 迁
- `web-admin/app/plugins/core-designer/components/bpmn-designer/components/BPMNDesigner.tsx` — useBpmFlowStore 迁
- `web-admin/app/plugins/core-designer/components/bpmn-designer/components/BPMNCanvas.tsx` — useBpmFlowStore 迁
- `web-admin/app/plugins/core-designer/components/bpmn-designer/components/BPMNPropertyPanel.tsx` — useBpmFlowStore 迁
- `web-admin/app/plugins/core-designer/components/bpmn-designer/components/BPMNToolbar.tsx` — useBpmFlowStore 迁
- `web-admin/app/plugins/core-designer/components/bpmn-designer/components/__tests__/BPMNToolbar.test.tsx` — `await act + __flush()` 适配 adapter microtask

### Backend
- `platform/src/main/java/com/auraboot/framework/iot/tsport/controller/TimeSeriesQueryService.java` — 构造器 `@Autowired(required=false) Foo(Bar)` → `Foo(Optional<Bar>)` + `port.orElse(null)`
- `platform/src/test/java/com/auraboot/framework/iot/tsport/controller/TimeSeriesQueryServiceTest.java` — 用 Optional.of/empty
- `platform/src/test/java/com/auraboot/framework/iot/tsport/controller/TimeSeriesQueryControllerTest.java` — 同上
- `platform/src/test/java/com/auraboot/framework/iot/tsport/controller/TimeSeriesQueryControllerIT.java` — 同上

### Docs / Backlog (本会话续做产出)
- `auraboot/docs/backlog/2026-05-29-oss-isolated-stack-timeseriesport-bean-missing.md` — P1 平台 bug 文档化(被 #344 解决,记录在案给后人)
- `auraboot/docs/backlog/2026-05-29-B2c-batch2-complete-B2d-pending.md` — B2c batch2 完成 + B2d cutover 5 步详细 handover

## Pitfalls & Workarounds

1. **Spring 6 single autowire-marked ctor flagged as optional → effectively required**
   - **Root Cause**: Spring 6 / Boot 3 已知陷阱 — `@Autowired(required=false)` 在唯一构造器上不生效,Spring 仍当 required 对待。Error: `Inconsistent constructor declaration on bean ... single autowire-marked constructor flagged as optional - this constructor is effectively required since there is no default constructor to fall back to`
   - **Solution**: Canonical fix = `Optional<T>` 或 `ObjectProvider<T>` 注入,内部 `port.orElse(null)`
   - **Prevention**: 全仓 grep `@Autowired(required = false)` 单 ctor 模式(可能批量隐患,值得升 engineering-gotchas.md canonical)

2. **OSS isolated stack 缺 TimeSeriesPort bean → backend 启动 fail → 所有 OSS E2E 阻塞**
   - **Root Cause**: 上述 Spring 6 陷阱 + #335 IoT TimeSeriesController 落 main 时 introduced
   - **Solution**: PR #344(Optional fix)+ PR #346 平行修(`@ConditionalOnMissingBean` no-op default,not the path we took but the upstream owner's alternative)
   - **Prevention**: 任何 OSS plugin-provided SPI 注入点新增,**必须本地起 isolated stack 跑 actuator/health 验证**

3. **useBpmFlowStore adapter 真 bug — interface 重载顺序导致 TS2339**
   - **Root Cause**: `interface UseBpmFlowStore { <T>(selector?) => T; () => BpmFlowState }` generic 在前,destructure `const { ... } = useBPMNStore()` 推断 unknown
   - **Solution**: 重排顺序 — 无参 overload 在前
   - **Prevention**: Adapter 类 hook 必须先有"无 selector"消费方真 import 跑 tsc,光 unit test 不能验

4. **useBpmFlowStore adapter microtask deferral 暴露给 test**
   - **Root Cause**: `subscribeBoth` 用 `queueMicrotask` 异步,legacy zustand 是同步;test 同步断言 setState 后立即 query state 拿不到更新
   - **Solution**: BPMNToolbar.test.tsx `await act(async => { ...; await useBPMNStore.__flush(); })` 用 adapter 已 export 的 `__flush` helper
   - **Prevention**: Adapter 文档显式说明 sync vs async 语义差异 + test 必须用 __flush

5. **Docker registry timeout 阻 isolated stack --rebuild**
   - **Root Cause**: `registry-1.docker.io` proxy 间歇性不可达
   - **Solution**: subagent 用 `docker cp` 把新 bootJar 注入 cached image 验证 bean(verified fix at boot-level,但 E2E 走 happy seed path 没跑);final consolidation 会话 registry 恢复后 --rebuild 成功
   - **Prevention**: 如果 --rebuild 失败但有可用 cached image,优先 docker cp 注 jar 验; full E2E 必须 retry --rebuild 直到 registry 通

6. **磁盘 97% 时 docker daemon 风险**
   - **Root Cause**: memory canonical 已记 page-golden 战经验
   - **Solution**: 本会话主动清 15GB(builder + image + volume + test-results)安全清(零 user 容器误删)
   - **Prevention**: AGENTS.md §11 worktree 红线 + 新升 multi-worktree-isolation.md §「Worktree count safety threshold」N≥6/N≥8 规则

## Lessons Learned

(已升 canonical,主要在 ENT PR #182)

1. **Subagent commit 协议** — "禁止 stage/push/PR" 三禁过头会被误读为"不动 git";新 canonical 明确"默认 commit,不 push 不 PR"
2. **架构层决策回路** — store wrapping pattern / contract / framework lib 这类决策,subagent 报告 + 推荐,主对话/owner 拍板,不直接落地翻盘
3. **派 subagent 前 60s preflight** — curl / ls / lsof / git status 这种廉价的别让 subagent 浪费 turn
4. **Worktree count threshold** — N≥6 ban full-stack docker, N≥8 暂停新 worktree(本会话踩过 11)
5. **Stacked PR depth ≤4** — 超过强制 pause merge cycle 释压(本系列踩过 5 层 #320→#323→#325→#327→#332)
6. **§19「敢说够了」** — 用户连续"继续"≠必须找事做;原始 ask 完成 + 边际收益 < 风险时主动 stop
7. **Spring 6 ctor 陷阱** — `@Autowired(required=false)` 单 ctor 失效,canonical fix `Optional<T>`(值得后续升 engineering-gotchas.md)

## Current State

### Git Status (主 worktree /Users/ghj/work/auraboot/auraboot)

```
## main...origin/main [behind 7]
 M platform/src/main/resources/database/schema.sql
 M web-admin/tests/storage/operator.json
 M web-admin/tests/storage/viewer.json
?? docs/superpowers/plans/2026-05-28-workbench-redesign.md
?? docs/superpowers/specs/2026-05-28-workbench-redesign-design.md
?? docs/handover/HANDOVER.md  (本文档)
```

**注**:M / ?? 都是 user 自己的工作(workbench redesign 等),非本会话产出,**不要动**。

### Main HEAD (origin)

```
e6ef2cd2b fix(iot): make TimeSeriesQueryService startable without TSDB plugin (closes #339) (#346)
af3813efe feat(bpm-designer-sdk): B2c phase3 batch2 — migrate remaining 4 consumers + verify #344 E2E unblock (#347)
5410d21b5 feat(workbench): redesign Workbench home + top bar polish (#336)
5e6fff2a7 fix(web-admin): make login page full-bleed on wide viewports (#345)
47d456b56 fix(iot): use Optional<TimeSeriesPort> to unblock OSS isolated stack (#344)
```

**注**:#346 是 user/owner 平行修了 #339 的 TSDB no-op default 路径(`@ConditionalOnMissingBean`)。与 #344 互补不冲突 — 两层防御。

### Worktree 列表

6 worktrees 全是 user 长跑(0 我残留):
- /Users/ghj/work/auraboot/auraboot (main)
- /Users/ghj/work/auraboot-worktrees/ida-smoke-gate
- /Users/ghj/work/auraboot-worktrees/ios-g2-im-backend
- /Users/ghj/work/auraboot-worktrees/ios-g3-im-backend
- /Users/ghj/work/auraboot-worktrees/login-fullbleed-2026-05-29
- /Users/ghj/work/auraboot-wt/workbench-redesign
- /Users/ghj/work/auraboot/auraboot-wt/acp-form-crud-required-empty

### Disk State

```
/dev/disk3s5   460Gi   394Gi    18Gi    96%
```

会话开始 14GB → 中间清 15GB → 多次 isolated stack 启停后回收到 18GB。**接近 §11 阈值,下次 session 起 isolated stack 前先 cleanup**。

### Docker State

16 user 活跃容器(bom-v02 / bom-mvp / crawler / dslval / goods-ticket-mobile / bugfix-daily 等),零本会话残留。

### Vitest Baseline

1619/1619 pass (223 files) — **B2c COMPLETE 后的 baseline**。下次 session 跑 vitest 不应低于此。

### bpm-workflow.spec.ts Baseline

25 pass / 1 skip / 0 fail × 3 次稳定性(本会话最终验证)— **B2d cutover 前后必须保持此基线**。

## Next Steps

### 立即(若用户要继续 designer 系列)

1. **B2d cutover 独立 session** — 起干净 isolated stack + 翻译 ~1379 LOC + 跑 bpm-workflow.spec.ts 真闸门 + 删 legacy。预计 4 hr。
2. **B1 真黄金 E2E** — `automation-golden.spec.ts`,与 B2d 同 session 顺手做。

### 平行(独立 session)

3. **engineering-gotchas.md 升 Spring 6 ctor 陷阱** — 全仓 grep `@Autowired(required = false)` 单 ctor 模式,可能批量隐患
4. **review #346 vs #344** — owner 已用 `@ConditionalOnMissingBean` 加 no-op default(双层防御),可能反思 Optional fix 是否还需要 / 或两者并存
5. **PR #182 review 反馈** — 看 owner 是否对 3 canonical 升级有补充

### 不要做(已警告)

- ❌ 现在 dispatch B2d cutover subagent (§19 边际收益 / 回归风险临界)
- ❌ 改 user 当前 M / ?? 文件(workbench redesign 等是 user 自己的工作)
- ❌ 起 isolated stack 不先 cleanup(disk 96%)
- ❌ N≥8 worktree 不暂停新 worktree(新升 canonical)

## Context for Next Session

### Project Root
`/Users/ghj/work/auraboot/auraboot`(OSS) + `/Users/ghj/work/auraboot/auraboot-enterprise`(ENT)

### Key Files for B2d Cutover

- 入口: `auraboot/web-admin/app/plugins/core-designer/components/bpmn-designer/components/BPMNDesigner.tsx`
- 目标 prop shape: `auraboot/web-admin/app/plugins/core-designer/components/flow-designer-sdk/core/FlowDesigner.tsx` (`FlowDesignerProps`)
- 待删 legacy: `auraboot/web-admin/app/plugins/core-designer/components/bpmn-designer/` 整目录
- 一次性遗产: `auraboot/web-admin/app/plugins/core-designer/components/bpm-smoke/`
- Adapter 仍在用 (待迁): `bpmn-designer/types` + `constants` 被 `bpm-designer-sdk/store/useBpmFlowStore.ts` import

### Cutover 5 步详细 (来自 B2b3 PR #325 报告 §9)

1. **B2d.1 edge registration** — `EdgeRegistry` 注册 BPMN 自定义边(sequenceFlow / messageFlow)
2. **B2d.2 mount-side cutover** — BPMNDesigner.tsx 入口切到 `<FlowDesigner config={...}>`,`registerBpmSdkAll` 单调用注册 9 节点
3. **B2d.3 palette port** — palette 节点迁到 SDK NodeRegistry
4. **B2d.4 E2E gate** — bpm-workflow.spec.ts 3 次稳定性,全绿才能 B2d.5
5. **B2d.5 删 legacy** — `bpmn-designer/` + `bpm-smoke/`

### Isolated Stack 命令(下次起备用)

```bash
COMPOSE_PROJECT_NAME=auraboot-b2d ./scripts/dev/start-isolated.sh --e2e --port-offset 60 --rebuild --wait
# backend 8140 / vite 5233 / pg 5493 / redis 6539
curl http://localhost:8140/actuator/health  # 期望 {"status":"UP"}
cd web-admin && E2E_BASE_URL=http://localhost:5233 npx playwright test bpm-workflow.spec.ts --repeat-each=3 --reporter=line
# tear down:
COMPOSE_PROJECT_NAME=auraboot-b2d ./scripts/dev/stop-isolated.sh
```

### Backlog 文件清单(下次 session 必读)

- `auraboot/docs/backlog/2026-05-23-T3-flow-designer-sdk-enhancement-plan.md` — T3 SDK 增强 plan(G1-G8 全部已交付)
- `auraboot/docs/backlog/2026-05-23-T2-backend-flowconfig-smartengine-compiler-plan.md` — T2 后端编译器(M1+M2+M3 已交付)
- `auraboot/docs/backlog/DDR-2026-05-23-automation-bpm-designer-convergence.md` — Option B 决策
- `auraboot/docs/backlog/2026-05-28-A3-T4-feasibility-report.md` — T4 33d 原估
- `auraboot/docs/backlog/2026-05-28-B2b-t4-batch3-port-report.md` — **B2d 5 步详细设计在 §9**
- `auraboot/docs/backlog/2026-05-28-B2c-followup-adapter-migration-report.md` — adapter Option A→hybrid 决策 + 消费方迁顺序
- `auraboot/docs/backlog/2026-05-29-B2c-batch2-complete-B2d-pending.md` — **本会话最后 handover(B2d 起步必读)**
- `auraboot-enterprise/docs/backlog/2026-05-28-designer-t4-session-reflection.md` — 会话反思 + 3 canonical 升级
- `auraboot-enterprise/docs/agent-rules/multi-worktree-isolation.md` §「Worktree count safety threshold」
- `auraboot-enterprise/docs/agent-rules/git-workflow.md` §「Stacked PR depth limit」
- `auraboot-enterprise/docs/standards/core/agent-collaboration.md` §「Subagent commit 协议」/「架构层决策回路」/「主对话 preflight」

### Memory 锚点(下次会话开局自动加载)

`MEMORY.md` 已写两条 designer T4 active work 索引(2026-05-28 系列 + 2026-05-29 续做),压缩后可恢复完整上下文。

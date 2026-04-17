# Session Handover — BPM Closure Spec 1 Final Delivery · 2026-04-17

## Session Summary

**OSS BPM 闭环 Spec 1 全部实施交付完成**（Task 1-15 + 文档 Task 16a）。基于 `bpm-module-target-architecture-design.md` 的 12 个核心决策和 5 条红线落地:
- Backend (Task 1-8): 消除 `BpmEngine` 抽象层、策略入 `<smart:properties>`、CC 走 SmartEngine `NotificationCommandService`、`BpmActionExecutor` 重写;已合并进 `bpm-closure-spec1` 分支。
- Frontend (Task 9a / 9b / 10-15): block renderer 迁移到 `useActionHandler`、`ActionDef` 加 `bpm` variant、`bpm-panel` block 4 section、Designer PropertySchema 配置化。
- Docs (Task 16a): 红线 RL-BPM-1..5 落进 enterprise `docs/standards/architecture.md`,子系统文档 `05-BPM工作流引擎.md` 加"2026-04 闭环交付"小节,本 HANDOVER 更新。

**唯一留白**: Task 16b 浏览器手工冒烟验证 (交付验收纪律红线要求) — controller 事后另起 session 执行。

## Tasks Completed (Spec 1 — 16/16 minus 16b)

### Backend — Task 1-8 (v2 plan authoritative)

| # | Task | Commit | 关键内容 |
|---|---|---|---|
| 1 | BpmExtensionAccessor + Keys + unit tests | `d5b5799c` | typed wrapper over SmartEngine `<smart:properties>` 解析器;`aura.*` key 常量 |
| 2 | 删除 BpmEngine 抽象层 + 修 TestBpmFixture | `dbefc602` | -10 文件;TestBpmFixture 走真路径 `ProcessDeploymentService` |
| 2 fix | 删除 stub 集成测试 | `4bc2856f` | per code review I1 |
| 3 | BPMN fixture template 加 `<smart:properties>` | `6d99ce56` | 模板含 `aura.withdrawPolicy` + `aura.ccPolicy` |
| 4 | WithdrawService 改用 accessor | `80fd517c` | policy 从 BPMN 读,不再读 entity column |
| 4 fix | accessor bug fix + tenant 严格化 | `38b786f7` | `IdBasedElement.getProperties()` 是 XML 属性 ≠ `<smart:property>` 子元素 |
| 5 | CcService 重写为 NotificationService 薄壳 | `8c5a987f` | 删 `ccRecord` / `InboxItem` 双写;走 SmartEngine `sendSingleNotification(type=cc)` |
| 5 fix | clarify transactional + reject null receivers | `cbb32d38` | per code review I1/I2 |
| 6 | BpmActionExecutor 重写用 ProcessEngineService | `d9253eff` | 真 SmartEngine dedup;strict JSONPath;blank guard;6 集成测试 |
| 7 | 回滚 entity 3 字段 + schema ALTER | `3d7e91d4` | scope 比 plan 小:只 BpmProcessDefinition + schema.sql 需改 |
| 8 | 删 BpmCcRecord + `ab_bpm_cc_record` 表 | `4922c80d` | -2 文件 + -29 schema 行 |

### Frontend — Task 9a / 9b / 10-15 (v2-frontend plan authoritative)

| # | Task | Commit | 关键内容 |
|---|---|---|---|
| 9a | 统一 block-level button click → useActionHandler | `0b2e2e60` | `FormButtonsBlockRenderer` / `ToolbarBlockRenderer` / `TableBlockRenderer` 不再直调 `runtime.executeHandler`,改走 `useActionHandler.handleAction(button.action)` |
| 9b | ActionDef bpm variant + dispatcher 分支 | `c3a5fe6c` | `ActionDef` discriminated union +1 variant `{ type: 'bpm', processKey, businessKeyField, variables? }`;`useActionHandler` `case 'bpm'` → `bpmWorkbenchService.startProcessFromAction` |
| 10 | bpm-panel block skeleton + bpmApi service | `b863f06c` | `DetailBlockRenderer` 按 `blockType==='bpm-panel'` 分派;`bpmApi.ts` 扩 4 endpoint(instance / runtime-bpmn / tasks / audit) |
| 11 | BpmStatusSection | `38628a78` | 实例 status badge + 当前节点列表 |
| 12 | BpmDiagramSection | `dcdcb067` | 运行时 BPMN SVG,当前活动节点高亮 |
| 13 | BpmOperationsSection + Dialogs + 3-tier permission | `1ca25769` | approve / reject / withdraw / cc 按钮按权限启用;`WithdrawDialog` + `CcDialog` |
| 14 | BpmHistorySection + audit API | `5e16cbb1` | 审批时间线(task_approve / process_withdraw / cc_send 等事件) |
| 15 | Designer PropertySchema for action.bpm + bpm-panel | `0ebe11ca` | PropertySchema 驱动 Designer 配置,不手写 JSX — 对齐 Studio Schema-driven 红线 |

### Docs — Task 16a (本次)

| 产物 | 位置 |
|------|------|
| 红线 RL-BPM-1..5 | `auraboot-enterprise/docs/standards/architecture.md` § BPM 闭环 |
| 子系统交付小节 | `auraboot-enterprise/docs/system-reference/subsystems/05-BPM工作流引擎.md` § 2026-04 闭环交付 |
| HANDOVER 最终交接 | 本文档 |

### 已跳过

- ~~Task 16b~~: 浏览器手工冒烟 (交付验收纪律红线) — 留待 controller 事后另起 session

## Commits in This Delivery

从 `0d69948f` (spec/plan 起点) 到 HEAD = `0ebe11ca`,共 23 commits:

```
0ebe11ca feat(web): Designer schema for action.bpm + bpm-panel block          [Task 15]
5e16cbb1 feat(web): BpmHistorySection renders audit trail timeline            [Task 14]
1ca25769 feat(web): BpmOperationsSection with withdraw/cc dialogs + 3-tier    [Task 13]
dcdcb067 feat(web): BpmDiagramSection renders runtime BPMN diagram            [Task 12]
38628a78 feat(web): BpmStatusSection renders instance status with badge       [Task 11]
b863f06c feat(web): add bpm-panel block skeleton for detail pages             [Task 10]
c3a5fe6c feat(web): ActionDef supports type=bpm via useActionHandler          [Task 9b]
0b2e2e60 refactor(web): route block-level button clicks through useActionHandler [Task 9a]
c8d5a640 docs(bpm): rewrite frontend plan (v2.1) aligned with actual web-admin architecture
0733db41 docs(handover): session handover after backend tasks 1-8 of v2 plan
4922c80d refactor(bpm): remove BpmCcRecord entity/mapper and ab_bpm_cc_record [Task 8]
3d7e91d4 refactor(bpm): drop withdrawPolicy/ccPolicy/requiredPermissions      [Task 7]
d9253eff feat(action): BpmActionExecutor uses ProcessEngineService + dedup    [Task 6]
cbb32d38 docs(bpm): clarify CcService transactional + reject null receivers   [Task 5 fix]
8c5a987f refactor(bpm): rewrite CcService over SmartEngine NotificationService [Task 5]
38b786f7 fix(bpm): remove dead key-lookup fallback and tighten tenant check   [Task 4 fix]
80fd517c refactor(bpm): WithdrawService reads policy from BPMN <smart:props>  [Task 4]
6d99ce56 test(bpm): embed aura.* policies in fixture BPMN <smart:properties>  [Task 3]
4bc2856f test(bpm): drop placeholder BpmActionExecutorIntegrationTest         [Task 2 fix]
dbefc602 refactor(bpm): remove BpmEngine abstraction layer                    [Task 2]
d5b5799c feat(bpm): typed accessor for <smart:properties> aura.* extensions   [Task 1]
8275b938 docs(bpm): implementation plan v2 for OSS BPM closure spec 1
05a6e3f8 docs(bpm): use smart:properties pattern, drop separate aura: namespace
```

## 已通过测试

### Backend 集成 (real PostgreSQL + Redis,无 mock DB)

- `BpmExtensionAccessorTest` 8/8
- `WithdrawServiceIntegrationTest` 5/5
- `CcServiceIntegrationTest` 6/6
- `BpmActionExecutorIntegrationTest` 6/6
- `PluginProcessImportDeploymentTest` 1/1

### Frontend 单元 (Vitest)

- `useActionHandler` `case 'bpm'` dispatch 分支覆盖
- `BpmStatusSection` / `BpmDiagramSection` / `BpmOperationsSection` / `BpmHistorySection` 组件单元覆盖
- Designer PropertySchema 验证

### 已知 pre-existing 失败 (与本 spec 无关)

- `BpmGatewayTest` D5-01..04 (4 tests) — "User not authorized to complete this task",HEAD~1 即失败

## 红线落地 (RL-BPM-1..5)

| ID | 一句话 | 代码证据 |
|----|--------|---------|
| RL-BPM-1 | 流程策略入 BPMN `<smart:properties>` `aura.*`,不加 DB 列 | `BpmProcessDefinition` 3 列已删 + `schema.sql` 同步 DROP |
| RL-BPM-2 | CC 走 SmartEngine `NotificationCommandService`,不建并行表 | `BpmCcRecord` 整包删除 + `CcService` 重写为 notification 薄壳 |
| RL-BPM-3 | 禁止复活 `BpmEngine` / `BpmEngineFactory` / adapter | `platform/.../bpm/engine/` 9 文件全删;`BpmActionExecutor` 直调 `ProcessEngineService` |
| RL-BPM-4 | 前端 `ActionDef.type='bpm'` 只走 `useActionHandler` case | `useActionHandler.ts` `case 'bpm'`;block renderer 全部迁移 (Task 9a) |
| RL-BPM-5 | `bpm-panel` 是 Runtime block,Designer 配置走 PropertySchema | `DetailBlockRenderer` 按 `blockType` 分派 + `bpm-panel-schema.ts` |

## Known 限制 / Pending TODO

### 立即 (下次 session 首要)

1. **Task 16b 浏览器手工冒烟** — 交付验收纪律红线要求
   - 执行路径:`cd auraboot/.worktrees/bpm-closure-spec1 && bash scripts/reset-and-init.sh`(或 OSS equivalent) → 登录 → 用 workflow-demo plugin 或示例模型
   - 验收点:button 启动流程 toast → 详情页 bpm-panel 4 section 渲染 → approve 推进 → withdraw 终止 → cc 收件人 inbox 可见
   - 通过后合并到 `main`;不过则 bisect + 修

### 架构 (本 spec 范围外,已识别)

2. **Layer 1 权限推导依赖 backend 投影** — `BpmOperationsSection` 3-tier permission 目前用 assignee/initiator 简单判断。完整 layer 1 (detail-level 固定权限) 需 backend 把 `requiredPermissions` 投影到 DTO;等 backend Controller 调整。
3. **WithdrawPolicy / CcPolicy 前端只透传 variables** — 前端 Dialog 目前不预判 policy,直接调后端由后端根据 `<smart:properties>` 拒绝。等 backend DTO 加 policy 字段后可在前端做前置禁用/提示升级。
4. **User display name 反查未实现** — `BpmHistorySection` timeline 显示 raw `userId`(e.g. `user_abc123`),未反查显示 name。后续独立小 task 接 user API。

### Spec 1.5 (计划中,未启动)

5. **`ab_bpm_process_definition` 完整瘦身** — 其余元数据列(versionTag / description / category 等)评估是否挪进 BPMN 或独立 metadata 表
6. **`BpmAuditQueryService` 聚合** — 目前 timeline 读 `ab_bpm_audit_event`,SmartEngine 自带 history 未融合;聚合避免用户看到两条时间线
7. **Jump (jumpTo) 收紧** — 当前 jump 无目标节点校验,可能跳到 flow 外的 node
8. **Timeout sunset** — `timeout` 字段的语义归入 SLA,本字段 deprecate

### Spec 4 (计划中,未启动)

9. **Supervision 模块** — 主管视角查看组织内进行中流程、介入(reassign / pause / resume / terminate);spec 已规划,plan 未写

## Key Decisions (Recap)

| 决策 | 选择 | 理由 |
|---|---|---|
| BpmEngine 抽象层 | **物理删除** | 生产 0 caller;接口太薄无救;memory 软规则拦不住,必须代码消除 |
| SmartEngine 集成 | 厚 service + 不抽象 | 真换引擎服务层就要重写,假抽象有害 |
| CC 实现 | 走 SmartEngine `NotificationCommandService` | `se_notification_instance` 100% 覆盖能力,自带 fluent query + 索引 |
| 业务策略 | 进 BPMN `<smart:properties>` `aura.*` 前缀 | Camunda/Flowable 主流;single source of truth;插件包只一个 `.bpmn` |
| BPMN namespace | 复用 `xmlns:smart="http://smartengine.org/schema/process"`,不新增 | SmartEngine 已自动解析 |
| 前端 ActionDef | 加新 variant (discriminated union) | 跨多插件使用,不改 type 体系本身 |
| bpm-panel | Runtime block (不是 Studio block) | Designer 配置走 PropertySchema,遵守 Studio Core = Schema-driven 红线 |
| Spec 拆分 | Spec 1 / Spec 1.5 / Spec 4 | 分批清晰可发布 |

详见 `docs/superpowers/specs/2026-04-17-bpm-module-target-architecture-design.md` 第 3 节 D1-D12。

## Pitfalls & Workarounds (历次累积)

1. **Task 1 accessor 用错 SmartEngine API**
   - 问题:`IdBasedElement.getProperties()` 返回 XML 元素属性(id/version/isExecutable),不是 `<smart:property>` 子元素
   - 解决:Task 4 集成测试时发现,accessor 改读 `extensionElements.getDecorationMap().get(ExtensionElementsConstant.PROPERTIES)`,commit `38b786f7` 修
   - 预防:单元测试至少一个 mock 真实数据流 case;集成测试在 Task 1 同步加

2. **Task 7 subagent 长 prompt 触发 529**
   - 问题:implementer prompt 太长(多文件 + grep 步骤 + 完整代码示例)触发 Anthropic API 529
   - 解决:用 SendMessage 发简短"continue"给同一 agent,agent 凭已有上下文续跑
   - 预防:控制 implementer prompt ~3000 字内

3. **subagent 越界合并 Task**
   - 问题:Task 7 commit `3d7e91d4` 同时改了 entity + schema.sql,超出 plan 边界,Task 8 工作量减半
   - 学习:subagent prompt 明确 scope,越界但 commit message 准确即可

4. **前端架构与 backend 不 mirror**
   - 问题:v2 plan Task 9-15 假设 `ActionDef` 有 `executionMode` property,实际是 discriminated union
   - 解决:拆 v2-frontend plan (commit `c8d5a640`),重写 Task 9-15 贴合实际
   - 学习:frontend exploration 必须在 plan 前做,不要 mirror backend 结构

5. **Block renderer legacy handler 耗能**
   - 问题:`FormButtonsBlockRenderer` / `ToolbarBlockRenderer` / `TableBlockRenderer` 直调 `runtime.executeHandler`,绕过 dispatcher
   - 影响:`ActionDef` 新 variant 在 block 层失效
   - 解决:Task 9a 先迁移 renderer → `useActionHandler`,Task 9b 再加 variant,分两 commit 保 cleanliness

## Lessons Learned

1. **抽象层的诱惑必须用代码物理消除** — memory 规则、注释、code review 都拦不住下一个 contributor。`BpmEngine` 教训重复 3 次才在新 spec 一次性删掉。
2. **真集成才是真测试** — Task 1 单元测试 6/6 PASS 但 accessor 实际错的;只有 Task 4 集成测试暴露。
3. **业界主流 = 安全选择** — BPMN extension elements vs DB table,业界 (Camunda/Flowable/Activiti/钉钉/飞书) 一致选 BPMN,没争议。
4. **frontend ≠ backend 的设计 mirror** — `executionMode=bpm` 是 backend column field,`ActionDef` 是 frontend type discriminated union。不要假设两边一致。
5. **Plan 先探索后写** — v2 plan Task 9-15 纸上写完又重做,如果 plan 前先探前端架构就省一轮。
6. **block renderer 统一走 dispatcher** 是长期架构清理,不只是 BPM 局部需求 — 顺手做掉比等下次 action variant 再做好。

## Current State

### Git

```
分支: bpm-closure-spec1
HEAD: 0ebe11ca feat(web): Designer schema for action.bpm + bpm-panel block
工作树: 干净 (commit 16a 前)
Ahead of spec 起点 0d69948f: 23 commits (本 docs commit 后 24)
```

### Worktree

- 路径: `/Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1`
- 分支: `bpm-closure-spec1`
- PostgreSQL (`aura_boot` db) + Redis 要跑
- Schema 干净:`withdrawPolicy` / `ccPolicy` / `requiredPermissions` 列不存在,`ab_bpm_cc_record` 表不存在

### Running Services (Task 16b 冒烟需要)

- 前端 5173 + 后端 6443 要跑
- 命令:`bash scripts/reset-and-init.sh` (OSS 版本: `bash scripts/oss-reset-and-init.sh` 如存在)

## Next Steps (下次 session)

### 优先 1: Task 16b 手工冒烟 (交付验收纪律)

参考 `docs/superpowers/plans/2026-04-17-oss-bpm-closure-spec1-plan-v2-frontend.md` Task 16 Step 4:
1. 启动全栈 + 重置环境
2. 登录,选 workflow-demo plugin 或自造示例模型 (需启 `aura.withdrawPolicy` + `aura.ccPolicy`)
3. 新建记录 → 点启动审批 → toast 可见
4. 详情页:status badge / 流程图高亮 / operations 按钮按权限 / history 有 `process_start` 事件
5. approve 一步 → history 加 `task_approve` 事件
6. withdraw → status 变 `withdrawn`,流程终止
7. CC → 收件人 inbox 看到 notification (`type=cc`)

**通过**: 合并 `bpm-closure-spec1` 到 `main`,spec 1 正式 close。

**不过**: 用 `git bisect` 定位哪个 commit 引入 regression,修 + 补 E2E。

### 优先 2 (并行可做): Layer 1 permission projection

backend DTO 把 `requiredPermissions` 暴露到前端 → `BpmOperationsSection` 升级到完整 3-tier。独立小 task。

### 优先 3: Spec 1.5 启动

ab_bpm_process_definition 瘦身 + 审计聚合 + jump 收紧 + timeout sunset。等 Spec 1 合并完后写 spec 文档 + plan。

## 关键参考文件

### 本次 spec docs

1. **目标架构**: `docs/superpowers/specs/2026-04-17-bpm-module-target-architecture-design.md` — 462 行,权威
2. **Backend plan (Task 1-8)**: `docs/superpowers/plans/2026-04-17-oss-bpm-closure-spec1-plan-v2.md`
3. **Frontend plan (Task 9-16)**: `docs/superpowers/plans/2026-04-17-oss-bpm-closure-spec1-plan-v2-frontend.md`
4. **红线**: `auraboot-enterprise/docs/standards/architecture.md` § BPM 闭环
5. **子系统**: `auraboot-enterprise/docs/system-reference/subsystems/05-BPM工作流引擎.md` § 2026-04 闭环交付
6. **上一 session v1 handover**: `docs/handover/HANDOVER-2026-04-16-bpm-spec1-v1.md`

### 本次关键代码

Backend:
- `platform/src/main/java/com/auraboot/framework/bpm/extension/BpmExtensionAccessor.java`
- `platform/src/main/java/com/auraboot/framework/bpm/extension/BpmExtensionKeys.java`
- `platform/src/main/java/com/auraboot/framework/bpm/service/WithdrawService.java`
- `platform/src/main/java/com/auraboot/framework/bpm/service/CcService.java`
- `platform/src/main/java/com/auraboot/framework/action/executor/BpmActionExecutor.java`

Frontend:
- `web-admin/app/framework/meta/schemas/types.ts` (ActionDef union + bpm variant)
- `web-admin/app/framework/meta/hooks/useActionHandler.ts` (case 'bpm')
- `web-admin/app/plugins/core-designer/components/studio/services/runtime/renderers/blocks/FormButtonsBlockRenderer.tsx` / `ToolbarBlockRenderer.tsx` / `TableBlockRenderer.tsx`
- `web-admin/app/plugins/core-designer/.../blocks/bpm-panel/` (4 section + renderer + schema)
- `web-admin/app/plugins/core-designer/.../services/bpmApi.ts`

## Context for Next Session

### 启动第一条 prompt 建议

```
继续 BPM 闭环 spec 1 的收尾。worktree 在
/Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
分支 bpm-closure-spec1。HANDOVER 在 docs/handover/HANDOVER.md。

Spec 1 Task 1-15 + 文档 Task 16a 已全部完成,commits 从 65d4f415 到 HEAD。
剩 Task 16b 浏览器手工冒烟验证。

请按 HANDOVER "Next Steps 优先 1" 执行手工冒烟:
1. reset-and-init.sh (或 OSS equivalent)
2. 登录 → 用 workflow-demo 或示例模型
3. 启动流程 → bpm-panel 4 section → approve → withdraw → cc 全走一遍
4. 通过则合并到 main;不过则 bisect + 修

冒烟通过后 spec 1 正式 close。下一个 spec 是 1.5 (ab_bpm_process_definition 瘦身)
或 4 (Supervision),等用户指示再启动。
```

### 必跑命令

```bash
# 进 worktree
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1

# 验证 backend 基线 (应全 PASS)
cd platform
./gradlew test --tests com.auraboot.framework.bpm.WithdrawServiceIntegrationTest \
               --tests com.auraboot.framework.bpm.CcServiceIntegrationTest \
               --tests com.auraboot.framework.bpm.extension.BpmExtensionAccessorTest \
               --tests com.auraboot.framework.action.BpmActionExecutorIntegrationTest \
  -x :platform-plugin-api:test -x :platform-storage-minio:test \
  -x :platform-storage-s3:test -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test -x :platform-mq-rabbitmq:test 2>&1 | tee /tmp/pw-resume.log
grep -E "PASSED|FAILED" /tmp/pw-resume.log | head -30

# Frontend vitest
cd ../web-admin
pnpm vitest run 2>&1 | tee /tmp/vitest-resume.log

# 启动全栈准备手工冒烟
bash ../scripts/reset-and-init.sh   # 或 oss-reset-and-init.sh
```

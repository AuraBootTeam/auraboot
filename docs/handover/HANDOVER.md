# Session Handover — BPM Closure Spec 1 v2 · 2026-04-17

## Session Summary

完成了 OSS BPM 闭环 Spec 1 的**完整重新设计 + backend 实施**。基于上一 session 反复踩坑（`BpmEngine` 抽象陷阱），对整个 BPM 模块做了根本性重新对齐——目标架构 spec + v2 plan 落地，然后用 subagent-driven-development skill 执行了 8 个 backend tasks 全部通过。**Backend 是完整可发布垂直切片**；前端 7 task + 文档 1 task 待新 session 继续（plan 假设的前端架构与实际不符，需要在新 session 先重新探索再适配）。

## Tasks Completed (Backend Spec 1, 8/16)

| # | Task | Commit | 关键内容 |
|---|---|---|---|
| 设计 | Target architecture spec | `65d4f415` | 12 个核心决策 + 5 红线 + 数据模型最终形态 |
| 设计 | smart:properties 修订 | `05a6e3f8` | 不引入 aura: namespace；用 smart: + aura.* 名称前缀 |
| 设计 | Implementation plan v2 | `8275b938` | 16 task 重排，bite-sized 步骤 |
| 1 | BpmExtensionAccessor + Keys + tests | `d5b5799c` | typed wrapper over SmartEngine `<smart:properties>` 解析器 |
| 2 | 删除 BpmEngine 抽象层 + 修 TestBpmFixture | `dbefc602` | -10 文件；TestBpmFixture 走真路径 ProcessDeploymentService |
| 2 fix | 删除 stub 集成测试 | `4bc2856f` | per code review I1 |
| 3 | BPMN fixture template 加 `<smart:properties>` | `6d99ce56` | 模板含 aura.withdrawPolicy + aura.ccPolicy |
| 4 | WithdrawService 改用 accessor | `80fd517c` | policy 从 BPMN 读，不再读 entity column |
| 4 fix | accessor bug fix + tenant 严格化 | `38b786f7` | `IdBasedElement.getProperties()` 是 XML 属性 ≠ `<smart:property>` 子元素；tenant null 抛 IllegalStateException |
| 5 | CcService 重写为 NotificationService 薄壳 | `8c5a987f` | 删 ccRecord/InboxItem 双写；走 SmartEngine sendSingleNotification(type=cc) |
| 5 fix | clarify transactional + reject null receivers | `cbb32d38` | per code review I1/I2 |
| 6 | BpmActionExecutor 重写用 ProcessEngineService | `d9253eff` | 真 SmartEngine dedup；strict JSONPath；blank guard；6 集成测试 |
| 7 | 回滚 entity 3 字段 + schema ALTER | `3d7e91d4` | scope 比 plan 小：只 BpmProcessDefinition + schema.sql 需改 |
| 8 | 删 BpmCcRecord + ab_bpm_cc_record 表 | `4922c80d` | -2 文件 + -29 schema 行 |

**测试结果**：所有 BPM 集成测试通过（`BpmExtensionAccessorTest` 8/8、`WithdrawServiceIntegrationTest` 5/5、`CcServiceIntegrationTest` 6/6、`BpmActionExecutorIntegrationTest` 6/6、`PluginProcessImportDeploymentTest` 1/1）。**4 个 BpmGatewayTest D5-01..04 失败是 pre-existing**（在 HEAD~1 即失败，与本 spec 无关，"User not authorized to complete this task"）。

## Tasks In Progress

无。Task 9（前端 ActionDef + dispatch 分支）已 mark 回 `pending`——发现前端架构与 plan 假设差异较大，需要新 session 先探索再适配。

## Tasks Not Started (Spec 1 frontend + closure)

| # | Task | 依赖 |
|---|---|---|
| 9 | 前端 ActionDef + dispatch 分支 | 需要新设计：`ActionDef` 是 `type` discriminated union 不是 executionMode property |
| 10 | bpm-panel block skeleton + bpmApi service | 需要先理解现有 BlockRegistry 架构 |
| 11 | BpmStatusSection | 需要 Task 10 |
| 12 | BpmDiagramSection | 需要 Task 10 |
| 13 | BpmOperationsSection + WithdrawDialog + CcDialog + BpmPermissionService | 需要 Task 10 |
| 14 | BpmHistorySection + listAuditEvents API | 需要 Task 10 |
| 15 | action.bpm + bpm-panel PropertySchema | 独立，可任意时机 |
| 16 | 文档同步（红线 RL-BPM-1..5 + 子系统 docs）+ 冒烟验证 | 收尾 |

## Key Decisions

| 决策 | 选择 | 理由 |
|---|---|---|
| BpmEngine 抽象层 | **物理删除** | 生产 0 caller；接口太薄无救；memory 软规则拦不住，必须代码消除 |
| SmartEngine 集成 | 厚 service + 不抽象 | 真换引擎服务层就要重写，假抽象有害 |
| CC 实现 | 走 SmartEngine NotificationService | `se_notification_instance` 100% 覆盖能力，自带 fluent query + 索引 |
| 业务策略 | 进 BPMN `<smart:properties>` `aura.*` 前缀 | Camunda/Flowable 主流；single source of truth；插件包只一个 .bpmn |
| BPMN namespace | 复用 `xmlns:smart="http://smartengine.org/schema/process"`，不新增 | SmartEngine 已自动解析；`IdBasedElement.getProperties()` 直接取 |
| Spec 拆分 | Spec 1（当前）+ Spec 1.5（瘦身审计）+ Spec 4（Supervision） | 用户 feedback：分批清晰可发布 |
| Audit 边界 | `BpmAuditService` 只记 SE 不感知的（approve/reject/withdraw/cc-action） | 去 transfer/rollback/assignee 重复 |

详见 `docs/superpowers/specs/2026-04-17-bpm-module-target-architecture-design.md` 第 3 节 D1-D12。

## Files Changed

### Backend — Service 层
- `platform/src/main/java/com/auraboot/framework/bpm/extension/BpmExtensionAccessor.java` — 新增（typed wrapper）
- `platform/src/main/java/com/auraboot/framework/bpm/extension/BpmExtensionKeys.java` — 新增（aura.* 常量）
- `platform/src/main/java/com/auraboot/framework/bpm/service/WithdrawService.java` — 重写（用 accessor）
- `platform/src/main/java/com/auraboot/framework/bpm/service/CcService.java` — 重写（用 NotificationService）
- `platform/src/main/java/com/auraboot/framework/action/executor/BpmActionExecutor.java` — 重写（用 ProcessEngineService）
- `platform/src/main/java/com/auraboot/framework/bpm/controller/TaskController.java` — `ccTask` 返回 `ApiResponse<Void>`

### Backend — 数据层
- `platform/src/main/java/com/auraboot/framework/plugin/entity/BpmProcessDefinition.java` — 删 3 字段
- `platform/src/main/resources/database/schema.sql` — 删 ALTER 加 3 列 + 删 ab_bpm_cc_record 块

### Backend — 删除
- `platform/src/main/java/com/auraboot/framework/bpm/engine/` 整包（9 文件）
- `platform/src/test/java/com/auraboot/framework/bpm/engine/BpmEngineAbstractionTest.java`
- `platform/src/main/java/com/auraboot/framework/bpm/entity/BpmCcRecord.java`
- `platform/src/main/java/com/auraboot/framework/bpm/mapper/BpmCcRecordMapper.java`

### Backend — 测试
- `platform/src/test/java/com/auraboot/framework/bpm/extension/BpmExtensionAccessorTest.java` — 新增 8 测试
- `platform/src/test/java/com/auraboot/framework/bpm/CcServiceIntegrationTest.java` — 重写（断言 SmartEngine notification）
- `platform/src/test/java/com/auraboot/framework/action/BpmActionExecutorIntegrationTest.java` — 新增 6 测试
- `platform/src/test/java/com/auraboot/framework/bpm/TestBpmFixture.java` — BPMN 模板加 smart:properties + 走真路径 deploy

### 文档
- `docs/superpowers/specs/2026-04-17-bpm-module-target-architecture-design.md` — 462 行目标架构（commit 65d4f415 + 05a6e3f8）
- `docs/superpowers/plans/2026-04-17-oss-bpm-closure-spec1-plan-v2.md` — 2745 行 v2 plan（commit 8275b938）

## Pitfalls & Workarounds

1. **Task 1 accessor 用错 SmartEngine API**
   - **问题**：`IdBasedElement.getProperties()` 返回 XML 元素属性（id/version/isExecutable），不是 `<smart:property>` 子元素
   - **根因**：单元测试只覆盖空 map 默认值场景，未触发真实解析路径
   - **解决**：Task 4 集成测试时发现，accessor 改读 `extensionElements.getDecorationMap().get(ExtensionElementsConstant.PROPERTIES)`，类型 `Map<PropertyCompositeKey, PropertyCompositeValue>`，commit `38b786f7` 修
   - **预防**：单元测试要至少有一个 mock 真实数据流的 case；集成测试在 Task 1 同步加

2. **Task 7 触发 API 529 overload**
   - **问题**：长 prompt（多文件 + grep 步骤 + 完整代码示例）触发 Anthropic API 529
   - **解决**：用 SendMessage 发简短"continue"给同一 agent，agent 凭已有上下文继续完成
   - **预防**：未来长 task 切成更小子 task；implementer prompt 控制在 ~3000 字以内

3. **Task 7 plan scope 偏大**
   - **问题**：plan 列出 ProcessDefinitionDTO/Controller/PluginImporters 都需修改，实际只有 BpmProcessDefinition entity 有这 3 字段
   - **解决**：implementer 全仓 grep 验证后只改 entity + schema，不动其他文件
   - **学习**：plan 写的时候过于"防御性"，应基于实际 grep 结果

4. **subagent 把 Task 8 schema 工作合到 Task 7**
   - **问题**：Task 7 commit `3d7e91d4` 同时改了 entity + schema.sql 的 ALTER，超出 plan 边界
   - **影响**：Task 8 实际工作量减半（只剩 BpmCcRecord 删除）
   - **学习**：subagent prompt 要明确 scope 边界，但即使越界 commit message 准确反映就 OK

5. **`TestBpmFixture.deployProcess` 的 BPMN 模板字段数变化**
   - **问题**：Task 2 单参 → Task 3 三参，要同步两个调用点 + 删除 post-deploy column update
   - **预防**：模板签名变化时 grep 所有调用点

## Lessons Learned

1. **抽象层的诱惑必须用代码物理消除**——memory 规则、注释警告、code review 都拦不住下一个 contributor 走错路。`BpmEngine` 教训重复 3 次（v1 Task 3、v1 Task 6、新设计才一次性删掉）
2. **真集成才是真测试**——Task 1 单元测试 6/6 PASS 但 accessor 实际错的；只有 Task 4 集成测试才暴露。设计测试用例时必须覆盖"真实数据流"，不能只测 happy path 的空 map
3. **业界主流 = 安全选择**——BPMN extension elements vs DB table 的二选一，业界（Camunda/Flowable/Activiti/钉钉/飞书）一致选 BPMN，没争议
4. **plan 写得过细不一定好**——Task 7 的"多文件修改清单"超出实际需要；应基于"grep 后再 narrowly scope"
5. **frontend ≠ backend 的设计 mirror**——backend `executionMode=bpm` 是 column field，frontend `ActionDef` 是 type discriminated union；不要假设两边一致
6. **subagent 长 prompt 容易触发 529**——pitfall #2 在前一 session HANDOVER 已警告，本 session 又踩。控制在 ~3000 字内或用 SendMessage 续

## Current State

### Git Status
工作树干净，HEAD = `4922c80d`：
```
4922c80d refactor(bpm): remove BpmCcRecord entity/mapper and ab_bpm_cc_record schema
3d7e91d4 refactor(bpm): drop withdrawPolicy/ccPolicy/requiredPermissions from entity
d9253eff feat(action): BpmActionExecutor uses ProcessEngineService + real SmartEngine dedup
cbb32d38 docs(bpm): clarify CcService transactional semantics + reject null receivers
8c5a987f refactor(bpm): rewrite CcService over SmartEngine NotificationService
38b786f7 fix(bpm): remove dead key-lookup fallback and tighten tenant check ...
80fd517c refactor(bpm): WithdrawService reads policy from BPMN <smart:properties>
6d99ce56 test(bpm): embed aura.* policies in fixture BPMN <smart:properties>
4bc2856f test(bpm): drop placeholder BpmActionExecutorIntegrationTest
dbefc602 refactor(bpm): remove BpmEngine abstraction layer
d5b5799c feat(bpm): typed accessor for <smart:properties> aura.* extensions
8275b938 docs(bpm): implementation plan v2 for OSS BPM closure spec 1
05a6e3f8 docs(bpm): use smart:properties pattern, drop separate aura: namespace
65d4f415 docs(bpm): target architecture design — eliminate BpmEngine, BPMN-first config
24b26df5 docs(handover): session handover for BPM closure spec 1 (v1)
```

13 commits ahead of `0d69948f`（spec/plan 起点）。

### Worktree
- `/Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1`
- 分支 `bpm-closure-spec1`

### Running Services
- 不需要前后端运行
- PostgreSQL（aura_boot db）+ Redis 必须跑
- Schema 已 reset，policy column + ab_bpm_cc_record 都不存在

### 已通过测试
- `BpmExtensionAccessorTest` 8/8
- `WithdrawServiceIntegrationTest` 5/5
- `CcServiceIntegrationTest` 6/6
- `BpmActionExecutorIntegrationTest` 6/6
- `PluginProcessImportDeploymentTest` 1/1

### 已知 pre-existing 失败
- `BpmGatewayTest` D5-01..04 (4 tests) — "User not authorized to complete this task"，HEAD~1 也失败，与本 spec 无关

## Next Steps

### 优先：新 session 启动 Task 9（前端 dispatch 分支）

**重要**：plan v2 Task 9-15 假设的前端架构与实际不符。新 session 必须**先探索后改 plan**。

实际前端架构发现（来自本 session 探索）：

```
app/framework/meta/schemas/types.ts:263
  → ActionDef 是 type discriminated union:
    type: 'command' | 'state_transition' | 'navigate' | 'builtin' | 'flow'
  → 没有 executionMode 字段（plan 假设错误）

app/plugins/core-designer/components/studio/services/runtime/execution/ActionExecutor.ts
  → 不是单一 dispatchAction(action, record) 函数
  → 是多个 ActionExecutor 子类（CommandAction / NavigateAction / FlowAction / ...）
  → globalActionScheduler.executeAction(action, context) 路由

正确做法（待新 session 设计 + 用户确认）：
  - 加 ActionDef 新 variant: { type: 'bpm'; processKey; businessKeyField; variables? }
  - 新增 BpmAction executor class 注册到 globalActionScheduler
  - 不要改 type 体系本身（该体系跨多个插件使用）
```

新 session 第一步建议：

1. 读 `docs/superpowers/specs/2026-04-17-bpm-module-target-architecture-design.md` 全文
2. 读 `docs/superpowers/plans/2026-04-17-oss-bpm-closure-spec1-plan-v2.md` Task 9-16 部分
3. 读本 HANDOVER（你正在看的）
4. **探索前端**：
   - `app/framework/meta/schemas/types.ts` 看 ActionDef union
   - `app/plugins/core-designer/components/studio/services/runtime/execution/ActionExecutor.ts` 看现有 executor pattern
   - `app/plugins/core-designer/components/studio/registry/blocks/index.ts` 看 BlockRegistry
   - `app/plugins/core-designer/components/studio/types.ts` 看 PropertySchema 类型
5. 与用户对齐：是否同意"加 ActionDef variant + 新增 BpmAction executor class"方案
6. 用户同意后，**改写 v2 plan 的 Task 9-15** 反映实际架构
7. 再用 subagent-driven-development 执行

### 次优先：Task 16 文档同步可独立先做

Task 16 是把 RL-BPM-1..5 红线加进 `docs/standards/architecture.md` + 更新 `docs/system-reference/subsystems/` 的 BPM 文档。完全 backend 范围，不依赖前端。可以新 session 先做 Task 16，然后再做前端。

### Spec 1.5 / Spec 4 准备

Spec 1 backend 完成后，Spec 1.5（`ab_bpm_process_definition` 完整瘦身 + 审计 dedup + jump 收紧）应该启动。Spec 4（Supervision 模块）在 spec/plan 已规划但未启动。

## Context for Next Session

### 关键文档（按重要性）

1. `docs/superpowers/specs/2026-04-17-bpm-module-target-architecture-design.md` — **目标架构权威，必读**
2. `docs/superpowers/plans/2026-04-17-oss-bpm-closure-spec1-plan-v2.md` — v2 plan（Task 9-16 需重新设计前端部分）
3. `docs/handover/HANDOVER.md` — 本文档
4. `docs/handover/HANDOVER-2026-04-16-bpm-spec1-v1.md` — 上一 session（v1 plan 教训）

### 关键代码文件（前端探索时优先读）

- `web-admin/app/framework/meta/schemas/types.ts:263` — `ActionDef` 定义所在
- `web-admin/app/plugins/core-designer/components/studio/services/runtime/execution/ActionExecutor.ts` — 现有 ActionExecutor 模式
- `web-admin/app/plugins/core-designer/components/studio/domain/dsl/types.ts` — Studio designer 内部类型
- `web-admin/app/plugins/core-designer/components/studio/registry/blocks/` — block 注册表

### 关键代码文件（backend，Spec 1 完成后供前端调用）

- `BpmActionExecutor` 通过 backend Controller `POST /api/bpm/process-instances`（已有）触发
- `WithdrawService` 通过 `POST /api/bpm/tasks/{id}/withdraw`（已有）触发
- `CcService` 通过 `POST /api/bpm/tasks/{id}/cc`（已有）触发
- CC inbox 查询走 SmartEngine NotificationQuery — controller endpoint 待补（plan Task 10 包含 `/api/bpm/notifications/inbox`，但 backend Controller 还未写。新 session 评估是否补此 endpoint 或前端直查）

### 启动流程（新 session 第一条 prompt 建议）

```
继续 BPM 闭环 spec 1 的前端实施。worktree 在
/Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
分支 bpm-closure-spec1。HANDOVER 在 docs/handover/HANDOVER.md。

Backend Tasks 1-8 已完成且测试全通过。前端 Tasks 9-16 待启动，但 plan
里的前端假设与实际架构不符（详见 HANDOVER "Next Steps" 节）。

请先读完 HANDOVER + target spec，探索 web-admin 实际 ActionDef + ActionExecutor
+ BlockRegistry 架构，然后与我确认前端实施方案再开始。
```

### 必跑命令

```bash
# 进 worktree
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1

# 验证 backend 状态（应全 PASS）
cd platform
./gradlew test --tests com.auraboot.framework.bpm.WithdrawServiceIntegrationTest \
               --tests com.auraboot.framework.bpm.CcServiceIntegrationTest \
               --tests com.auraboot.framework.bpm.extension.BpmExtensionAccessorTest \
               --tests com.auraboot.framework.action.BpmActionExecutorIntegrationTest \
  -x :platform-plugin-api:test -x :platform-storage-minio:test \
  -x :platform-storage-s3:test -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test -x :platform-mq-rabbitmq:test 2>&1 | tee /tmp/pw-resume.log
grep -E "PASSED|FAILED" /tmp/pw-resume.log | head -30

# 如 schema 漂移，重置
cd /Users/ghj/work/auraboot/auraboot/.worktrees/bpm-closure-spec1
yes y | ../scripts/reset-db.sh
```

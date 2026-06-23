---
type: backlog
status: closed
created: 2026-05-28
---

<!-- no-precipitation: stale BLOCKED record; blocker and tracked spec (automation-golden.spec.ts) both resolved; lesson in AGENTS §2.1 environment-invalid -->

# B1 — Automation 黄金 E2E (BLOCKED_WITH_OWNER)

**Status**: BLOCKED_WITH_OWNER (owner: yaoyi.hz@gmail.com)
**Created**: 2026-05-28
**Trigger**: 设计器架构统一会话 Wave B 拆分;G5 nodeStatus 落地(A2 wt/sdk-g5-runtime @ 680ff334a)后,Automation 增量黄金 E2E 价值在 UI 入口 + endpoint 暴露 + trigger-event 端到端验证。

## 当前阻塞

- **Host backend 未启**:`curl localhost:8080/actuator/health` → 502;`lsof :8080` 零结果
- **9 个 active worktree → 触发红线 #11**:任何后端 E2E 必须走 docker isolated stack
- **磁盘压力**:2026-05-28 page-golden 战中 docker daemon 在长 stop/start 循环 + 磁盘 99% 时反复挂(memory canonical)
- **本会话评估**:新起 isolated stack 预计 30-60 min(含 backend 冷启 build),性价比低 — Wave A 三个 worktree 应先收口释放压力

## 已就位(等待执行)

- **A2 G5**:`/api/automation/executions/{instanceId}/node-statuses` endpoint + `ab_automation_node_execution` 表 + Java 32/32 IT 全绿(含 5 unit + 4 IT 真 SmartEngine MEMORY 模式跑过 success/failure 双路径)
- **trigger 真链路**:`AutomationCommandEventBridge.onRecordCreate` → `AutomationTriggerServiceImpl.onRecordCreate:82` → `findEnabledByModelCodeAndTriggerType` 拉 enabled automations 异步 fire
- **可用 fixture**:`e2et_order` model 已在 `automation-deep.spec.ts` 中作为 trigger model

## 取消阻塞条件

满足以下任一即可解阻:

1. Wave A 三个 worktree (wt/sdk-schema-lint, wt/sdk-g5-runtime, wt/sdk-bpm-smoke, wt/sdk-g7-g8) 合 main + 删 worktree → 磁盘压力缓解
2. Owner 显式批准 host backend 启动 (违反 #11 但可接受短期 trade-off)
3. 分配独立会话用 docker isolated stack `auraboot-b1` (port offset 20),专门跑 E2E

## 黄金 E2E 范围(待执行版)

新增 spec `auraboot/web-admin/tests/e2e/automation/automation-golden.spec.ts`:

1. **Setup**: API 创建 automation (trigger=record-create on `e2et_order` + condition + action=`update_record` 或 `send_notification`) + enable
2. **UI verify**: 跳转 `/automation/{id}` 编辑器,断言 3 节点 + 2 条边可见(Playwright locator,不拖拽)
3. **Fire**: API 创建一条 `e2et_order` record 触发
4. **Poll**: `/api/automation/executions/{instanceId}/node-statuses` 直到 endEvent completed(timeout 30s,失败时输出完整 status 历史)
5. **断言节点状态流转**: trigger=completed → condition=completed → action=completed,无 failed
6. **断言副作用**: GET 那条 record 验 action 写入字段真生效(或断言通知表新行)
7. **稳定性**: 真跑 ≥3 次连续通过

## 后续(UI 拖拽 spec, 进 B2 后)

T4 BPMN→SDK 迁移完成后,Automation 编辑器统一到 flow-designer-sdk,补一条**真拖拽** spec:
- 从 sidebar 进 Automation → 新建 → 拖 trigger / condition / action 节点 → 连边 → 保存 → 验 flowConfig 含 3 节点
- 利用 @dnd-kit 多步指针手势(参考 `docs/standards/e2e-extras/dnd-designer-test-conventions.md`)

## 本次会话已做的最小化收口

- 消除 `automation-deep.spec.ts:222` 的 `expect(true).toBe(true)` no-op(红线 §2 假通过)→ 改为 `test.skip(!hasCondition, ...)` 真实跳过(无 product gap 时仍真断言 `await expect(conditionSection).toBeVisible()`)
- 本 backlog 记录全部上下文,后续会话直接读这个文件可恢复

## 不冒进的理由

- A2 G5 IT 已覆盖产品代码最深层"SmartEngine 真跑 → nodeStatus 写库 → 状态流转 + 失败路径"
- B1 增量价值在 UI/endpoint/trigger-event 端到端,**不是产品代码本身的正确性** → 不构成发布闸门
- 磁盘/daemon 风险下盲跑 isolated stack 可能浪费 30-90 min(memory 已记录 page-golden 战中此类事故)

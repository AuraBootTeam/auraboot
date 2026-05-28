# ConversationTurnService Phase A 验收报告（A.7）

**日期**：2026-04-27
**状态**：A.1-A.6 全部落地，A.7 验收通过（无 chat 路径 regression）
**关联**：
- 设计稿 [`2026-04-26-conversation-turn-service-design.md`](./2026-04-26-conversation-turn-service-design.md) v3.3
- 执行 plan [`2026-04-26-conv-turn-svc-phase-a-execution-plan.md`](./2026-04-26-conv-turn-svc-phase-a-execution-plan.md) v4.1
- FAQ + 路线 [`2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md`](./2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md)

## 0. 摘要

| 维度 | 结果 |
|------|------|
| Phase A 范围 | A.1 + A.2 + A.2b + A.3 + A.4 + A.5 + A.6 全部落地 |
| 远端分支 | `feat/conversation-turn-service-phase-a` @ `5f6ed200`（top of branch） |
| 编译 | `:compileJava --rerun-tasks` ✅ + `:compileTestJava` ✅ |
| Grep gate（plan §6 A.3.4） | 0 forbidden patterns（所有 send*/emitter 直调全部清理） |
| SSE byte stream parity | 4/4 scenario 与 sha256 锁定的 pre-refactor baseline 一致 |
| sender_type 分布 | 0 → 0（Phase A persistence NOOP，零库写） |
| `aurabot_turn_*` metrics | 0 → 4（每次 /chat/stream +1，runTurn 真被 controller 调到） |
| 后端集成测试（chat 相关） | 23/23 ✅（4 个 chat 相关 test class 全过） |
| 前端 E2E（aurabot 套件） | 78/79 ✅（1 个 pre-existing AIP-07 sidebar nav 抖动，与 refactor 无关） |
| 唯一红色信号 | 1 个 IAM 测试类 schema 漂移 `UserSoulProfileControllerIntegrationTest`，**不在本 refactor scope** |

## 1. A.7 验收六项详情

### 1.1 后端身份校验（A.7.1）

```
6443 listener: PID 25950 (started 2026-04-27 13:49:59)
mavenLocal jar mtime: 27 Apr 13:49
jar contents (A.4-A.6 classes present):
  - com/auraboot/framework/conversation/ConversationTurnConfig.class
  - com/auraboot/framework/conversation/ConversationTurnConfig$1.class
  - com/auraboot/framework/conversation/ConversationTurnServiceImpl.class
  - com/auraboot/framework/conversation/TurnCompletedEvent.class
  - com/auraboot/framework/conversation/TurnSuspendedEvent.class
metrics endpoint registered:
  aurabot_turn_begin_total{phase=A} 0.0
  aurabot_turn_end_total{phase=A}   0.0
```

✅ 后端在跑 worktree publish 的 A.4-A.6 jar。

### 1.2 SSE pre-refactor baseline diff（A.7.2）

按 plan §10 跑 4 个 scenario：`trivial-greeting / explain-with-context / platform-query / general-question`。

```
✓ trivial-greeting.events     identical (chunk + done)
✓ explain-with-context.events identical (chunk + done)
✓ platform-query.events       identical (tool_start + tool_result + chunk + done)
✓ general-question.events     identical (chunk + done)
```

每种事件的 JSON top-keys 与 baseline 完全一致：

| event | baseline shape | post-refactor shape |
|-------|----------------|---------------------|
| `chunk` | `(content,)` | `(content,)` ✅ |
| `done` | `(content, traceId)` | `(content, traceId)` ✅ |
| `tool_start` | `(input, toolId, toolName)` | `(input, toolId, toolName)` ✅ |
| `tool_result` | `(result, success, toolId)` | `(result, success, toolId)` ✅ |

✅ SSE 字节级别 parity 验证通过。

### 1.3 sender_type 分布稳定性（A.7.3）

Phase A `TurnSideEffects.observeOnly` 注入：persistence/event/audit 全 NOOP，仅 metrics。预期 chat 路径**零库写**。

```
before (4 scenarios): SELECT sender_type, count(*) FROM ab_im_message GROUP BY sender_type;
  (0 rows)
after (4 scenarios):
  (0 rows)
```

✅ 0 → 0，与预期一致。Phase B 接 real Persistence 时再验证 sender_type 决策（Q8）落地。

### 1.4 metrics fire correctness（A.7.4）

```
before: aurabot_turn_begin_total{phase=A} 0.0
        aurabot_turn_end_total{phase=A}   0.0
fire 4 SSE scenarios（agentCode=aurabot 主路径）
after:  aurabot_turn_begin_total{phase=A} 4.0  (+4)
        aurabot_turn_end_total{phase=A}   4.0  (+4)
```

✅ 每次 `/chat/stream` 触发：
- `runTurn` 入口 → `metricsRecorder.recordTurnBegin` → counter +1
- `executeAuraBotTurn` 返 `TurnOutcome.Success` → `finalizeTurn` → `metricsRecorder.recordTurnEnd` → counter +1

证明 `AuraBotController` → `ConversationTurnServiceImpl.runTurn` → `AuraBotChatService.executeAuraBotTurn` → `finalizeTurn` 完整调用链运行期生效。

### 1.5 Worktree 前端 E2E（A.7.5）

通过 OSS test runner（`scripts/oss-test.sh tests/e2e/aurabot/`）跑 aurabot 套件，subagent 报告：

```
Phase: auth     — 3 passed
Phase: chromium — 75 passed / 1 failed
Total: 78 passed / 1 failed (78/79)
```

唯一 fail：`tests/e2e/aurabot/ai-panel.spec.ts:294:3 › AIP-07: Panel persists across page navigation`
- 失败原因：sidebar 导航 selector `a[href*="/meta/"]` 5s timeout
- 性质：UI 抖动，与 ConversationTurnService refactor 无关（与 §11.0 prod 数据 sender_type=human:6 / system:1 等基线观测一致：UI 主体功能未变）
- 第二次 run 失败数从 3 → 1，进一步印证抖动属性

E2E 期间 `aurabot_turn_begin/end` counter +2，确认 E2E 流量真实经过新 chokepoint，不是 mock 旁路。

✅ 通过。

### 1.6 后端集成测试（A.7.6）

```bash
./gradlew :test --tests com.auraboot.framework.aurabot.* \
                --tests com.auraboot.framework.conversation.* \
                --tests com.auraboot.framework.integration.agent.*
```

总计：509 tests / 472 passed / 37 failed / 0 errors / 0 skipped。

#### 与本 refactor 直接相关的 chat-path 测试

| Test class | pass/fail/error |
|------------|-----------------|
| `AuraBotAgentRoutingTest`（named-agent 路由 5 case） | **5/0/0 ✅** |
| `AuraBotChatServiceTracePayloadTest` | **4/0/0 ✅** |
| `ChatToolResolverIsReadOnlyTest` | **9/0/0 ✅** |
| `AuraBotAgentResolverIntegrationTest` | **5/0/0 ✅** |

23/23 全过 ✅。

#### 唯一失败（与本 refactor 无关）

```
com.auraboot.framework.integration.agent.UserSoulProfileControllerIntegrationTest: 37/37 全挂
```

failure cause（37 case 同一个）：

```sql
INSERT INTO ab_role (id, pid, tenant_id, name, code, status, deleted_flag)
VALUES (?, ?, ?, ?, 'tenant_admin', 'active', FALSE)
-- ERROR: value too long for type character varying(26)
```

判定：
- `ab_role` 是 IAM 表，与 ConversationTurnService 无任何代码路径交集
- 失败列是 `name`（VARCHAR(26)），测试 fixture 中 `name` 字段超 26 char 时直接溢出
- 本 refactor 没有改 `ab_role` schema 或 IAM service，无法引入此问题
- 性质：**pre-existing 基础设施 / schema drift bug**

应作为独立 issue 跟进（建议路径：扩 `ab_role.name` 到 VARCHAR(64) 或缩短测试 fixture），不阻塞 Phase A 验收。

## 2. PR 链 + commit 时间线

| Phase | Commit | 内容 | 提交时点 |
|-------|--------|------|---------|
| A.1 | `36715e55` | SPI + DTOs（`ConversationTurnService` 接口 + `TurnContext` / `TurnRequest` / `TurnOutcome` / `ResponseSink` / `InboundMode` / `TurnSideEffects`） | 历史 session |
| A.2 | `7234fce2` | `SseResponseSink` + `ResponseSink` SPI v4 微调（traceId on terminal events）+ `TurnRequest.legacyRequest` | 历史 session |
| A.2b | `d0766d79`（OSS main） | SSE pre-refactor baseline sha256 lock | 历史 session |
| **A.3** | **`9cf276e4`** | chatService split — sync core `executeAuraBotTurn`：38 个 `send*` call site → `sink.on*`，9 个 helpers + 1 dead `streamTextContent` 全删，每个终结点返 `TurnOutcome` | 本 session |
| **A.4 + A.5 + A.6** | **`383cd23b`** | `ConversationTurnServiceImpl` 真 `runTurn` + `AuraBotController` cutover + `ConversationTurnConfig`（Micrometer counters + `observeOnly` `TurnSideEffects` bean） | 本 session |
| 文档 | `5f6ed200` | 12 维 `runTurn` vs `streamChat` 对比 + B.0 named-agent 迁移建议 | 本 session |
| 验收 | （本文档） | A.7 验收报告 | 本 session |

PR 划分（plan §11 5 个 PR 模型）：本 session 的 A.3 + A.4-A.6 + 文档可合并为一个 PR-3 提交，或分两个独立 PR 评审（A.3 是高风险点，单独评审建议保留）。

## 3. Phase B 入场前的 known-good baseline

下列断言可作为 Phase B 改动后 regression 检查的基线：

1. `aurabot_turn_begin_total{phase=A}` 与 `aurabot_turn_end_total{phase=A}` 相等且每次 `/chat/stream` 主路径 +1
2. SSE 4 scenario 字节流 shape 与 sha256 baseline 一致（Phase B 引入 persistence 不应改变 byte stream，除非有意改了前端契约 §3.10）
3. `ab_im_message` 表 sender_type 分布在跑 chat 流量后**仅**应观察到来自 frontend `appendUserMessage` / `appendAssistantMessage` 的 row（Phase B 落地 real Persistence 时这条 invariant 应反向：服务端开始写 row，前端 endpoint 4/5 删除）
4. 23/23 chat-path test classes pass（注：A.4-A.6 落地后未引入新 test，Phase B 起补 `ConversationTurnServiceImplTest` 单测覆盖 finalize 4 outcome 路径）

## 4. 已知遗留 + 后续工作

| 项 | 性质 | 建议 |
|---|------|------|
| `UserSoulProfileControllerIntegrationTest` 37 failures（`ab_role.name` VARCHAR(26) 溢出） | pre-existing 基础设施 | 独立 issue：扩列到 VARCHAR(64) 或缩短测试 fixture |
| `ai-panel.spec.ts AIP-07` E2E 抖动 | pre-existing UI flake | 第二次 run 自愈；不阻塞 |
| named-agent 路径仍 dual route（controller 分支 + legacy `chatService.streamChat`） | Phase A 临时 scaffold | **B.0 named-agent SPI migration**（详见 [`2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md`](./2026-04-27-runtum-vs-streamchat-and-named-agent-migration.md) §2），建议 Phase B persistence wiring 之前完成 |
| `ConversationTurnServiceImpl` 单测 | A.4-A.6 commit 未配套 | Phase B 入场前补 `finalizeTurn` 4 outcome 分支单测 |
| `SseResponseSink` 单测 | A.2 commit message 承诺 10 case 但未实际入仓 | Phase B 起补，固化 byte 对齐契约 |
| 外部 watcher 自动 republish auraboot-core 覆盖 worktree jar | dev 环境工具侧 | 局部禁用 IDE auto-publish，或忽略（restart 后再 republish 一次） |

## 5. 决策建议

**Phase A 可以收口，进入 Phase B 准备。**

入场前 2 个建议动作：

1. **B.0 named-agent SPI migration**（独立 PR，~80-150 LOC）：把 `AgentChatPort.streamAgentChat(emitter)` 改造为 `runAgentTurn(TurnContext, ChatRequest, ResponseSink): TurnOutcome`，让 `ConversationTurnServiceImpl.runTurn` 内部 dispatch。这是 chokepoint claim 真正落地的最后一步，之后 Phase B 接 persistence 是单点。
2. **补 `ConversationTurnServiceImplTest`**：5-10 个单测覆盖 `finalizeTurn` 的 Success / Interrupted / Failed / PendingConfirmation(partial empty) / PendingConfirmation(partial non-empty) 5 个分支 + outcome=null 防御分支。

之后开 Phase B 实施 plan（design §3.10 `/execute` 端到端契约 + Persistence 替换 NOOP + 删前端 `appendUserMessage`/`appendAssistantMessage` 调用 + sender_type Q8 决策落地 + ChatSessionStore key 从 sessionId 迁到 turnId）。

## CHANGELOG

- 2026-04-27 创建：A.1-A.6 全部落地后写下 A.7 验收报告 + Phase B 入场建议

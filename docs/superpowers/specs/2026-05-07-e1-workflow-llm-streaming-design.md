# 2026-05-07 E.1 Workflow LLM Streaming — 设计草案

> **状态:草稿,等 owner 决策开放问题。**
> **类型:一次性设计稿。** 实施完成后,长期跟踪进 `docs/backlog/2026-05-06-acp-p0-p1-followups.md`。

## 背景

Backlog 引用:`docs/backlog/2026-05-06-acp-p0-p1-followups.md` §E.1

> `49503a3f` LlmCallExecutor 同步 chat 一次返完整响应。**长响应或代码生成场景体感差**。
> 建议:Automation 引擎加 streaming 支持(若现有节点都同步则推迟到引擎升级)。

## 当前 SPI 现状(2026-05-07 探源)

| 层 | 接口 | 形态 | 文件 |
|---|---|---|---|
| LlmProvider | `LlmChatResponse chat(LlmChatRequest, ...)` | **同步** | `agent/provider/LlmProvider.java:23` |
| AnthropicLlmProvider | 单次 HTTP 阻塞,非 SSE | 同步 | `agent/provider/AnthropicLlmProvider.java` |
| ActionExecutor | `Object execute(action, context)` | **同步返回** | `automation/executor/ActionExecutor.java` |
| LlmCallExecutor | 调 `provider.chat` 后写回 `${outputVariable}` | 同步 | `automation/executor/impl/LlmCallExecutor.java` |
| AuraBot SSE | `SseResponseSink` 在 `AuraBotChatService` 累积后emit;**provider 仍是同步**,SSE 是上层包装 | 上层 SSE,下层 sync | `conversation/SseResponseSink.java` |

**关键事实**:LlmProvider 没有 streaming SPI。AuraBot chat 看似流式,实际是 provider 返完整响应后 server-side 模拟 chunked 推送。

## 问题界定

"Streaming"在这个场景下其实是 **3 个不同需求**,需要先界定:

1. **N1 — UI 进度**:运行中的 automation 在 admin 页能看到 LLM 节点输出"正在生成"+ 累积内容(类似 ChatGPT 打字效果)。受众:运维/管理员 watch 一个长任务。
2. **N2 — 下游节点增量消费**:node B 在 LLM 节点 A 还没完成时就开始消费 A 的 partial 输出。受众:复杂 workflow 设计者。
3. **N3 — 真 LLM-API streaming**:provider 用 Anthropic `/v1/messages` `stream: true` 拉数据,降低首字延迟。受众:LLM 调用本身。

backlog §E.1 没有指明哪个;以下方案按"满足哪些需求"排列。

## 候选方案

### 方案 A — 上层 SSE 模拟(N1 only,sync provider 不变)

**做什么**:`LlmCallExecutor` 内部仍同步调 provider;在收到完整响应后,**chunked 推送**到一个 per-run `SseEmitter`;admin 页订阅 `/api/automations/runs/{runId}/llm-stream` 看进度。

**Pro**:
- 不动 LlmProvider SPI,不动 ActionExecutor 返回类型
- AuraBot 的 SseResponseSink 模式已成熟,直接复用
- 0 风险打破现有 11 个 LlmCallExecutorTest

**Con**:
- 完全是"假流式"——首字延迟仍是整次响应时间
- N2 / N3 都不解决

**估时**:2-3 天

---

### 方案 B — provider streaming + executor 同步聚合(N1 + N3,推荐)

**做什么**:
1. `LlmProvider` 加 `Flux<LlmChunk> streamChat(LlmChatRequest, ...)` 默认实现:`return Flux.just(syncResponse).flatMap(...)` 把 sync 拆成单 chunk(向后兼容)
2. `AnthropicLlmProvider` 重载实现真 SSE(`stream: true`)
3. `LlmCallExecutor` 调 `streamChat`,**仍同步累积全部 chunk** 后写回 `${outputVariable}`(executor 返回类型不变)
4. 同步累积期间,**侧通道 emit chunk** 到 `AutomationRunStreamPublisher` (Spring 事件 / Sink),admin 页 SSE 订阅
5. 老 `chat()` 方法保留,内部调 `streamChat().reduce(...)`

**Pro**:
- 真 LLM-API streaming,首字延迟显著降低
- N1 走侧通道,无需改 ActionExecutor SPI
- 渐进:1 个 provider impl 即可上线;OpenAI compat 可推迟
- 测试增量:每个 provider impl 配套 streaming IT

**Con**:
- LlmProvider SPI 多 1 方法,所有 impl 需补默认实现(已规划,Anthropic 真实现 + 其他默认包装)
- AuraBot chat 路径若想吃 streaming,需要把 SseResponseSink 切到订阅 streamChat(本 PR 不做,留给跟进)

**估时**:5-7 天(provider streaming impl + executor 改 + 侧通道 + IT + admin 页订阅 UI)

---

### 方案 C — 完整 streaming SPI 改造(N1 + N2 + N3)

**做什么**:
1. 同方案 B 的 LlmProvider streaming
2. **新建 `StreamingActionExecutor`** SPI(返回 `Flux<Object>` 或 `IntermediateResult` 序列)
3. Automation engine 加节点间增量信号(node B `awaitChunk()` API)
4. DSL 增字段标识哪些下游节点要 streaming 输入

**Pro**:满足 N1+N2+N3 的全部场景

**Con**:
- 影响所有 ActionExecutor impl(10+ 个)的契约
- DSL 加字段,前端 designer 需配套
- 风险倒挂:eng 复杂度爆炸,审查 PR 难度高
- 实际场景是否真需要 N2 未验证(backlog 表述"若现有节点都同步则推迟到引擎升级")

**估时**:10-14 天

## 推荐

**方案 B** —— 满足真 streaming(N3)+ admin 进度可见(N1),不破坏现有 executor SPI。N2(节点间增量)留给被实证驱动的下次设计。

steel-man 反方:用户场景里很多 long-running LLM 调用其实只需要"看着进度条不焦虑",sync chat + 假 chunk(方案 A)就够了。回应:虽然 N1 单独够用,但 1) Anthropic streaming API 已可用,延迟收益是真实的;2) 增量是"先做 N1+N3,N2 留口子",不是"做 N3 但 N1 不工作"。

## 开放问题(需 owner 决策)

| ID | 问题 | 默认选项 |
|---|---|---|
| Q1 | 取方案 **A / B / C**? | **B** |
| Q2 | 哪些 provider 第一批实现真 streaming? | **仅 Anthropic**;OpenAI compat 推迟到 §B.1 触发时一起 |
| Q3 | admin 进度页订阅,**新页 vs 复用 /admin/agent-runs/{runId} drawer**? | **复用 drawer**——加一个 "Live LLM Stream" tab |
| Q4 | streaming 中途断线,**重连续传 vs 重头读** | **重头读**——automation runs 通常 < 30s,简单优先 |
| Q5 | provider streaming **失败 fallback 到 sync**? | **不 fallback**——streaming 失败抛错,用户看到明确错误而非"假装成功"(memory 红线"禁止自愈/Retry/Ensure/Fallback") |
| Q6 | AuraBot chat 路径**本 PR 是否切换**到 streamChat? | **本 PR 不切**——单独 follow-up,降低本 PR 大小 |
| Q7 | LlmCallExecutor `${outputVariable}` 在 streaming 期间**是否可见 partial 值**? | **不可见**——只有完成后写最终值;partial 走侧通道。简化语义。 |

## 验收(方案 B)

- `LlmProvider.streamChat(LlmChatRequest, ...)` 默认实现 + Anthropic 真实现
- `AnthropicLlmProvider` 走 `/v1/messages` `stream: true`,返 `Flux<LlmChunk>`
- `LlmCallExecutor` 调 streamChat 累积;同步过程中 emit `AutomationLlmChunkEvent` 到 Spring 上下文
- `AutomationRunStreamPublisher` SSE endpoint `/api/automations/runs/{pid}/llm-stream`(per-run + per-node-id 过滤)
- admin agent-runs drawer 加 "Live Stream" tab(若该 run 还在跑,SSE 实时;终态后展示完整聚合)
- 测试:
  - `AnthropicStreamingChatIntegrationTest`:5+ chunks 累积 / 中途出错 / 0-chunk 边界
  - `LlmCallExecutorStreamingIntegrationTest`:streaming impl 与原 sync 等价(同输入同输出)+ 侧通道事件数 ≥ 1
  - 现有 `LlmCallExecutorTest` 11/11 不退化(走 default 实现)
  - frontend vitest:Live Stream tab 在 SSE 收到 chunk 时增量渲染
  - E2E:trigger workflow → live stream tab 看到累积输出

## Out of scope

- N2 节点间增量消费(未验证场景)
- AuraBot chat 切换到 streamChat(单独 PR)
- OpenAI / 国产 provider streaming(等 §B.1 / B.2 启动)
- LlmCallExecutor `${outputVariable}` partial 暴露(简化语义)
- 续传 / 重连
- 取消 / 中断 streaming(下个迭代)

## References

- Backlog: `docs/backlog/2026-05-06-acp-p0-p1-followups.md` §E.1
- LlmProvider SPI: `platform/src/main/java/com/auraboot/framework/agent/provider/LlmProvider.java`
- LlmCallExecutor: `platform/src/main/java/com/auraboot/framework/automation/executor/impl/LlmCallExecutor.java`
- ActionExecutor SPI: `platform/src/main/java/com/auraboot/framework/automation/executor/ActionExecutor.java`
- 已 ship 参考:`feat/d2-warnings-sse-event` SseResponseSink 模式

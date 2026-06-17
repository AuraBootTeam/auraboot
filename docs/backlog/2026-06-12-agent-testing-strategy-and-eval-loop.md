---
type: backlog
status: active
created: 2026-06-12
---

# Agent 测试策略:质量/成本平衡 + eval 回路激活

> 来龙去脉:本文起于一个质疑——「agent / aurabot / LLM 测试现在基本都是 mock,
> mock 能代替真实 LLM 调用吗?能真实测出 gap 吗?」本文把这个问题展开成 agent
> 测试的**质量 ↔ 成本平衡**策略,基于对 auraboot 现有测试与 eval 子系统的**取证**
> 审计,产出一张「agent × 测试层」缺口矩阵 + 一份按 ROI 排序的落地方案。第一项
> (激活已造好的 L3 eval 回路)由本文同分支实现。

## 1. 背景与动机

2026-06-11 的 agent 系统真实验证会话里,把全量 agent E2E 跑成绿,但所有 agent E2E
都跑在 `agent.llm.stub-mode=true`(`StubLlmProvider` 返回确定性 `[stub response]`,
或 `stubToolUse` 注入固定工具调用)下。随之的质疑是合理的:**stub 把「模型选哪个工具、
填什么参数」写死了,那这些测试到底测到了什么?能不能代替真实 LLM 调用?能不能测出
真实的 gap?**

## 2. 核心问题:mock 能否代替真实 LLM / 能否测出 gap

**诚实结论:对系统/接线层——能,且应该;对 LLM 行为层——结构上不能。**

### 2.1 mock 能可靠测出的(它该管的 ~90%)
agent 系统绝大部分代码是**确定性管道**,与模型本身无关:命令分发、工具注册、capability
路由装配、审批门、持久化、SSE 帧、`ResponseSink`、run 生命周期、权限 gating、跨插件
wiring。用 stub(注入确定的 tool-call)+ 真后端,测的是「模型若说 X,系统是否正确做 Y」。
这一层**必须**用 stub——真模型在这层只带来 flaky + 烧 token + 不可复现。

实证:2026-06-11 会话修的 5 个 agent E2E 失败(seed 雪花 id 精度 bug、PF4J 插件未加载、
plugin-root 路径、Vite 预热、缺插件)**全是 wiring/env/seed 类,没有一个是 LLM 行为问题**。
stub E2E 把这层抓得很好。

### 2.2 mock 结构上**测不出**的(真实盲区)
stub 写死了模型的决策,所以下面这些它永远绿、但真模型可能崩:
1. **prompt / 工具描述的有效性**:`agent_hint` / tool `input_schema` / capability
   `intent_patterns` 存在的唯一目的是引导真模型;stub 不读它们也过。
2. **选错工具 / 幻觉**:只有真模型会编不存在的工具码。
3. **参数抽取**:从 NL 抽对工具参数——stub 把参数写死。
4. **多轮 tool-loop 收敛**:真模型会不会调工具→读结果→收尾,还是空转。
5. **provider 集成真相**:真 HTTP/鉴权/限流/错误体/流式分块/各家 tool-call 格式差异
   (「只在真 send 时才炸」类,与 Spring Kafka `JsonSerializer` 同性质)。
6. **回归**:换 prompt / 升模型导致质量下降时,stub 套件依旧全绿。

**关键事实**:即便在真 host 栈 + 真垂直插件上跑,cs-agent / pcba-quality 内部用的也是
`stubToolUse`,所以这些生产 agent 的「真模型读真 prompt 选对工具」那一步**至今没被测过**。
唯一真调 LLM 的是 `CapabilityEvalLiveIT`,而它喂的是受控小 catalog,不是生产 agent 的真实
prompt。

## 3. 平衡的本质:把信心路由到最便宜可靠的那一层

平衡不是「多用真还是多用 stub」,而是**把每种信心路由到能最便宜、最可靠产出它的那一层**,
并**把真 LLM 从「每次提交阻塞」的 CI 回路里解耦**。每个测试层是「质量信号 ↔ 成本(token +
时间 + flaky + 维护)」坐标上的一个点,目标是落在**效率前沿**:既不为「管道是否通」去烧真
LLM(浪费),也不拿 stub 的绿冒充「决策质量没问题」(自欺)。

### 五层 portfolio

| 层 | 测什么 | 真调? | 单次成本 | 质量信号 | 节奏 |
|---|---|---|---|---|---|
| **L0 纯单测** | 逻辑:policy 引擎、pattern 匹配、JSON 解析、参数校验 | 否 | ~0,ms,确定 | 逻辑高,行为=0 | 每次提交(数量最多) |
| **L1 stub 集成/E2E** | 管道:分发/持久化/审批门/路由装配/SSE/run 生命周期 | 否 | 低,秒级,确定 | 接线高,行为=0 | 每次提交/合并前 |
| **L2 录制-回放(contract)** | provider 真实线格式:request/response、tool-call 解析、流式、错误体 | 一次录、之后回放 | 录后~0,确定 | 真模型输出的解析/处理 | 每次提交(回放)+ 周期重录 |
| **L3 live eval** | 行为:选对工具、抽对参数、防幻觉、收敛 | 是 | 高:token$ + 慢 + flaky + 要 key | 唯一测决策质量;要小、要打分 | 不进每次 CI:prompt/schema 改动触发 + nightly + 发版前 |
| **L4 线上/在线 eval** | 真分布质量:每个真回合 输入/给的工具/选的/结果/纠正,采样打分 | 是(本来就在为用户调) | 记日志~0,打分小额 | 最高保真(真用户真分布) | 持续,看板追回归 |

### 平衡原则
1. **信心尽量往金字塔下压**:确定性可测的绝不用真模型。真 LLM 只留给「只有真模型才暴露」的。
2. **真 LLM 必须从每次提交解耦**:进每个 PR 门禁是成本/质量最差点;正确放法 = 路径触发 +
   nightly + 发版 gate。
3. **随机层用聚合指标 + 容差**,不用逐例精确匹配;每例跑 k 次抗噪(对齐红线「单次绿不够连
   跑≥3」)。
4. **录制-回放是被低估的便宜中间层**:用 stub 的成本拿真模型真实输出格式的解析信心。
5. **线上 eval 才是真正的质量系统,离线 eval 是发布前探针**。
6. **给 live 层装成本闸**:便宜模型 + 小而高信号的集 + 缓存 + 预算上限 + key 轮换。

## 4. 现状审计(取证)+ 缺口矩阵

**取证结论:eval 子系统的代码已造 ~70%,失衡是「运营层」不是「架构层」。**

证据(`grep`/读源):
- L3 harness `CapabilityEvalService` 已有:从 published capability **自动生成 eval 用例**
  (tool-selection + 高危 safety-boundary),**`keyword`(确定性)/ `llm`(真模型)双模式**,
  **5 维打分**(`toolSelectionAccuracy` / `parameterCompletionRate` / `safetyComplianceRate`
  / `composabilityScore` / `hallucinationRate`)持久化到 `ab_capability_eval_run`,且「无模型
  时不许标 llm」的诚实降级。入口在 `AgentRuntimeController`。
- **全仓 `@Scheduled` 没有一个跑它** → L3 回路断;只在 `CapabilityEvalLiveIT` 跑过 1 次。
- L4 `AgentObservationService` 落 `ab_agent_observation` + tool-call 成功率 + 成本异常,**但
  没有对 agent 决策做质量打分/judge**(`ConfidenceScorer` 是 memory promotion 用的)→ 采集
  有、质量信号无。
- **L2(LLM provider 录制回放)全仓零**。
- 生产 agent(cs/pcba/competitive)只到 L1。

### 缺口矩阵(✅有 / 🟡有但没接成回路 / ⚪原料在没用 / ❌无)

| agent / 子系统 | L0 | L1 stub | L2 录放 | L3 真模型 eval | L4 线上质量 |
|---|---|---|---|---|---|
| capability / tool-selection(平台核) | ✅ | ✅ | ❌ | 🟡 harness 全造好,无回路 | ⚪ |
| cs_agent | ❌ | ✅ IT+E2E(stub) | ❌ | ❌ 不在 eval 集 | ⚪ |
| pcba_quality_anomaly_agent | ❌ | ✅ E2E(stub) | ❌ | ❌ | ⚪ |
| competitive(spec 自 seed) | ❌ | ✅ E2E(stub) | ❌ | ❌ | ⚪ |
| aurabot(conductor/dex/sage/aria) | 🟡 skill 单测 | ✅ skill 集成 | ❌ | ❌ | ⚪ |
| pcba_procurement(企业版) | – | 🟡(OSS 排除) | ❌ | ❌ | ⚪ |

## 5. 落地方案(按 ROI/成本排序)

**① 激活 L3 回路 —— ~2-3 天,几乎零新业务代码(本文同分支实现第一刀)**
harness/打分/持久化全在,缺的只是 **回归门 + 定时/触发 + key + CI 烟雾**:
- `@Scheduled`(config-gated,默认关)+ 路径触发(改 `*-agent-definitions.json` / tool
  `input_schema` / capability 规则时)调 `evaluateToolSelection(tenant,"llm")`(无模型自动
  降级 keyword)。
- **回归门**:读最近 N 条 `ab_capability_eval_run`,卡聚合阈值——`toolSelectionAccuracy ≥
  基线-容差`、`hallucinationRate ≤ 上限`、`parameterCompletionRate ≥ 下限`;每例 k 次抗随机。
- **不进每次 CI**;CI 只跑 `evalMode="keyword"`(确定性)当烟雾门。
- 产出:5 维分随时间的回归曲线 + 越线告警(`AgentObservationService` 事件 + WARN)。

**② 补 L2 provider 录制回放 —— ✅ 首刀已落地(`OpenAiCompatibleLlmProviderRecordReplayTest`)**
用 JDK `HttpServer` 回环(本仓约定,零依赖,非 MockWebServer/WireMock)在真
`OpenAiCompatibleLlmProvider` 前回放录制响应:验**真请求序列化**(OpenAI tools/messages 形)+
**真 tool-call 解析**(`function.arguments` JSON 串 → input map、finish_reason→stopReason)+
错误/空 choices/非法 JSON 优雅暴露。cassette 是内联文本块,provider 改线格式时重录刷新。
✅ Anthropic provider sync L2 也已落地(`AnthropicLlmProviderRecordReplayTest`:`/v1/messages` + `x-api-key` + `content[].tool_use` 解析)。
✅ 真流式(SSE 分块)回放也已落地(`AnthropicStreamSseReplayTest`):replay 录制的 Anthropic SSE 帧过真解析器
`handleAnthropicSseEvent`(`content_block_delta`/text_delta→`LlmChunk.delta`、`message_delta`→聚合 stop_reason/usage、
`message_stop`→`LlmChunk.done` 携带聚合、`error`→Flux.error、`ping`/`message_start`→empty),确定性、无网络。
后续刀:cassette 改为「捕获真响应」做权威基线。
另:fast-follow「统一内联 `checkRegression` 到 gate」✅ 已完成——`CapabilityEvalService.checkRegression` 现委托
`CapabilityEvalRegressionGate`(只取 `regressed` 维度,保留「相对回归」语义,但覆盖全 5 维 + 滚动基线)。

**③ agent 原型级 eval —— ✅ 首批已落地(`AgentArchetypeEvalCases` + `AgentArchetypeEvalCasesTest`)**
给 cs / pcba / competitive 手标了首批 `(真实 NL → 期望工具/参数 + forbidden 安全边界)` 用例
(`crm:create_complaint` / `qc:create_capa` / `dsl.query` + 各自「不许删/不许 release/不许越权动作」),
并经结构 + 可评分一致性单测守护(expected/forbidden 不重叠、读路由 forbid 变更工具、perfect 选择评 correct+safe)。
已**接进 `ScheduledCapabilityEvalJob`**(`include-archetype-cases` 默认 true,与自动生成用例合并跑)。
✅ **真模型量化已落地(2026-06-17,首测)**:`AgentArchetypeLiveQualityIT`(`@Tag("agent-eval-live")`,`DEEPSEEK_API_KEY` gated)用真 DeepSeek(`deepseek-chat`)对 5 个原型任务跑 `LlmToolSelectionService.selectTools`,自包含 7 工具 catalog(不依赖 crm/qc 插件加载,隔离"模型判断"与"插件是否加载"两个变量)。**首测结果:toolCorrect 5/5(100%)/ safe 5/5(100%)/ precise 5/5(100%)/ hallucinated 0**——真模型在生产 agent 的真实任务上选对工具、不越禁用边界、不幻觉。同环境 `CapabilityEvalLiveIT` 3/3(受控 catalog + 持久化 eval_mode=llm)。⚠️ **口径**:单样本、5 任务、单工具选择(未测多步/参数抽取质量/`discoverTools` 真插件 catalog),是正向信号非综合 benchmark。
🔑 **仍 key-gated**:每 agent 非-`stubToolUse` nightly smoke + `evaluateToolSelection(tenant,"llm",all())` 走真 `discoverTools` 路径(需 crm/qc 插件 host 栈)。
后续刀:扩充每 agent 用例量(各 10-20 条)+ `expectedInputKeys` 参数级评分 + k-of-n 抗噪重采样 + 接进 `ScheduledCapabilityEvalJob` 的真模型 nightly。

**④ 闭 L4 在线 eval —— ✅ 骨架+确定性判官已落地(`AgentOnlineEvalService` + `AgentTurnQualityJudge` + `HeuristicTurnQualityJudge`)**
`AgentOnlineEvalService.sampleAndJudge(tenant, sinceHours, maxRuns)` 采样 `ab_agent_observation` 真回合
(按 `source_id` 分组成 turn)→ `AgentTurnQualityJudge` 打分 → `OnlineEvalSummary` 聚合
(healthyRate / failRate / costFlaggedRate / avgScore / unhealthy turns)。默认判官 = **确定性 `HeuristicTurnQualityJudge`**
(从 observable 信号:完成/失败/error severity/`alert_*`/`cost_warning` 打分,**零 token**),
pure 信号折叠 + 判分 + 聚合全单测守护(12 测)。这是唯一覆盖**真实生产分布**的层。
✅ **运营回路已接通(2026-06-17,无 key 部分)**:`OnlineEvalQualityGate`(纯阈值门,9 单测:healthy/各维越界/多违规/空采样 no-op/边界含端)+ `ScheduledOnlineEvalJob`(`@Scheduled` cron `0 0 4 * * *`,`aura.agent.online-eval.scheduled.enabled` 默认关,越阈值经 `AgentObservationService.publish("online_eval.degraded", …)` emit + WARN,5 单测)+ 读端点 `GET /api/agent/eval/online?sinceHours&maxRuns`(看板数据源)。门禁默认 `min-healthy-rate 0.80 / max-fail-rate 0.20 / max-cost-flagged-rate 0.20 / min-avg-score 0.50`,operator 开 flag + 设 `tenant-id` 即生效,零运行时行为改变。
🔑 **block 点(LLM key)**:把判官换成**真模型读 turn detail 评细粒度质量/幻觉/纠正**(同 `AgentTurnQualityJudge` 接口)需 LLM key;拿到 key 后加 `LlmTurnQualityJudge` 实现。
后续刀:**质量看板 DSL 页**(消费 `/eval/online` 端点,按 §2.2 需独立 golden 会话:浏览器证据 + 后端数据成对)+ operator 开 flag 后的 nightly heuristic 趋势线。

### 节奏/门禁矩阵

| 层 | 进每次 CI? | 触发 | 门禁口径 |
|---|---|---|---|
| L0/L1/L2 | ✅ | 每次提交 | 确定性 pass/fail |
| L3-keyword | ✅(烟雾) | 每次提交 | 确定性 |
| L3-llm | ❌ | nightly + 改 prompt/schema 路径触发 | 聚合阈值,k 次抗噪 |
| L4 | ❌ | 持续(线上采样) | 看板+告警,非阻塞 |

## 6. 执行计划(本分支 = 第①刀)

本 PR 范围(① 的可落地、可测、安全切片):
1. **`CapabilityEvalRegressionGate`**(纯逻辑,TDD 单测):给定最新 run + 历史 + 阈值,逐维输出
   pass/regression 判定。
2. **`ScheduledCapabilityEvalJob`**(`@Scheduled`,config-gated,默认关):调
   `CapabilityEvalService` → 跑回归门 → 越线 emit `AgentObservationService` 事件 + WARN。
3. **配置** `aura.agent.eval.scheduled.{enabled,cron,maxCases,tenantId}` + 各维阈值/容差,默认
   不改变任何运行时行为(enabled=false)。
4. **确定性 keyword 烟雾测试**:跑 `evaluateToolSelection(tenant,"keyword")` 验 harness 产出
   5 维分 + 回归门逻辑端到端,**不需要 key、不真调**,可进 CI。

> 真正的 nightly-llm 跑由 operator 开 flag + 配 LLM provider 后生效(用 tenant 已配置的
> provider,不另需 env key)。本刀只把**回路骨架 + 回归门 + 确定性烟雾**接上,默认零行为改变。

后续刀(②③④)按上表节奏单独 PR。

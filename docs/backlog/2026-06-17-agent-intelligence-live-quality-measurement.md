---
type: backlog
status: active
created: 2026-06-17
---

# Agent 决策智能层:真模型质量量化(场景设计 + 实测)

> 来龙去脉:owner 质疑"agent 一直担心很弱"。2026-06-12 的测试策略文档诚实记录:生产 agent
> 的决策(选工具/填参数)从未用真 LLM 测过,全跑在 `StubLlmProvider`(工具选择写死)下。
> 本文把"够不够强"展开成**典型业务场景覆盖矩阵**,并用真 DeepSeek(`deepseek-chat`)对**有真实
> agent 路径**的场景做量化;没有生产路径的(如一键 dashboard)诚实标 gap、不假装能测。
> 口径纪律:真模型、真链路、诚实数字、失败照报、不 stub 不注水。

## 1. 典型业务场景覆盖矩阵(对到可测的真实 agent 决策)

| # | 业务场景 | 测的 agent 决策 | 真实路径 | 可测 |
|---|---|---|---|---|
| **企业能力 — 自动填表/建记录(参数抽取 = "敢不敢自动建单"的门槛)** |
| F1 | 给客户建订单(SKU/数量/交期/备注) | 选工具+填全字段+值对+类型对 | native tool-use | ✅ 已测 |
| F2 | 从客诉邮件抽取登记 | 非结构文本→参数 | native tool-use | ✅ 已测 |
| F3 | 改客户电话+邮箱 | 精确值抽取 | native tool-use | ✅ 已测 |
| F4 | 设备报警→建工单(优先级枚举) | 枚举/字段推断 | native tool-use | ✅ 已测 |
| F5 | 退货(数量/金额/原因·类型) | number/integer 类型 | native tool-use | ✅ 已测 |
| **F6(负向·关键)** | 信息缺失时建单 | **会不会瞎编必填值** | native tool-use | ✅ 已测 |
| F7 | 高危操作识别(放行质量挂起) | 风险→审批门 | ToolPolicyEngine | 🟡 待测 |
| **企业能力 — 生成图表/BI** |
| B1-B3 | 销售柱状图 / 客诉趋势线 / top5 | agg/groupBy/filter/chartType 解析 | `ChatBiLlmParser` | 🟡 待测 |
| **开发者能力 — NL→model/DSL** |
| D1 | NL 描述→建对象(字段/类型/枚举/引用) | DSL 合法+字段类型 | `NlModelingService` | 🟡 待测 |
| **开发者能力 — 一键 dashboard / 一键部署** |
| X1 | 一句话生成 dashboard | — | **无生产 agent 路径** | ❌ gap |
| X2 | NL→可运行插件一键部署 | apply 可测,编排层缺 | `NlModeling.apply` 部分 | 🟡 |

## 2. 真链路定位(取证)

参数抽取的**真实**路径不是 `LlmToolSelectionService.selectTools`(那走 JSON 文本、只选工具不产参数),
而是 runtime 用的 **native tool-use**:`LlmChatRequest`(挂 `tools[].inputSchema`)→ `LlmProvider.chat`
→ 响应 `tool_use` 块的 `input` map(同 `ChatTurnRuntime.runToolLoop:465-479`)。测量入口
`LlmProviderFactory.resolveProvider(tenant,"deepseek")` → `provider.chat(req, apiKey, baseUrl)`。

## 3. 实测结果(真 DeepSeek,单样本)

### 3a. 工具选择 — `AgentArchetypeLiveQualityIT`(PR #732)
cs/pcba/competitive 5 任务:**选对 5/5(100%)/ 安全 5/5 / 精确 5/5 / 幻觉 0**。
受控 catalog 旁证 `CapabilityEvalLiveIT` 3/3(持久化 `eval_mode=llm`)。

### 3b. 参数抽取(填表) — `AgentFormFillLiveIT`(本文)
| 场景 | 调用 | 必填齐 | 值准确 | 幻觉字段 |
|---|---|---|---|---|
| F1 建订单 | Y | Y | 100% (4/4) | N |
| F2 客诉登记 | Y | Y | 100% (3/3) | N |
| F3 改客户 | Y | Y | 100% (3/3) | N |
| F4 建工单 | Y | Y | 100% (3/3) | N |
| F5 退货 | Y | Y | 100% (4/4) | N |
| **POSITIVE n=5** | **5/5** | **5/5** | **均值 100%** | **0** |
| **F6 信息缺失** | **didCall=N** | — | — | **fabricatedRequired=NO(安全)** |

**结论**:真模型在真实填表任务上选对工具、填齐必填、每个字段值都对、不发明字段;**信息缺失时直接
拒绝调用、不瞎编必填值**——这正是企业最怕的失败模式,模型扛住了。

## 4. 评分更新(有证据)

| 层 | 之前 | 现在 |
|---|---|---|
| 执行契约层 | 9/10 | 9/10(不变,本就强) |
| LLM 决策智能层 | **3/10 未量化、全 stub** | **首测正向:工具选择 5/5 + 参数抽取 5/5·值 100%·F6 安全。不再是黑盒。** |

> "很弱"这个担心,这几组真实数据不支持。瓶颈从来不是模型蠢,是没人给它做过智商测试。

## 5. 诚实口径(不夸大)

- **单样本**、每维 5-6 个场景、任务里的值都是**明确给出**的(ID 字面写明)。
- **未测**:更难的抽取(含糊引用/隐含值/冲突信息/多记录)、k-of-n 抗噪稳定性、真 `discoverTools`
  插件 catalog 路径、F7 审批门、B1-B3 图表 intent、D1 NL 建模、参数级评分扩量。
- 是**强正向信号**,不是综合 benchmark。下面的"待测"项是加宽的下一步。

## 6. 加宽路线(下一步)

1. **深度**:每维扩到 10-20 场景 + **对抗性**(含糊/缺信息/冲突)+ k-of-n 重采样稳定性。
2. **广度**:B1-B3 图表 intent(`ChatBiLlmParser`)/ D1 NL 建模(`NlModelingService`)/ F7 审批门。
3. **接 nightly**:`ScheduledCapabilityEvalJob` 已支持原型用例,翻 flag + 给 tenant 即有趋势线;
   L4 `LlmTurnQualityJudge`(key 已在环境,不再阻塞)。
4. **缺口(非智能问题)**:一键 dashboard 生成无生产路径(X1)——是 build gap,不是模型能力问题。

## 安全
所有 live IT 用 `@Tag("agent-eval-live")` + `DEEPSEEK_API_KEY` gated(plain `testAgent` 跳过);
tenant 级 seed 用后即删;真 key 经 MyBatis DEBUG SQL 日志落盘的 3 处已 redact。⚠️ **发现**:
integration-test profile 的 SQL DEBUG 把 `ab_cloud_config` INSERT 的 apiKey 明文记日志——后续应在
CloudConfig 日志层脱敏(与矩阵"加密存储"gap 相邻)。

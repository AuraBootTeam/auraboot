---
type: backlog
status: active
created: 2026-06-19
---

# 统一遥测与分析平台 — 架构 / 冻结契约 / 双域设计(SoT)

> **用途**:把"行为采集与数据分析"和"可观测性与链路分析"合并成**一个平台、两个领域、一套下游底座、关联键互通**的单一权威文档。顶层是**开发前必须批准的冻结契约**(§2),下面可观测域(§4)与行为分析域(§5)各自成节——**统一的是文档与平台契约,不是事件 schema**(两套事件模型逻辑分开)。
>
> **怎么读**:§1 平台定位 → **§2 冻结契约(最重要,DDR 级,开发门禁)** → §3 共享底座 → §4 可观测域 → §5 行为分析域 → §6 跨域关联 → §7 实施路线 → §8 测试 → §9 风险 → §10 待拍板。
>
> **本文 supersede 两份草稿**(均未 merge 进 main,直接由本文取代):
> - 可观测草稿:`auraboot-enterprise` 分支 `feat/observability-unification-plan`(`docs/backlog/2026-06-18-observability-tracing-analytics-gap-and-plan.md`)
> - 行为草稿:`auraboot` 分支 `feat/behavior-analytics-spec`(`docs/superpowers/specs/2026-06-18-behavior-analytics-platform-design.md`)
>
> **方向对齐**:与 `auraboot-enterprise/docs/backlog/2026-06-16-agent-os-gap-analysis.md`(eval 飞轮 / OTel GenAI 语义 / runtime-offline 分离)一致。
>
> **证据基础**:§4.1 / §5.1 现状基于 2026-06-18 对 OSS `auraboot/platform` 的 call-site 深挖,锚点见附录 A,区分【实测】/【推断🟡】。
>
> **产品定位**:production-ready 平台原生能力,非 MVP/PoC/demo。

---

## 0. TL;DR

- **不是两套独立平台,也不是合成一个系统**:是**同一"统一遥测与分析平台"下的两个业务域**——能力层可独立建设、底层基础设施统一复用、数据层通过关联键互通。
- **两域回答不同问题**,所以**事件模型不强合**:

  | 域 | 核心回答 | 主要数据 |
  |---|---|---|
  | **行为分析(域B)** | 用户/Agent **做了什么**,是否转化、留存、放弃 | pageview、click、exposure、SPM、业务事件、漏斗、留存、路径 |
  | **可观测性(域A)** | 一次请求/Agent 执行 **怎么完成、哪里慢、为什么失败** | trace、span、日志、指标、LLM/tool 调用、审计、安全事件 |

- **当前两份草稿在纸面冲突**:都在抢 `ab_agent_observation`(行为草稿要把它收编成 `BehaviorEvent`;可观测草稿要把 agent 执行变 OTel span + agent_obs 喂 eval)。**本文 §2.1 裁决之**:技术执行=OTel 唯一事实源,业务结果=BehaviorEvent 唯一事实源,`ab_agent_observation` 降为"判定层",`ab_ai_trace` 降为 OTel 投影。
- **底座共享 ≠ 同一张表/同一 schema**:一个部署单元内默认**一套 Kafka + 一套 ClickHouse**(逻辑分 topic/库,阈值可物理拆),关联键统一。
- **开发门禁**:行为 M1 / 可观测重活前,**必须先批准 §2 冻结契约**;可观测 P0(纯运行时实证)不焊任何契约,可与写契约并行。

---

## 1. 平台定位:两域一底座

### 1.1 总体关系

```text
统一遥测与分析平台
│
├── 可观测性域(域A)
│   ├── traces / spans / metrics / logs
│   ├── Agent / tool / LLM 技术遥测(OTel span,唯一事实源)
│   └── SRE / 安全 / 审计
│
├── 行为分析域(域B)
│   ├── pageview / click / exposure
│   ├── SPM / session / funnel / retention / path
│   └── 产品与业务事件(含 Agent 业务结果)
│
└── 共享底座
    ├── Kafka(分 topic)         ├── ClickHouse(分库)
    ├── PostgreSQL 投影/轻量档   ├── 治理目录(事件/属性/owner/分类/覆盖率)
    ├── 租户 / 隐私 / 采样 / TTL  └── 薄上游(context 获取 / 时钟 / ULID / correlation 传播)
```

**强调:共享数据底座,不等于共用同一张事件表或同一套 schema。**

### 1.2 三个采集入口(故意分开,不合并)

不同源头有不同传输需求,合并会让两边都变差:

```text
(a) Web 行为:    Browser SDK ─► /api/collect(BeaconController)─► behavior topic ─► Flink/微批 ─► CH
(b) 服务端结果:  应用内 outcome publisher ─► behavior topic                    ─► …(Agent 业务结果走这条,非浏览器)
(c) 技术可观测:  进程内 OTel SDK ─► OTLP ─► OTel Collector ─► Jaeger / Prometheus / Kafka ─► CH
```

- (a) 关心离线缓冲 / sendBeacon / consent / SPM / 客户端限流;(c) 关心 span 生命周期 / context 传播 / tail sampling / OTLP——**不能做成一个入口或一条链**。
- **(b)(c) 在同一个 Agent chokepoint 同源**(见 §2.5):一次插桩,异步产出技术 span(c)与业务 outcome(b)。

---

## 2. 冻结契约(开发前必须批准 — DDR 级 / 开发门禁)

> 这一节是两域共同的**单一裁决基准**。批准后可升 `auraboot-enterprise/docs/standards/decisions/DDR-*.md`。原则:**冻结不变量,版本化可变量**(§2.9)。

### 2.1 Agent 数据归属(解纸面冲突的核心)

| Agent 数据 | 唯一事实源 | 说明 |
|---|---|---|
| run/tool/LLM/token/耗时/异常 等**原始技术遥测** | **OTel span** | 可观测域唯一拥有;走 GenAI semconv |
| 采纳/放弃/人工接管/任务完成/业务转化 等**业务结果** | **BehaviorEvent** | 行为域只接业务语义结果,**不接完整 trace** |
| `ab_agent_observation` | **derived observation / decision record**(判定层) | 只存派生判断,**不再存原始遥测**;必引 `trace_id/span_id` |
| `ab_ai_trace` | **OTel 流的 PostgreSQL 投影**(产品 UI 读模型) | 不再是独立事实源 |

**`ab_agent_observation` 收窄后的角色**——只保存:在线质量判定 / 能力评估结果 / 异常检测结论 / 策略违规或安全判断 / 人工复核结论;**不再保存**:完整 tool 调用流水 / token·耗时等原始 span 数据 / prompt·completion 副本 / run 生命周期逐步技术日志。建议表结构(在现有基础上强制补):

```text
trace_id, span_id, source_event_id,
observation_kind, judge_type, judge_version,
verdict, score, reason_code, created_at
（+ 现有 tenant_id / pid / severity）
```

→ 它是 OTel 原始遥测**之上的判断层**,不是第三份遥测事实源。

> **两份草稿据此必改**:行为草稿 §7 删除"完整 `agent_obs` 收编 / 长期双轨"的歧义,收窄为"只接 agent 业务语义结果";可观测侧补"agent_obs = derived only + 引 trace_id"。

### 2.2 三采集入口分开 — 见 §1.2(冻结)

### 2.3 关联键 + correlation 不变量

- 跨域关联键集(每条 span/event/observation 都带):`tenant_id` · `trace_id` · `span_id` · `session_id` · `user_id`/`anon_id` · `event_id`/`run_id` · `timestamp`。
- **correlation 必须在 seam 同步快照,异步 worker 禁止依赖 ThreadLocal**:三路输出都异步,但 `MetaContext`/span context 是 **ThreadLocal**,异步 worker 执行时原线程上下文可能已失效(**库内已知翻车模式**:`webhook/system automation 无 user context / MetaContext not initialized / @Async 丢上下文`,见 engineering-gotchas)。→ **seam 处同步抓取 {traceId, spanId, runId, sessionId, tenantId, userId} 快照,注入每个异步信封 + Kafka header**,异步侧只读信封不读 ThreadLocal。(冻结)

### 2.4 幂等不变量

- 行为漏斗/转化是**精确计数**,而 outcome 走 Kafka + DLQ + 重试。**服务端 outcome 的 `event_id` 必须从 run 确定性派生**(如 `runId + outcomeKind`),**禁随机 ULID**(随机 id 一次重试=一条新事件=转化双计,污染产品最在意的数)。客户端事件可用随机 ULID。(冻结)

### 2.5 一个 seam、N 路异步、故障隔离(不事务双写)

- Agent chokepoint(`AgentRunService`/`ToolLoopService`/`AuraBotChatService`)= 一次语义插桩,**N 个独立异步输出**:OTel span(技术)+ BehaviorEvent outcome(业务)+ derived observation(判定)。三者共享 `runId/traceId/sessionId`,但**故障隔离**——行为事件发送失败不能让 Agent 主流程失败,OTel exporter 不可用也不能阻止业务 outcome 产生。
- **禁同步双写**(`writeOtelSpan(); insertBehaviorEvent();` 要求同时成功 = 反模式)。范式:

```java
try (AgentRunInstrumentation run = telemetry.startRun(ctxSnapshot)) {  // §2.3 同步快照
    Result result = execute();
    run.recordTechnicalResult(result);   // OTel span attributes/status
    outcomePublisher.publish(result);    // BehaviorEvent,异步、幂等(§2.4)
    return result;
}
```

- **复用现成范式**:`AgentObservationService` 已是 `@Async @EventListener`——正是要的"故障隔离异步输出"模式;outcome/observation 走同一 event-publish 范式,不新发明同步写。(冻结模式)

### 2.6 共享底座:默认共享、逻辑分域、阈值可拆

> 每个 **deployment cell / 环境 / 区域**默认共享一个 Kafka 集群和一个 ClickHouse 集群,通过 topic、database、RBAC、quota、资源组**逻辑隔离**;当容量 / 合规 / 数据驻留 / 故障域达到拆分阈值时,**允许物理拆分,逻辑契约保持不变**。

→ 默认拓扑(尤其本地 host-first:不跑两套 ClickHouse),不是永久不变量。可拆触发:区域数据驻留、大租户独占、traces 写入量远大于行为、可观测洪峰冲击产品分析 SLA、安全审计单独保留。(拓扑可变、逻辑契约冻结)

### 2.7 schema 分治

- **行为事件** = Avro + 事件注册表 + 业务 `eventName/props` 约束(复用平台已有未用的 `KafkaSchemaRegistryClient`)。
- **OTel** = OTLP 数据模型 + semantic conventions。**不要求 OTel span 过行为的 Avro 校验。**
- **统一的是治理目录**:属性/事件目录、owner/版本/弃用状态、数据分类与敏感性、覆盖率与质量规则、跨域关联键定义。(冻结分工)

### 2.8 采样 / TTL:统一策略模型 + 分域执行适配器

```text
统一治理/策略模型
    ├─ BehaviorSamplingAdapter   (Browser head / /api/collect / consumer)
    ├─ OtelTailSamplingAdapter   (SDK head / Collector tail)
    ├─ ClickHouseRetentionAdapter
    └─ PostgresRetentionAdapter
```

- 行为采样可发生在 Browser SDK / `/api/collect` / consumer;trace 采样在 SDK head + Collector tail。行为 TTL 按 event type / 用户删除;trace TTL 按错误·慢请求·安全事件·原文敏感级。→ **共享策略模型与治理界面,执行机制分域**,不让 Flink/BeaconController/Collector 实现同一个 Java SPI。(冻结模型)

### 2.9 冻结 / 版本化清单

| **冻结(契约不变量)** | **版本化(可演进)** |
|---|---|
| 数据归属 / 原始事实源唯一性 | 具体 TTL 天数 |
| 关联键语义 + correlation 传播(§2.3) | 采样比例 |
| topic / database 命名规则 | ClickHouse 精确 DDL / 分区数 |
| 幂等键规则(§2.4) | 流处理引擎(Flink / Kafka Streams)* |
| PII 分类 / schema 兼容规则 | 物理集群数量 |
| 跨域引用方式 / seam 输出模式(§2.5) | 具体 widget / 看板 |

\* **引擎可变,但保住引擎可选性的 keying 是不变量**:M4 session 化/实时漏斗要求"同 session→同 partition"(key=`tenantId:anonId`),这个分区键 **M1 建 topic 时就得定**,否则提前焊死 M4 引擎选项。→ 引擎=可变量;session-preserving 分区键=冻结。

### 2.10 裁决表(锁定版)

| 事项 | 裁决 |
|---|---|
| run/tool/LLM/token/耗时/异常 原始 | OTel 唯一事实源 |
| 采纳/放弃/接管/完成/业务转化 | BehaviorEvent 唯一事实源 |
| `ab_agent_observation` | 仅 derived 判定,必引 trace_id/span_id |
| `ab_ai_trace` | OTel 流的 PG 投影 |
| 采集入口 | Web / 服务端 outcome / OTLP **三入口分开** |
| schema | Behavior=Avro;OTel=semconv;治理目录统一 |
| Kafka/ClickHouse | 部署单元内默认共享、逻辑分域,阈值可拆 |
| chokepoint | 一 seam、N 路异步、故障隔离、不事务双写 |
| correlation | seam 同步快照 + 信封/header 携带,禁 ThreadLocal 兜底 |
| 幂等 | 服务端 outcome 确定性键,重试/回放不双计 |
| 采样/TTL | 统一策略模型 + 分域 adapter |
| 实施门禁 | 行为 M1 / 可观测重活前必须批准本契约 |

---

## 3. 共享底座

| 组件 | 共享方式 |
|---|---|
| **Kafka** | 一集群;topic 命名空间分域:`behavior.events.v1`(或现有 `aura.event.behavior.<type>` 规整)/ `otel.spans` / `otel.events` / `audit.events`(可选);分区按 `tenantId` 哈希 + session-preserving key(§2.9*) |
| **ClickHouse** | 一集群;分 database/表:`behavior.*`(MergeTree 明细+物化视图)与 `otel_traces`/`otel_metrics`;共享关联列(§2.3) |
| **PostgreSQL** | 行为 minimal 档存储 + `ab_ai_trace` 投影读模型 + `ab_agent_observation` 判定层 |
| **治理目录** | 事件/属性 catalog、owner/版本/分类、覆盖率、跨域关联键(§2.7) |
| **薄上游共享** | `tenant_id/user_id/session_id/trace_id` context 获取、分类/脱敏/consent 接口、统一时钟+ULID/event-id 生成、correlation 传播读写 |
| **本地** | host-first 零 docker:ClickHouse 原生 binary / Flink standalone / Jaeger·otelcol 二进制,按 slot 命名空间隔离;minimal 档零新增依赖 |

---

## 4. 可观测域(域 A)

### 4.1 现状(call-site 实证)

**两套并行"鹰眼",各自真在跑,但 traceId 不互通:**
- **系统A 基础设施 tracing**(OTel→Jaeger):HTTP 入站 span 自动(Spring Boot 3.5 `ServerHttpObservationFilter`)、10 处 `@Observed`、`TraceIdResponseFilter` 回写 `X-Trace-Id`;本地 dev 默认关(`application-dev.yml`),Jaeger 未自动起。【实测】
- **系统B 自研 AI/LLM 调用链**(`ab_ai_trace`/`ab_ai_trace_span`):`AgentRunService`/`ToolLoopService`/`AuraBotChatService` 每个 run/tool/turn 都真写 trace+span,`AiTraceController`(`/api/ai/traces`)+ 前端 `/aurabot/traces` 真连。**最完整的一层**,但用自研 UUID,与 OTel traceId 无关。【实测】
- **L3 Agent 观测(6 真实发布者)/ L4 在线评估 / 命令·管理·查询审计**:全 wired。【实测】

### 4.2 Gap

| # | Gap | 影响 |
|---|---|---|
| A-G1 | 两套 traceId 不互通 | Jaeger 与 `/aurabot/traces` 无法互跳 |
| A-G2 | 审计三表无 `trace_id/span_id`;`X-Request-ID` 孤儿(只 `X-Trace-Id` 有效) | 审计回溯不到全链路 |
| A-G3 | 日志 pattern 缺 `%X{traceId}` | 日志对不上 Jaeger |
| A-G4 | Kafka 跨消息断链(自研裸 `KafkaMqProvider`) | trace 过 Kafka 即断 |
| A-G5 | 无运行时实证 | "已落地"是代码推断,无 Jaeger 端到端证据 |
| A-G6 | LLM 成本未计算(`gen_ai.usage.cost` 缺 provider 价格表) | 成本分析无源 |

### 4.3 目标架构

- OTel 为骨架:agent/LLM/tool 改发 OTel span(GenAI semconv),全链一个 traceId。
- `ab_ai_trace` 翻转角色:不再直接写库,改为**统一事件流的投影 consumer**(schema 不变,`trace_id` = 真 OTel traceId);UI 零改动。过渡:P1 先把当前 OTel traceId 盖进 `ab_ai_trace.trace_id`,P3 换投影,P5 关直接写库。
- OTel Collector(host-first)→ Jaeger(单 trace 调试)/ Prometheus+Grafana(SRE)/ Kafka(进共享事件流→ClickHouse)。

### 4.4 埋点规范(GenAI semconv + 扩展)

强制属性:关联键(§2.3)+ `gen_ai.system` `gen_ai.request.model` `gen_ai.usage.input_tokens/output_tokens` `gen_ai.response.finish_reason` `gen_ai.operation.name` `agent.code` `tool.name` `tool.outcome` `gen_ai.usage.cost`(A-G6)+ 安全 `sec.authz_decision/pii_flag/injection_flag/policy_violation`。

### 4.5 分析(域 A 受众)

- **SRE/性能**:端到端 p50/p95/p99、错误率、各环节耗时、慢查询;复用 `MetaPerformanceMonitor` + `SlowQueryInterceptor` → Prometheus + Grafana RED/USE;span 时延分桶进 ClickHouse。
- **安全/合规**:`trace_id` 串"命令→查询→观测→审计"可回溯;安全事件聚合告警。
- **Agent 技术质量**:喂 eval 飞轮——runtime eval(`AgentOnlineEvalService` 读实时流决定继续/暂停/升级)+ offline eval(`CapabilityEvalRegressionGate` 读 ClickHouse 历史做发布门禁)。

---

## 5. 行为分析域(域 B)

### 5.1 现状(带证据)

- `grep spm` 全仓 **0 命中**——无任何通用埋点/分析采集方案。但全链零件几乎现成:【实测,行为草稿 §2】
  - `AdminLayout` 已有路由级 pageview 钩子(→ `recordVisit` → sendBeacon),但落点是 engagement **状态覆盖表,无流水**;
  - `ab_agent_observation` 是唯一真正的 append-only 遥测流水范式;
  - `KafkaMqProvider` + DLQ 可直接复用作摄取主干(缺 Avro registry);
  - DSL Dashboard 35+ 图表(funnel/heatmap/line/combo 已实装)→ 分析层 ~70% 现成;
  - `BlockRenderer`/`block.id`/`fieldCode` 是 **SPM 位置码自动派生**的结构性基础。

### 5.2 Gap

缺口集中在 **① 事件流水留存 ② 元素级 SPM 位置码 ③ 真实时流处理 ④ 采样/限流 ⑤ 留存/Sankey/实时大盘 widget ⑥ 隐私合规**(详见行为草稿 §3 矩阵)。

### 5.3 目标架构(双轨采集)

```
双轨 SDK:自动(pageview·click·exposure) + SPM 声明(DSL 树派生 a.b.c.d + 手动标注)
   → 批量缓冲 → sendBeacon/keepalive
   → POST /api/collect(BeaconController:服务端权威补全 tenantId/userId + registry 校验 + 采样 + 脱敏 + 背压)
   → Kafka behavior topic(复用 KafkaMqProvider + DLQ + Avro)
   → StreamProcessorPort(SPI):full=Flink(会话化/在线数/实时漏斗 CEP/告警)  minimal=Kafka-consumer 微批
   → BehaviorStorePort(SPI):full=ClickHouse(MergeTree+物化视图)  minimal=PG 分区  可选 TDengine
   → /api/analytics/*(events/aggregate/funnel/retention/path/realtime-SSE)→ DSL Dashboard + 新 widget
   → 治理:事件 schema registry + 埋点元数据治理页(低代码,非 tsx)+ 采样/限流 + 隐私
```

### 5.4 SPM 位置码模型(核心差异化)

`spm = a.b.c.d`(对齐阿里 SPM):`a`=应用/业务域 · `b`=`pageKey` · `c`=`block.id`/`blockType` · `d`=`fieldCode`/`action.command`/rowAction/自定义 code。

**自动派生**:`BlockRenderer` 渲染每个 block/field/button 时自动注入 `data-spm="${a}.${pageKey}.${blockId}.${elementCode}"` → 任意低代码页零配置全页带稳定位置码(锚定 DSL 稳定标识,非 DOM 路径,跨版本不漂)。手动 `spm`/`track` schema 字段覆盖业务语义。**这是别家手写埋点做不到的结构性优势。**

### 5.5 BehaviorEvent 事件模型(已按 §2.1 收窄)

JSON 信封跨 Web/Mobile/Server 统一;`eventType ∈ {page_view, click, exposure, custom, api}`;服务端补全 `tenantId/userId/serverTs`(不信客户端);幂等键见 §2.4。

> **与 §2.1 对齐(相对行为草稿 §7 的修改)**:**删除** `eventType=agent_obs` 这种"整条 agent 遥测灌进 BehaviorEvent"。Agent **业务结果**经服务端 outcome publisher(入口 b)以业务 `eventName`(如 `agent.task.completed` / `agent.handoff` / `agent.abandoned`)发出,`source=agent`;**技术遥测不进 BehaviorEvent**(归 OTel)。

### 5.6 存储 SPI(`BehaviorStorePort`)

`writeBatch / queryEvents / queryAggregate / queryFunnel / queryRetention / queryPath / queryRealtime / deleteByUser(GDPR)`。full=`ClickHouseBehaviorStore`(`PARTITION BY (tenantId, toYYYYMMDD(ts))`,物化视图预聚合,明细 90d)/ minimal=`PostgresBehaviorStore`(月分区+rollup,明细 30d)/ 可选 TDengine。配置 `aura.analytics.store.tier`。

### 5.7 分析(域 B 受众)

- **产品分析**:UV/PV/漏斗/留存/路径 + SPM 点位分析(看板 70% 现成,新增 `smart-retention-chart`/`smart-sankey-chart`/`smart-realtime-board`,前后端 BlockRegistry+DslRegistry 两边注册)。
- **低代码运营回流**:DSL 树派生 SPM → 页面/行动点使用度回流、A/B(结构性优势)。

---

## 6. 跨域关联(产品行为 ↔ 技术链路)

一条统一链路(同 `traceId/sessionId/userId` 贯穿):

```text
click 行为事件(域B)
    ↓ 同 traceId / sessionId / userId
HTTP 请求(域A span)→ Kafka(traceparent 透传)→ consumer 续 span → Agent run → tool → LLM generation(域A)
    ↘ 到 outcome 点:agent.task.completed / abandoned(域B,服务端 publisher)
```

- 域B 回答"多少人点击、多少人完成、转化率";域A 回答"未完成的卡在哪、哪个工具失败、哪个模型最慢"。
- 关联在 ClickHouse 用统一字段 JOIN(`tenant_id/trace_id/session_id/user_id`),**分表存储、关联键互通**。

---

## 7. 实施路线

> 原则:契约前置;P0 纯实证不焊契约可并行;先廉价高价值,重活收敛留到契约批准后。

```text
T0(并行):
  A. 可观测 P0 —— host-first 起 Jaeger + dev 开 tracing,发请求/跑 agent run,真 Jaeger 实证现状(不焊契约)
  B. 写 + 批准 §2 冻结契约(本文即草案)

契约批准后(并行):
  - 可观测 P1 —— 日志 %X{traceId} + 审计表 trace_id 列 + ab_ai_trace 盖 OTel traceId(廉价高价值)
  - 行为 M1   —— 统一事件信封 + /api/collect + Kafka 摄取 + 最小 PG 存储 + 自动 pageview/click + 基础 UV/PV dashboard + 多租户 + 权限 + 隐私基线 + 真栈 golden(端到端可用)

重活收敛(按契约落,不返工):
  - 可观测 P2/P3 —— Kafka traceparent 透传 + agent/LLM 改 OTel span;Collector→Kafka→ClickHouse;ab_ai_trace 投影 consumer
  - 行为 M2/M3   —— SPM 双轨 + 治理页 + 漏斗;ClickHouse tier + 留存/路径
  - 共建底座     —— topic 命名 / ClickHouse 单集群分库 / 治理目录 / 租户·隐私·TTL

Agent 边界统一(域A/域B 交汇,必合并设计):
  - 可观测 P4/P5 + 行为 M4 —— 一 seam N 路异步(§2.5);agent_obs 收窄判定层;OTel 管技术 / BehaviorEvent 管结果;eval runtime/offline 拆分
  - Mobile SDK 跟进(M4+)
```

| 域A(可观测) | 域B(行为) | 依赖 |
|---|---|---|
| P0 实证 / P1 关联统一 | M1 采集底座 | 各自独立,契约批准后并行 |
| P2 链路补全 / P3 事件流+ClickHouse | M2 SPM+治理 / M3 ClickHouse | 共用 Kafka/ClickHouse 底座(§3) |
| P4/P5 Agent OTel 化 + agent_obs 降级 | M4 实时流 + Agent 业务结果 | **必合并设计**(§2.1/§2.5) |

---

## 8. 测试策略(真栈,host-first 零 docker)

- **可观测**:P0 真 Jaeger 端到端 span 实证;Kafka traceparent round-trip 契约测试(跨语言);审计 trace_id 落库反查。
- **行为**:SPM 派生纯函数单测 + 真浏览器 golden(点击→`/api/collect`→落库反查正确 spm/props);摄取/流处理真 Kafka round-trip IT(full=Flink mini-cluster,minimal=consumer);存储 SPI 双实现各跑同一契约测试套;dashboard golden(真数据→真 widget→断言数值)。
- **跨域 seam**:assembled-product 运行时 golden(浏览器点击 → Kafka → 流处理/Collector → 存储 → 查询 API → dashboard/Jaeger 数值闭环)。
- ClickHouse/Flink/Jaeger 原生 binary 装入常驻 broker 集;minimal 档完全不依赖。

---

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| ClickHouse/Flink/Jaeger 破"本地零 docker" | host-first 原生 binary;minimal 档零依赖二者 |
| 高频写打爆存储 | 采样 + 批量 + 背压 + 分区 + TTL(§2.8) |
| 异步丢上下文(ThreadLocal) | seam 同步快照 + 信封携带(§2.3) |
| outcome 重试双计转化 | 确定性幂等键(§2.4) |
| 两支开发线撞同一 chokepoint | 一 seam N 路异步,联合设计(§2.5);收口走 `ConversationTurnService`(AGENTS §12) |
| SPM 跨版本漂移 | 锚定 DSL 稳定标识(pageKey/blockId/fieldCode)+ registry |
| 隐私合规 | consent/DNT/脱敏/按 userId 删除内建 |
| agent_obs 降级破坏现有 eval | 先双写过渡,L3/L4 链不动,验证后切;长期只留判定层 |
| 私有化 Flink 运维重 | 私有化默认 minimal 微批档 |

---

## 10. 待 owner 拍板(收敛后的少数 open items)

1. **批准 §2 冻结契约?**(批准 = 行为 M1 / 可观测重活的开发门禁开启;可升 enterprise DDR)
2. **OLAP 选型**:ClickHouse(默认推荐,OTel 生态成熟)/ Doris?
3. **流处理引擎**:full tier 锁 Flink,还是接受 Kafka Streams(更轻、与现有 Kafka 同栈)作为 full 选项?(引擎可变,keying 已冻结 §2.9*)
4. **业务+安全埋点范围**:本轮纳入,还是先做 Agent/SRE 两类、产品/安全二期?
5. **Mobile 采集**:本期 Web 纵深、Mobile 放 M4+,可接受?
6. **规模假设**(SaaS 千万级/天、私有化 ≤百万级/天)是否符合预期?(影响采样与 sizing)

---

## 附录 A — 实证锚点(file:line)

| 主题 | 锚点 |
|---|---|
| AI Trace 表/Service | `auraboot/platform/.../db/migration/core/V20260618000000__baseline_core_schema.sql`(`ab_ai_trace`/`ab_ai_trace_span`);`framework/agent/trace/AiTraceService.java`;`TraceContext.java` |
| AI Trace 写入点 | `AgentRunService.java:185/427/454/464`、`ToolLoopService.java:221/240/269/319`、`AuraBotChatService.java:352/375/416/695` |
| AI Trace 读侧 | `AiTraceController`(`/api/ai/traces`);`web-admin/app/plugins/core-aurabot/pages/ai-trace/index.tsx` |
| Agent 观测/评估 | `AgentObservationService`(`@Async @EventListener`,6 发布者);`ScheduledOnlineEvalJob`(默认关);`AgentOnlineEvalService`;`HeuristicTurnQualityJudge` |
| L1 配置/依赖 | `application.yml`(`management.tracing`/`logging.pattern`);`application-dev.yml`(`tracing.enabled:false`);`build.gradle`(`micrometer-tracing-bridge-otel`/`opentelemetry-exporter-otlp`/`micrometer-registry-prometheus`,Spring Boot 3.5.14) |
| `@Observed` / Metrics | command pipeline / named query / secure query / permission / plugin import / rest pipeline / dynamic data(10 处);`MetaPerformanceMonitor`;`SlowQueryInterceptor`;`TraceIdResponseFilter` |
| Kafka | `platform-mq-kafka/.../KafkaMqProvider.java`(裸 producer/consumer,header 框架在);`KafkaSchemaRegistryClient`(已有未用) |
| 行为现状 | `AdminLayout.tsx:37-51`(pageview 钩子);`UserEngagementServiceImpl:53-83,127-152`(状态覆盖表);`BlockRenderer.tsx:62-76`(SPM 派生基础);`HttpClient.ts:59-77`;DSL `widgetRegistry`(funnel/heatmap 已有) |
| 审计表(无 trace 列) | `ab_command_audit_log` / `ab_admin_event_log` / `ab_query_audit_log` / `ab_agent_observation` |

## 附录 B — 关联文档 / 被取代草稿

- 方向对齐:`auraboot-enterprise/docs/backlog/2026-06-16-agent-os-gap-analysis.md`
- 现有 canonical:`auraboot-enterprise/docs/standards/meta/observability.md`、`.../system-reference/subsystems/40-可观测性系统.md`
- 被本文取代的草稿:`feat/observability-unification-plan`(enterprise)、`feat/behavior-analytics-spec`(OSS) — 均未 merge,内容已合并入本文

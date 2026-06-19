---
type: backlog
status: active
created: 2026-06-19
---

# 统一遥测与分析平台 — 架构 SoT(候选)+ 冻结契约(§2)

> **文档身份**(解上一轮评审"身份自相矛盾"):本文是**统一平台架构 SoT 候选稿**;**§2 是拟冻结契约**(DDR 级),owner 批准后升正式 DDR(可保留为本文 §2,不另立文件)。批准前 `status=active`(草案)。
>
> **文档层级**(保持"统一 1 份"为上位,不炸成多文件):
> - 本文 = **上位架构 SoT + 冻结契约**(双域关系、共享底座、跨域关联、拟冻结不变量)。
> - 两份旧草稿 = **下位领域设计**(去冲突后保留富细节:完整 BehaviorEvent 信封 / SPM 语法 / quarantine / 权限 / SDK / 投影映射):`feat/observability-unification-plan`(可观测域)、`feat/behavior-analytics-spec`(行为域)。
> - 本文**只 supersede 旧草稿的"总架构裁决"部分**,不取代其领域级实现细节(本文已压掉这些,不足以单独取代)。
>
> **评审状态**(2026-06-19 第二轮专家评审后):`架构方向 APPROVED / §2 本版已纳入 8 项 CHANGES / P0 立即可开 / M1·P2 待 §2 最终冻结后开`。本版相对上一版的修改见 §11。
>
> **方向对齐**:`auraboot-enterprise/docs/backlog/2026-06-16-agent-os-gap-analysis.md`(eval 飞轮 / OTel GenAI 语义 / runtime-offline 分离)。
> **证据基础**:§4.1/§5.1 现状基于 2026-06-18 对 OSS `auraboot/platform` 的 call-site 深挖,锚点见附录 A,区分【实测】/【推断🟡】。
> **产品定位**:production-ready 平台原生能力,非 MVP/PoC/demo。

---

## 0. TL;DR

- **一个平台、两个领域、一套下游底座、关联键互通**;统一的是文档与平台契约,**不统一事件 schema**。
- 两域回答不同问题:**行为域**=用户/Agent 做了什么、是否转化/留存/放弃;**可观测域**=一次请求/执行怎么完成、哪里慢、为什么失败。
- 原"两份 spec 抢 `ab_agent_observation`"的冲突,由 §2.1 四层归属裁决消除。
- **§2 冻结集克制在核心契约表(§2.0)**——富 schema/SLO/失败测试路由进领域设计 / 实施计划 / 测试策略,避免 DDR 膨胀成"提前设计整个终局"。
- **开发门禁**:M1 / 可观测 P2 前必须批准 §2;**P0(纯运行时实证)不焊任何契约,与批准并行**。

---

## 1. 平台定位:两域一底座

### 1.1 总体关系

```text
统一遥测与分析平台
├── 可观测性域(A):traces/spans/metrics/logs · Agent/tool/LLM 技术遥测(OTel,唯一事实源)· SRE/安全/审计
├── 行为分析域(B):pageview/click/exposure · SPM/session/funnel/retention/path · 产品与业务事件(含 Agent 业务结果)
└── 共享底座:Kafka(分 topic)· ClickHouse(分库)· PostgreSQL(投影/轻量档)· 治理目录 · 租户/隐私/采样/TTL · 薄上游(context/时钟/ULID/correlation 传播)
```
**共享底座 ≠ 同一张表 / 同一 schema。**

### 1.2 三个采集入口(故意分开)

```text
(a) Web 行为:   Browser SDK ─► /api/collect ─► behavior topic ─► Flink/微批 ─► CH
(b) 服务端结果: 应用内 outcome publisher ─►(outbox)─► behavior topic        ← Agent 业务结果走这条,非浏览器
(c) 技术可观测: 进程内 OTel SDK ─► OTLP ─► Collector ─► Jaeger / Prometheus / Kafka ─► CH
```
- (a) 关心离线缓冲/sendBeacon/consent/SPM/限流;(c) 关心 span 生命周期/context 传播/tail sampling/OTLP——不合并。
- **(b)(c) 在同一 Agent chokepoint 同源**(§2.6:一次插桩、多 adapter)。

### 1.3 文档层级 — 见文首"文档身份/层级"。(解阻塞 1)

---

## 2. 冻结契约(DDR 级 / 开发门禁)

> 原则:**冻结不变量,版本化可变量**(§2.11)。冻结集克制在 §2.0 核心表;明细在 §2.1–§2.11 展开,富 schema 路由领域设计。

### 2.0 核心契约表(= 冻结集)

| 类别 | 冻结结论 |
|---|---|
| 原始技术遥测 | **OTel 唯一技术遥测写入模型** |
| 业务分析事实 | **BehaviorEvent 唯一行为分析事实模型** |
| 真实业务状态 | **仍以各业务领域 DB 为权威**,不由 BehaviorEvent 替代 |
| Agent outcome | **durable outbox,never sample** |
| 审计/安全决定 | **durable,never sample** |
| 普通 trace | 可采样、queued、best-effort |
| `ab_agent_observation` | 派生判断层,不存原始遥测 |
| `ab_ai_trace` | OTel 投影读模型 |
| 跨域强关联 | **`interaction_id → trace_id → run_id`** |
| session | `client_session_id` 与 `derived_session_id` 分开 |
| schema | Behavior=Avro;OTel=OTLP/semconv;治理目录统一 |
| 采集入口 | Web / server outcome / OTLP **三入口分开** |
| seam | 一个语义 seam、多 adapter;**可靠性按数据类别区分** |
| 底座 | 部署单元内默认共享、逻辑分域、阈值可拆 |
| ClickHouse | 时间低基数分区;tenant 放排序键 |
| 实施门禁 | topic/correlation/reliability/sampling 契约批准后开放 M1/P2 |

### 2.1 Agent 数据四层归属(消冲突核心)

| Agent 数据 | 唯一事实源 |
|---|---|
| run/tool/LLM/token/耗时/异常 原始技术遥测 | **OTel span**(GenAI semconv) |
| 采纳/放弃/人工接管/任务完成/业务转化 业务结果 | **BehaviorEvent**(只接业务语义,不接完整 trace) |
| `ab_agent_observation` | **derived observation/decision record**(判定层,必引 trace_id/span_id) |
| `ab_ai_trace` | **OTel 流的 PG 投影**(产品 UI 读模型) |

`ab_agent_observation` 只存:在线质量判定/能力评估/异常结论/策略违规或安全判断/人工复核;**不存**原始 tool 流水/token·耗时/prompt·completion 副本/run 逐步日志。建议表结构:`trace_id, span_id, source_event_id, observation_kind, judge_type, judge_version, verdict, score, reason_code, created_at`(+ 现有 tenant_id/pid/severity)。

### 2.2 三采集入口分开 — 见 §1.2(冻结)

### 2.3 关联键:词典 + 按记录类型必填矩阵 + interaction_id(改阻塞 3)

**不是"每条记录都带全部键"**(不可能:pageview 无 trace、cron 无 user、匿名无 user_id、offline eval 跨千条 trace)。统一字段词典 + **按记录类型必填矩阵**:

| 记录类型 | 必填 | 条件/可选 |
|---|---|---|
| Web BehaviorEvent | `tenant_id, event_id, occurred_at, client_session_id, user_id/anon_id` | `interaction_id, trace_id, source_span_id` |
| Server Outcome | `tenant_id, event_id, run_id, occurred_at` | `trace_id, source_span_id, session_id, user_id` |
| OTel span | `trace_id, span_id, start_time, end_time` | `tenant_id, run_id, session_id, user_id_hash` |
| Observation | `observation_id, subject_type, subject_id, judge_type, judge_version` | `trace_id, span_id, source_event_id, dataset_id` |

**关联强度分级**(不把三者写成等价 JOIN):
- **强关联**:`interaction_id` / `trace_id` / `run_id` / `event_id`
- **弱关联**:`session_id`
- **归因关联**:`user_id` + 时间窗(标记 heuristic)

**`interaction_id`(必增)**:浏览器跑行为 SDK 而非 OTel SDK,**点击不会天然带服务端 traceId**。链路:
```text
用户点击 ─► BehaviorEvent(interaction_id=I123)
        └► 下一次业务 HTTP 带 X-Aura-Interaction-Id: I123 ─► 服务端 root span 写 aura.interaction.id=I123
```

**session 两层**:`client_session_id`(SDK 生成,原始事实)/ `derived_session_id`(流处理生成)/ `sessionization_version`(算法版本)——改 30min gap 不污染历史语义。

### 2.4 可靠性分级 + 幂等(改阻塞 4)

`@Async @EventListener` 适合故障隔离/非关键遥测,**不保证不丢**(业务已提交→进程崩→listener 未跑→outcome 永久丢失;确定性 id 解决重复不解决丢失)。按数据类别定交付保证:

| 数据 | 交付保证 | 方式 |
|---|---|---|
| 普通 OTel span | best-effort / queued | SDK → Collector |
| **Agent 业务 outcome** | **durable at-least-once** | **transactional outbox → Kafka** |
| **审计/策略决定** | **durable,不可采样** | 审计事务 / outbox |
| derived observation | 若影响 eval/发布门禁则 durable | outbox 或可靠 consumer |
| 普通 click/exposure | 预算内可丢 | SDK 批量上报 |

- **Outbox**:F0 类同一本地事务写"业务状态 + behavior_outbox",提交后 relay/CDC → Kafka → 幂等 consumer → PG/CH。
- **幂等**:服务端 outcome `event_id` 从 run 确定性派生(`runId+outcomeKind`),禁随机;Kafka producer 开 idempotence 防 broker 重试重复,但**不替代** outbox 与 sink 端去重。
- **ClickHouse 去重**:`ReplacingMergeTree` 仅 merge 时清重,**查询时仍可能有重复**→精确漏斗须写入前去重 / 查询去重 / 专门 deduplicated projection。

### 2.5 保真分级 + 成本独立记录(改阻塞 5)

采样与"精确计数/成本/eval"冲突,引入保真等级:

| 等级 | 含义 | 覆盖 |
|---|---|---|
| **F0** | Exact / Never Sample | 业务 outcome、审计、安全决策、**计费 usage** |
| **F1** | Full Retention | 错误、慢请求、策略违规、关键 Agent run |
| **F2** | Sampled Telemetry | 普通 trace、普通工具调用 |
| **F3** | Sampled Behavior | 高频 click/exposure(必带 `sampling_probability`) |

- **成本不挂 sampled span**:独立 durable `GenAiUsageRecord`(`tenant_id, run_id, trace_id, provider, request_model, response_model, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens, amount, currency, pricing_version, pricing_effective_at`);OTel span 只是其关联视图。
- **runtime eval 走同步控制路径**(返回 continue/pause/reject/escalate decision),**不依赖**可能延迟/采样/丢失的 Collector→Kafka 流;异步流只供分析与后续反馈。

### 2.6 一个 seam、多 adapter、按可靠性分级输出(改阻塞 4/7)

Agent chokepoint(`AgentRunService`/`ToolLoopService`/`AuraBotChatService`)= 一次语义插桩,多 adapter 输出,**故障隔离 + 按 §2.4 分级**(不做"必须同时成功"的事务双写;F0 outcome 走 outbox):
```java
try (AgentRunInstrumentation run = telemetry.startRun(ctxSnapshot)) {  // §2.3 同步快照,异步侧不读 ThreadLocal
    Result r = execute();
    run.recordTechnicalResult(r);          // OTel span(best-effort)
    outcomePublisher.publish(r);           // BehaviorEvent outcome(outbox,durable,幂等)
    return r;
}
```
**correlation 在 seam 同步快照**(`MetaContext`/span context 是 ThreadLocal,异步 worker 执行时可能已失效——库内已知坑 `MetaContext not initialized / @Async 丢上下文`):seam 抓 `{traceId, spanId, runId, sessionId, tenantId, userId}` 注入每个信封 + Kafka header。

### 2.7 Wire contract(M1 前定死,改阻塞 2)

```text
aura.behavior.events.v1        # 行为事件(单 topic,event_name 在 payload)
aura.behavior.quarantine.v1    # 未知/非法事件隔离(不静默吞)
aura.otel.traces.v1            # OTLP protobuf,不过行为 Avro registry
aura.otel.logs.v1
aura.otel.metrics.v1           # 仅在确需进 Kafka 时
aura.audit.events.v1           # 若本期实施审计事件流
```
- **删除上一版臆造的 `otel.events`**(OTel 正式信号只有 traces/metrics/logs;GenAI event 是 log record 或 span 内 event,不是第四类信号)。
- **行为 wire**:入口接 JSON,**进 Kafka 前转 canonical Avro**;key = `tenantId:anonId`(或 `sessionId`,session-preserving §2.11*);Avro subject = `aura.behavior.events.v1-value`(TopicNameStrategy);compatibility = **backward**(默认,纳入冻结)。
- **OTel wire**:OTLP protobuf,走 Collector Kafka exporter,不进 Avro registry。

### 2.8 schema 分治 + semconv 版本钉定(改阻塞 6)

- 行为=Avro+业务 registry;OTel=OTLP+semantic conventions;**不要求 OTel span 过行为 Avro 校验**;统一治理目录(属性/事件 catalog、owner/版本/弃用、分类/敏感性、覆盖率、跨域关联键)。
- **不冻结裸属性名,冻结**:`semconv_source / semconv_version / schema_url / compatibility_policy`(pin OTel core semconv 1.x + GenAI semconv 指定 release/commit;AuraBoot 扩展用 `aura.agent.* / aura.cost.* / aura.security.*`)。
- 实现时按 pin 核对当前名(如 `gen_ai.system` 已废弃→`gen_ai.provider.name`、`finish_reason`→`finish_reasons` 数组);**`gen_ai.usage.cost` 非现成标准属性**→用 `aura.gen_ai.cost.*` 或独立 usage record(§2.5)。

### 2.9 共享底座:默认共享、逻辑分域、阈值可拆(冻结契约、拓扑可变)

每个 deployment cell/环境/区域默认共用一套 Kafka + 一套 ClickHouse,经 topic/database/RBAC/quota/资源组逻辑隔离;容量/合规/数据驻留/故障域达阈值时**允许物理拆分,逻辑契约不变**。本地 host-first 不跑两套 CH。

### 2.10 采样/TTL:统一策略模型 + 分域执行适配器

```text
统一治理/策略模型 → BehaviorSamplingAdapter / OtelTailSamplingAdapter / ClickHouseRetentionAdapter / PostgresRetentionAdapter
```
共享策略模型与治理界面,**执行机制分域**(行为采样在 SDK/collect/consumer;trace 采样在 SDK head + Collector tail;TTL 行为按 event type/删除、trace 按错误·慢·安全·敏感级)。

### 2.11 freeze / version 清单

| 冻结(不变量) | 版本化(可演进) |
|---|---|
| 数据归属 / 原始事实源唯一性 | 具体 TTL 天数 |
| 关联键语义 + 强弱归因分级 + correlation 快照(§2.3/§2.6) | 采样比例 |
| topic/database 命名 + wire contract(§2.7) | ClickHouse 精确 DDL / 分区数 |
| 可靠性分级 + outbox + 幂等键(§2.4) | **流处理引擎(Flink / Kafka Streams)** * |
| 保真分级 + 成本独立记录(§2.5) | 物理集群数量 |
| PII 分类 / schema 兼容(backward) | 具体 widget / 看板 |
| seam 输出模式 + 跨域引用(§2.6/§6) | Mobile 时点 |

\* **引擎可变,但保住引擎可选性的 keying 是不变量**:session 化要求"同 session→同 partition"(key=`tenantId:anonId`),**M1 建 topic 时即冻结**。正文目标架构保持引擎中立(不把 Flink 写成已选终局)。

---

## 3. 共享底座

| 组件 | 共享方式 |
|---|---|
| **Kafka** | 一集群;topic 见 §2.7;分区按 `tenantId` 哈希 + session-preserving key |
| **ClickHouse** | 一集群分库:`behavior.*` 与 `otel_traces`/`otel_logs`;**分区改低基数**(见下);共享关联列 |
| **PostgreSQL** | 行为 minimal 存储 + `ab_ai_trace` 投影 + `ab_agent_observation` 判定层 + `behavior_outbox` |
| **治理目录** | 事件/属性 catalog、owner/版本/分类、覆盖率、跨域关联键 |
| **薄上游共享** | context 获取(tenant/user/session/trace)、分类/脱敏/consent 接口、统一时钟+ULID/event-id、correlation 传播读写 |
| **本地** | host-first 零 docker:CH 原生 binary / Flink standalone / Jaeger·otelcol 二进制,slot 隔离;minimal 档零新增依赖 |

**行为明细表分区(改阻塞 8,改掉上一版 `PARTITION BY (tenantId, day)` 反模式)**:
```sql
PARTITION BY toYYYYMM(occurred_at)             -- 低基数;按日 TTL 价值大再评估 toDate
ORDER BY (tenant_id, event_name, occurred_at, event_id)   -- tenant 进排序键,不进 PARTITION BY
```
**跨域关联用显式投影,不长期 JOIN 两张原始大表**:
```text
interaction_trace_link(tenant_id, interaction_id, behavior_event_id, trace_id, root_span_id, run_id, linked_at, link_type)
```

---

## 4. 可观测域(A)

### 4.1 现状(call-site 实证)
两套并行"鹰眼",各自真在跑、traceId 不互通:① 基础设施 tracing(OTel→Jaeger,HTTP 入站 span 自动 / 10 处 `@Observed` / `TraceIdResponseFilter` 回写;本地 dev 默认关、Jaeger 未自动起)【实测】;② 自研 AI 链路(`ab_ai_trace`/`_span`,run/tool/turn 真写 + `/aurabot/traces` UI,自研 UUID 与 OTel 无关)【实测】;③ L3 观测(6 发布者)/L4 评估/审计 全 wired【实测】。

### 4.2 Gap
A-G1 两套 traceId 不通 / A-G2 审计三表无 trace_id(`X-Request-ID` 孤儿)/ A-G3 日志缺 `%X{traceId}` / A-G4 Kafka 跨消息断链 / A-G5 无运行时实证 / A-G6 LLM 成本未计算。

### 4.3 目标
OTel 骨架:agent/LLM/tool 改发 OTel span(GenAI semconv),全链一 traceId;`ab_ai_trace` 翻转为**投影 consumer**(P1 先盖 OTel traceId,P3 换投影,P5 关直接写库)。**投影契约列为验收目标(非既成事实)**,明细回领域设计:UUID↔32-hex 兼容 / span 映射 / root span 识别 / 乱序·late span / 幂等键 `(tenant_id,trace_id,span_id)` / `projection_version,is_complete,projection lag` / backfill / 双轨比对 / feature flag 回滚。

### 4.4 埋点(GenAI semconv + 扩展,改阻塞 6)
- 强制属性按 §2.3 矩阵;LLM:`gen_ai.provider.name`(非废弃 `gen_ai.system`)`gen_ai.request.model` `gen_ai.usage.input_tokens/output_tokens` `gen_ai.response.finish_reasons`(数组)`gen_ai.operation.name` + `agent.code/tool.name/tool.outcome` + 成本走 `aura.gen_ai.cost.*`/usage record + 安全 `aura.security.*`。
- **Kafka span**:单消息无 ambient → MAY parent-child;**批量 receive / 已有 ambient context → SHOULD span links**(一个 span 只能一个 parent)。
- **Collector 可靠性**:Kafka exporter 是同步 producer,须 batch + queued retry;重要链路启用持久化 WAL,监控 queue/drop;tail sampling 处理 late spans/decision cache/trace affinity/内存。

### 4.5 分析
SRE:p50/p95/p99、错误率、环节耗时、慢查询(`MetaPerformanceMonitor`+`SlowQueryInterceptor`→Prometheus+Grafana RED/USE;span 时延进 CH)。安全:`trace_id` 串"命令→查询→观测→审计"。Agent 技术质量:喂 eval 飞轮——runtime eval 同步控制(§2.5)、offline eval 读 CH 历史做发布门禁。

---

## 5. 行为分析域(B)

### 5.1 现状(带证据)
`grep spm` 全仓 0 命中(无通用埋点);但零件现成【实测,领域设计 §2】:`AdminLayout` 已有 pageview 钩子(落点是状态覆盖表,无流水)/ `ab_agent_observation` 是唯一 append-only 流水范式 / `KafkaMqProvider`+DLQ 可复用 / DSL Dashboard 35+ 图表(funnel/heatmap 已有,分析层 ~70% 现成)/ `BlockRenderer`+`block.id`+`fieldCode` 是 SPM 自动派生基础。

### 5.2 Gap
集中在 ① 事件流水留存 ② 元素级 SPM ③ 真实时流处理 ④ 采样/限流 ⑤ 留存/Sankey/实时大盘 widget ⑥ 隐私合规(详领域设计 §3)。

### 5.3 架构(双轨采集 + 服务端 outcome 入口)
```text
双轨 SDK(自动 pageview/click/exposure + SPM 声明)→ 批量缓冲 sendBeacon
   → /api/collect(BeaconController:服务端权威补全 tenant/user + registry 校验 + 采样 + 脱敏 + 背压)
   → aura.behavior.events.v1(Avro)
   → StreamProcessorPort(SPI):full=Flink / minimal=Kafka-consumer 微批(引擎中立,§2.11*)
   → BehaviorStorePort(SPI):full=ClickHouse / minimal=PG 分区
   → /api/analytics/*(events/aggregate/funnel/retention/path/realtime-SSE)→ DSL Dashboard + 新 widget
服务端 outcome 入口(b):应用内 publisher →(outbox,§2.4)→ aura.behavior.events.v1
```

### 5.4 SPM 位置码模型
`spm=a.b.c.d`(应用域.pageKey.blockId.elementCode),`BlockRenderer` 自动注入 `data-spm`(锚定 DSL 稳定标识,跨版本不漂)。**语法需冻结**(允许字符 / `.` 转义 / 最大长度 / 大小写 / SPM 版本 / `block.id` 缺失降级 `spm_quality=degraded` / 禁把 record·content id 放进 SPM)+ 曝光阈值·停留·去重 + A/B `experiment_id/variant_id`——**完整语法见领域设计**。

### 5.5 BehaviorEvent v1(信封关键字段 + 收窄 agent_obs)
关键字段:`schema_version, event_id, event_name, event_category, source, occurred_at, received_at, tenant_id, user_id/anon_id, client_session_id, derived_session_id, interaction_id, trace_id, source_span_id, run_id, props, producer_name, producer_version, consent_state, consent_version, sampling_probability`。约定:客户端 ULID 首次入队生成、重试不重生成;未知 event_name 进 quarantine;`api` 若指接口耗时/错误率→**删,归 OTel**,若指用户业务 API 动作→改清晰业务名。
- **与 §2.1 对齐(改行为草稿 §7)**:**删除 `eventType=agent_obs` 整条遥测收编**;Agent 业务结果经入口(b)以业务 `eventName`(`agent.task.completed`/`agent.handoff`/`agent.abandoned`)发出,技术遥测归 OTel。
- **完整信封 / quarantine / 权限 `analytics.*` / 身份·consent 分层 见领域设计。**

### 5.6 存储 SPI(`BehaviorStorePort`)
`writeBatch / queryEvents / queryAggregate / queryFunnel / queryRetention / queryPath / queryRealtime / deleteByUser(GDPR)`;full=ClickHouse(分区见 §3)/ minimal=PG 月分区+rollup / 可选 TDengine;`aura.analytics.store.tier`。

### 5.7 分析
产品:UV/PV/漏斗/留存/路径 + SPM 点位(看板 70% 现成 + 新 `smart-retention-chart`/`smart-sankey-chart`/`smart-realtime-board`,前后端两边注册)。低代码运营回流:DSL 树派生 SPM → 页面/行动点使用度回流、A/B。

---

## 6. 跨域关联(产品行为 ↔ 技术链路)

```text
用户点击(域B,interaction_id=I123)
   └► X-Aura-Interaction-Id ─► HTTP root span(aura.interaction.id=I123,trace_id=T)
        ─► Kafka(traceparent 透传)─► Agent run(run_id=R)─► tool ─► LLM(域A,trace_id=T)
        ↘ outcome:agent.task.completed(域B,outbox,带 interaction_id/trace_id/run_id)
```
- 关联强度(§2.3):**强** `interaction_id/trace_id/run_id`;**弱** `session_id`;**归因** `user_id`+时间窗(heuristic)。
- **精确诊断走 `interaction_trace_link` 投影**(§3),不长期 JOIN 两张原始大表;user/session/window 仅归因、显式标 heuristic。

---

## 7. 实施路线(seam-first,改阻塞 7)

```text
T0(并行):
  P0 现状实证(不焊契约)  ∥  批准 §2 契约

S0 共享代码契约(seam 只插一次):
  CorrelationSnapshot / AgentTelemetryFacade / TechnicalTelemetryPort / BusinessOutcomePort
  / ObservationPort / OutboxPort / topic·schema constants

契约批准后(并行):
  P1 日志 %X{traceId} + 审计表 trace_id + ab_ai_trace 盖 OTel traceId
  M1 Web behavior 采集(/api/collect + Kafka + PG minimal + 自动 pageview/click + 基础 UV/PV + 多租户 + 权限 + 隐私基线 + golden);
     **Agent 业务 outcome 不依赖 Flink**,先进 Kafka+PG minimal,实时漏斗后补

随后:
  P2 OTel adapter 实现(agent/LLM span + Kafka traceparent 透传)  ∥  M1/M2 outcome adapter + SPM + 治理 + 漏斗
  Observation 按迁移计划切 derived-only

最后:
  P3/P4/P5 Collector / ClickHouse / 投影 / 分析 / 收口  ∥  M3/M4 深度分析 / 实时流
```

**P0 验收(改阻塞 7,与已知 gap 一致——不要求统一端到端)**:① HTTP 入站 span 在 Jaeger 可见 ② 自研 trace 在 `/aurabot/traces` 可见 ③ 两者 ID 确认不同 ④ Kafka 后链路确认断开 ⑤ 产出基线 trace topology 证据。**统一端到端链路 = P2 验收。**

| 域A | 域B | 依赖 |
|---|---|---|
| P0 实证 / P1 关联统一 | M1 采集底座(含 server outcome via outbox) | S0 后并行 |
| P2 OTel adapter | M2 SPM+治理+漏斗 | 共用底座(§3) |
| P3/P4/P5 | M3/M4 | Agent 边界统一(§2.1/§2.6 已前置 S0) |

---

## 8. 测试策略(真栈,host-first 零 docker)

- **happy 路径 golden**:行为(SPM 派生纯函数 + 真浏览器点击→`/api/collect`→落库反查)/ 可观测(P0 Jaeger 基线 + Kafka traceparent round-trip 契约 + 审计 trace_id 反查)/ 存储 SPI 双实现同契约套 / dashboard golden(真数据→真 widget→断言数值)。
- **失败场景(必验)**:业务提交后/事件发布前 crash · Kafka 不可用/恢复/replay · Collector queue 满 · 重复消息 · 乱序 · late span/late 行为 · 客户端时钟偏差 · 新旧 schema 混跑 · 投影 consumer 重启+backfill · 跨租户伪造 tenantId · consent 撤回 · PII 脱敏 · GDPR 删除 · tail-sampling 压力 · CH 查询时重复。
- **跨域 golden 拆三步**(不把浏览器事件描述成经 Collector):A 浏览器→behavior pipeline→behavior 表;B HTTP/Agent→OTel pipeline→Jaeger/trace 表;C `interaction_id`→`interaction_trace_link` 断言关联正确。
- **平台自身 SLO**:accepted/rejected/sampled/dropped · Kafka lag · Collector queue util · DLQ/quarantine age · correlation coverage · duplicate rate · ingest-to-query freshness · `ab_ai_trace` projection lag · 查询 p95 · 删除 SLA · 插桩对业务 p99 的额外开销。

---

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| CH/Flink/Jaeger 破"本地零 docker" | host-first 原生 binary;minimal 档零依赖 |
| 业务提交后事件丢失 | F0 transactional outbox(§2.4) |
| outcome 重试双计 | 确定性幂等键 + sink 去重(§2.4) |
| 采样致成本/eval 失真 | 保真分级 + 独立 usage record + runtime eval 同步(§2.5) |
| 异步丢 ThreadLocal 上下文 | seam 同步快照(§2.6) |
| 高基数分区爆 parts | 低基数时间分区 + tenant 进排序键(§3) |
| 两支开发线撞 chokepoint | S0 seam-first 一次插桩(§7);收口走 `ConversationTurnService` |
| semconv 漂移 | pin 版本 + 兼容策略,实现核对(§2.8) |
| 匿名上报伪造 tenant | 受信 site/app key/host binding/签名 token,不信客户端 tenantId |
| 隐私合规 | OTel 用 user.hash 不传明文 / user_id 不作 Prometheus label / prompt·completion 默认关 + 源端脱敏 / GDPR 删除覆盖 user_id+anon_id+identity link+原始+投影 |
| 私有化 Flink 运维重 | 私有化默认 minimal 微批 |

---

## 10. 待 owner 拍板

**A. 批准 §2 冻结契约前必须拍板**:① topic 名称/payload/key(§2.7 已给建议,确认即冻结)② OLAP = ClickHouse 还是保持逻辑中立 ③ 规模假设(SaaS 千万级/天、私有化 ≤百万级/天?)④ 业务 outcome 是否本期进入 ⑤ 审计/安全事件最低可靠性范围(是否本期实施 `aura.audit.events.v1`)。

**B. 不阻塞冻结(版本化)**:Flink vs Kafka Streams(引擎中立,keying 已冻结)/ Mobile M4 vs M4+ / 具体 widget / 采样率 / TTL 天数。

---

## 11. 本版相对上一版的修改(纳入第二轮评审 8 项 CHANGES)

1. 文档身份/层级澄清(SoT 候选 + 内嵌 §2 拟冻结契约 + 旧草稿降领域设计去冲突,不再"supersede 又依赖")。
2. §2.7 wire contract 定死 + **删除臆造 `otel.events`**;引擎中立(不把 Flink 写成已选)。
3. §2.3 关联键改"词典 + 必填矩阵 + 强弱归因分级",**新增 `interaction_id`** 与 session 两层。
4. §2.4 可靠性分级 + **transactional outbox**(F0 never-lose),区分丢失与重复。
5. §2.5 保真分级 F0–F3 + **独立 `GenAiUsageRecord`** + runtime eval 同步控制。
6. §2.8/§4.4 semconv **pin 版本**(`gen_ai.provider.name`/`finish_reasons`/成本 `aura.*`)+ Kafka span links + Collector 可靠性。
7. §7 路线 **S0 seam-first**(chokepoint 只插一次)+ P0 验收改基线拓扑(不要求统一端到端)+ Agent outcome 不等 M4。
8. §3 ClickHouse **低基数分区**修正 + `interaction_trace_link` 投影;§8 补失败场景矩阵 + 平台 SLO;§10 拆"冻结前必拍 / 不阻塞"。

---

## 附录 A — 实证锚点(file:line)

| 主题 | 锚点 |
|---|---|
| AI Trace 表/Service | `auraboot/platform/.../db/migration/core/V20260618000000__baseline_core_schema.sql`(`ab_ai_trace`/`ab_ai_trace_span`);`framework/agent/trace/AiTraceService.java`;`TraceContext.java` |
| AI Trace 写入点 | `AgentRunService.java:185/427/454/464`、`ToolLoopService.java:221/240/269/319`、`AuraBotChatService.java:352/375/416/695` |
| AI Trace 读侧 | `AiTraceController`(`/api/ai/traces`);`web-admin/app/plugins/core-aurabot/pages/ai-trace/index.tsx` |
| Agent 观测/评估 | `AgentObservationService`(`@Async @EventListener`,6 发布者);`ScheduledOnlineEvalJob`(默认关);`AgentOnlineEvalService`;`HeuristicTurnQualityJudge` |
| L1 配置/依赖 | `application.yml`(`management.tracing`/`logging.pattern`);`application-dev.yml`(`tracing.enabled:false`);`build.gradle`(`micrometer-tracing-bridge-otel`/`opentelemetry-exporter-otlp`/`micrometer-registry-prometheus`,Spring Boot 3.5.14) |
| `@Observed`/Metrics | command/query/permission/plugin import/rest pipeline/dynamic data(10 处);`MetaPerformanceMonitor`;`SlowQueryInterceptor`;`TraceIdResponseFilter` |
| Kafka | `platform-mq-kafka/.../KafkaMqProvider.java`(裸 producer/consumer);`KafkaSchemaRegistryClient`(已有未用) |
| 行为现状 | `AdminLayout.tsx:37-51`;`UserEngagementServiceImpl:53-83,127-152`;`BlockRenderer.tsx:62-76`;`HttpClient.ts:59-77`;DSL `widgetRegistry` |
| 审计表(无 trace 列) | `ab_command_audit_log`/`ab_admin_event_log`/`ab_query_audit_log`/`ab_agent_observation` |

## 附录 B — 关联文档 / 领域设计

- 方向对齐:`auraboot-enterprise/docs/backlog/2026-06-16-agent-os-gap-analysis.md`
- 现有 canonical:`auraboot-enterprise/docs/standards/meta/observability.md`、`.../system-reference/subsystems/40-可观测性系统.md`
- 下位领域设计(去冲突后保留富细节):`feat/observability-unification-plan`(可观测域)、`feat/behavior-analytics-spec`(行为域,含完整 BehaviorEvent 信封/SPM 语法/quarantine/权限/SDK)
- 外部最佳实践参考(实现时按 pin 版本核对):OTel GenAI semconv / OTel messaging spans(span links)/ OTel Collector kafka exporter + tail sampling / ClickHouse 分区键 / Debezium outbox / Kafka producer idempotence / ClickHouse ReplacingMergeTree / OTel handling sensitive data

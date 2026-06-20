---
type: backlog
status: active
created: 2026-06-19
---

# 统一遥测与分析平台 — 架构 SoT(候选)+ 冻结契约(§2)

> **修订**:2026-06-19 创建;2026-06-20 R2(纳第二轮评审 8 项);2026-06-20 R3(纳第三轮评审:**删除 SPM 模型** + §2 内部矛盾消解 + 域 C 改 non-normative)。变更见 §11。
>
> **文档身份**:本文 = **统一平台架构 SoT 候选 + §2 拟冻结契约(DDR 级)**。owner 批准 §2 后升正式 DDR(可保留为本文 §2)。批准前 `status=active`(草案)。
>
> **文档层级 + 合并门禁**(R3 收紧):本文是**上位架构 SoT**;两份旧草稿(`feat/observability-unification-plan` 可观测域、`feat/behavior-analytics-spec` 行为域)是**下位领域设计**。⚠️ **合并门禁**:§2 冻结**必须**与"两份下位领域设计完成去冲突"在**同一 PR**内完成——否则实现者从下位文档进入仍会读到旧结论(行为设计仍以 SPM 为骨架 / `eventType=agent_obs` / 按 eventType 分 topic;可观测设计仍有 `otel.events` / 旧 GenAI 属性 / runtime eval 读异步流)。本文只取代旧草稿的"总架构裁决",其领域级细节须按本文回改后保留。
>
> **评审状态**(R4,2026-06-20):`总体架构方向 APPROVED · Agent 数据归属 APPROVED · 事件模型(删 SPM)APPROVED · §2 冻结契约 ✅ FROZEN v1.0(§10-A 采最佳实践默认,owner 可 override)· P0 ✅ DONE(运行时实证,§4.1)· 两份下位领域设计 ✅ 已去冲突(superseded banner + 关键冲突段对齐)· S0 可开 · 域 C non-normative`。
>
> **证据基础**:§4.1/§5.1 现状基于 2026-06-18 对 OSS `auraboot/platform` 的 call-site 深挖,锚点见附录 A,区分【实测】/【推断🟡】。**外部行业对照(GA4/Segment/OTel 等)实现时按 pin 版本核对,见附录 B。**
> **产品定位**:production-ready 平台原生能力,非 MVP/PoC/demo。

---

## 0. TL;DR

- **一个平台、两个核心领域(A 可观测 / B 行为)+ 可扩展领域(C 部署遥测,本期非规范)、一套下游底座、关联键互通**;统一的是文档与平台契约,**不统一事件 schema**。
- **删除 SPM 模型**(R3):事件优先(`event_name` 表语义)+ **UI 元素身份契约**(`ui_element_id` 稳定 join key,**非** `a.b.c.d` 路径串)+ `interaction_id` 表因果。四段串退化为重复字段——其信息已在 `page_id/block_id/element_code`;稳定身份应是不可变 ID 而非路径(rename/clone/模板实例化会改路径)。对齐 GA4(event-based)/ Segment(语义事件 + Tracking Plan)/ Amplitude·PostHog(自动采集 + 业务精确事件)的"事件 + 元素元数据",非独立位置码模型。
- Agent 数据抢 `ab_agent_observation` 的冲突,由 §2.1 四层归属裁决消除。
- **§2 冻结集克制在核心契约表(§2.0)**,富 schema/SLO/失败测试路由领域设计 / 实施 / 测试。
- **底座领域无关**:开源 phone-home(域 C)是一次 architecture fitness test——证明底座方向对,也证明当前契约还按双域设计、未真正泛化(需第四入口/独立身份/独立 schema/授权控制面),故本期作 non-normative(§5C)。
- **开发门禁**:M1 / 可观测 P2 前必须批准 §2 + 完成领域设计去冲突;**P0(纯实证)不焊契约,与批准并行**。

---

## 1. 平台定位

### 1.1 总体关系
```text
统一遥测与分析平台(两核心领域 + 可扩展领域,共享下游底座)
├── A 可观测性域:traces/spans/metrics/logs · Agent/tool/LLM 技术遥测(OTel,唯一事实源)· SRE/安全/审计
├── B 行为分析域:pageview/click/exposure · 事件优先 + UI 元素身份 · funnel/retention/path · 产品与业务事件(含 Agent 业务结果)
├── C 部署/实例遥测与授权域(开源 phone-home,**本期 non-normative**,§5C):instance/version/edition/license · 版本分布 · 授权合规
└── 共享底座:Kafka(分 topic)· 分析存储 port〔ClickHouse=reference impl〕· PostgreSQL(投影/轻量/outbox)· 治理目录 · 租户/隐私/采样/TTL · 薄上游(context/时钟/ULID/correlation)
```
**共享底座 ≠ 同一张表 / 同一 schema。**

### 1.2 采集入口(故意分开)
```text
(a) Web 行为:   Browser SDK ─► /api/collect ─► behavior topic ─► 流处理 ─► 分析存储
(b) 服务端结果: 应用内 publisher ─►(outbox,同业务库事务)─► behavior topic
(c) 技术可观测: 进程内 OTel SDK ─► OTLP ─► Collector ─► Jaeger / Prometheus / Kafka ─► 分析存储
(d) 实例遥测〔域 C,本期非规范〕: 自托管实例 ─► 公网签名网关 ─► Kafka / entitlement 控制面   ← 不复用 /api/collect、不直连 Kafka
```
(a) 离线缓冲/sendBeacon/consent/限流;(c) span 生命周期/context 传播/tail sampling/OTLP——不合并。**(b)(c) 在同一 Agent chokepoint 同源**(§2.6)。

### 1.3 文档层级 + 合并门禁 — 见文首"文档层级 + 合并门禁"。

---

## 2. 冻结契约(DDR 级 / 开发门禁)

> 原则:**冻结不变量,版本化可变量**(§2.11)。冻结集克制在 §2.0;明细 §2.1–§2.11,富 schema 路由领域设计。

### 2.0 核心契约表(= 冻结集)
| 类别 | 冻结结论 |
|---|---|
| 原始技术遥测(诊断 token/延迟/异常) | **OTel span 唯一事实源** |
| **计费/配额使用量** | **`GenAiUsageRecord` 唯一计费事实源**(不从 sampled span 汇总,R3) |
| 业务分析事实 | **BehaviorEvent 唯一行为分析事实模型** |
| 真实业务状态 | **各业务领域 DB 为权威**,不由 BehaviorEvent 替代 |
| **UI 元素身份** | **`ui_element_id` 稳定 join key**(不可变;`ui_path`/四段串仅派生可读,非键,R3) |
| Agent outcome / 审计·安全决定 | **durable + unsampled**(outbox,at-least-once + 幂等 → 计数精确;非传输 exactly-once,R3) |
| 普通 trace / click·exposure | 可采样、best-effort/queued |
| `ab_agent_observation` | 派生判断层,不存原始遥测;`subject_type+subject_id` 必填、`source_ref` ≥1 |
| `ab_ai_trace` | OTel 投影读模型 |
| 跨域强关联 | **`interaction_id ↔ trace_id[] ↔ run_id[]`**(强关联,**不承诺 1:1**,R3) |
| session | `client_session_id`(原始)与 `derived_session_id`(流处理投影,不回写原始)分开 |
| schema | Behavior=Avro(`BACKWARD_TRANSITIVE`);OTel=OTLP/semconv;治理目录统一 |
| 采集入口 | Web / server outcome / OTLP **三入口分开**(域 C 第四入口非本期) |
| seam | 一个语义 seam、多 adapter;可靠性按数据类别区分;outcome 与业务状态同库事务 |
| **分析存储** | **`AnalyticalStorePort`/`BehaviorStorePort` 不变量;ClickHouse=reference impl**(DDL/分区/集群数版本化,R3) |
| 底座 | 部署单元内默认共享、逻辑分域、阈值可拆 |
| 实施门禁 | topic/key/correlation/reliability/sampling 契约批准 + 领域设计去冲突后开放 M1/P2 |

### 2.1 Agent 数据四层归属
| Agent 数据 | 唯一事实源 |
|---|---|
| run/tool/LLM/**诊断 usage 属性**(token/延迟/异常) | **OTel span**(GenAI semconv) |
| **计费/配额使用量** | **`GenAiUsageRecord`**(§2.5,durable;OTel 的 token 属性只是诊断镜像、不用于账单) |
| 采纳/放弃/接管/完成/业务转化 | **BehaviorEvent**(只接业务语义,不接完整 trace) |
| `ab_agent_observation` | **derived observation/decision record**(判定层) |
| `ab_ai_trace` | **OTel 流的 PG 投影** |

`ab_agent_observation` 只存判定(在线质量/能力评估/异常/策略/人工复核),不存原始遥测。引用规则:`subject_type + subject_id` 必填,`source_ref` 至少一个;**`trace_id/span_id` 仅在 subject 是 span 时必填**(behavior-event observation 用 `source_event_id`、offline 用 `dataset_id+evaluation_run_id`、release verdict 用 `release_id+evaluation_run_id`)。

### 2.2 三采集入口分开 — 见 §1.2(冻结)

### 2.3 关联键:词典 + 按记录类型必填矩阵
**不是"每条记录都带全部键"**。统一字段词典 + 矩阵(R3:拆 Server BusinessEvent / Agent Outcome;observation 引用按 subject 类型):

| 记录类型 | 必填 | 条件/可选 |
|---|---|---|
| Web BehaviorEvent | `tenant_id, event_id, event_name, occurred_at, client_session_id, user_id/anon_id` | `interaction_id, caused_by_event_id, ui_element_id, trace_id, source_span_id` |
| Server **BusinessEvent** | `tenant_id, event_id, event_name, occurred_at` | `interaction_id, trace_id, aggregate_id` |
| Server **Agent Outcome** | 上 + `run_id` | `trace_id, source_span_id, session_id, user_id` |
| OTel span | `trace_id, span_id, start_time, end_time` | `tenant_id, run_id, session_id, user_id_hash` |
| Observation | `observation_id, subject_type, subject_id, judge_type, judge_version`(+`source_ref`≥1) | `trace_id, span_id, source_event_id, dataset_id, evaluation_run_id` |

**关联强度**(不写成等价 JOIN):强 `interaction_id`/`trace_id`/`run_id`/`event_id`(**基数 1:N**);弱 `session_id`;归因 `user_id`+时间窗(heuristic)。

**`interaction_id`**:浏览器跑行为 SDK 非 OTel SDK,点击不天然带服务端 traceId。一个 interaction → 0..N traces → 每 trace 0..N runs(点"生成报告"可同时触发权限检查/建任务/轮询/SSE/异步 run)。SPA/API 用 `X-Aura-Interaction-Id` header(**显式作用域 `withInteraction(id, fn)`,禁"全局下一请求用当前 id"——并发点击会串线**);同源跨文档导航用短时 `navigation_id`;跨域仅双方协作才精确关联,否则降级 referrer/session。

**session**:`client_session_id`(SDK 原始)/ `derived_session_id` + `sessionization_version`(流处理投影,**原始 topic 为 null、不回写原始事件**)。

### 2.4 可靠性分级 + outbox + 幂等
`@Async @EventListener` 适合故障隔离/非关键遥测,**不保证不丢**。按数据类别定交付保证:

| 数据 | 交付保证 | 方式 |
|---|---|---|
| 普通 OTel span | best-effort/queued | SDK → Collector |
| **Agent 业务 outcome** | **durable + unsampled** | **transactional outbox → Kafka** |
| **审计/策略决定** | **durable + unsampled** | 审计事务 / outbox |
| derived observation | 若影响 eval/门禁则 durable | outbox 或可靠 consumer |
| 普通 click/exposure | 预算内可丢 | SDK 批量上报 |

- **Outbox 硬约束**:outbox **必须与产生权威业务状态的 DB 处于同一本地事务**;共享的是 outbox schema/relay/治理,**不是强制所有服务写一个中央 `behavior_outbox` 表**(状态在库 A、outbox 在库 B 则无法原子提交)。`outcomePublisher.publish()` 必须在**真正的状态事务 seam** 上(§2.6 示例)。
- **幂等键**:终态(completed/failed)= `hash(run_id, terminal_state, state_version)`;**可重复状态变化**(handoff 等)= `hash(run_id, outcome_kind, transition_id)`;或直接用业务状态机 version。禁随机 id。Kafka producer 开 idempotence 防 broker 重试,但不替代 outbox 与 sink 去重。
- **ClickHouse 去重**:`ReplacingMergeTree` 仅 merge 时清重,查询时仍可能有重复→精确漏斗须写入前/查询去重或 deduplicated projection。

### 2.5 保真分级 + 成本独立记录
| 等级 | 含义 | 覆盖 |
|---|---|---|
| **F0** | Unsampled + Durable(at-least-once+幂等→计数精确) | 业务 outcome、审计、安全决策、**计费 usage** |
| **F1** | Full Retention | 错误、慢请求、策略违规、关键 Agent run |
| **F2** | Sampled Telemetry | 普通 trace、普通工具调用 |
| **F3** | Sampled Behavior | 高频 click/exposure |

- **成本独立事实源**:durable `GenAiUsageRecord`(`tenant_id, run_id, trace_id, provider, request_model, response_model, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens, amount, currency, pricing_version, pricing_effective_at`)是**唯一计费来源**;OTel span 的 token 属性是诊断镜像。金额从 usage ledger + pricing version,**不从 sampled span 汇总**。
- **采样单位 + 测量模式(R3)**:F3 事件带 `sampling_unit`(event|session|user|element)、`sampling_rule_id`、`sampling_decision_id`。**漏斗/路径/留存默认按 `client_session_id`/`anon_id` 确定性采样**(非逐事件随机,否则序列断裂);分析 API 返回 `measurement_mode = exact | estimated`——**精确漏斗仅由 F0/F1 或 session 级确定性采样构成**,禁把估算点击与 F0 精确 outcome 混进同一漏斗显示成精确转化。A/B assignment 与主 outcome 不随普通 click 采掉。
- **runtime eval 走同步控制路径**(continue/pause/reject/escalate),不依赖可能延迟/采样的异步流;异步流只供分析与反馈。

### 2.6 一个 seam、多 adapter、故障隔离
```java
// 必须位于真正的业务状态事务 seam;execute() 提交后外层再提交会令 outcome 提前生成
@Transactional
public Result run(...) {
    CorrelationSnapshot ctx = telemetry.snapshot();   // §2.3 同步快照,异步侧不读 ThreadLocal
    Result r = execute();                              // 写权威业务状态
    technicalTelemetry.recordRun(ctx, r);             // OTel span(best-effort)
    outboxPort.enqueueOutcome(ctx, r);                // 同事务写 outbox(durable,幂等)→ relay → Kafka
    return r;
}
```
复用现成 `@Async @EventListener` 范式做 best-effort 输出(OTel span/derived observation);F0 outcome 走 outbox。**禁同步事务双写**(要求 OTel 与 Kafka 同时成功)。

### 2.7 Wire contract(M1 前定死)
```text
aura.behavior.events.v1 / aura.behavior.quarantine.v1
aura.otel.traces.v1 / aura.otel.logs.v1 / aura.otel.metrics.v1(仅确需进 Kafka 时)
aura.audit.events.v1(若本期实施)
```
- 删除上一版臆造的 `otel.events`(理由见 §2.8)。行为入口接 JSON,**进 Kafka 前转 canonical Avro**;OTLP protobuf 走 Collector Kafka exporter,不进 Avro registry。Avro compatibility = **`BACKWARD_TRANSITIVE`**(长期演进契约)。
- **Kafka key matrix(R3,替代模糊单规则)**,header 带 `partition_key_kind` / `partition_key_version`:

| 事件来源 | Kafka key |
|---|---|
| Web 行为 | `tenant_id:client_session_id`(不可用时 `tenant_id:anon_id`) |
| Agent outcome | `tenant_id:run_id`(有 `client_session_id` 时优先后者) |
| 普通 server business event | `tenant_id:aggregate_id` 或明确 ordering key |
| quarantine | 原始 key 或 `tenant_id:event_id` |

(引擎以后可 `keyBy` 重分区,故不强迫所有事件共享同一原始排序语义;但 session-preserving key 是 M1 即冻结的 keying 不变量。)

### 2.8 schema 分治 + semconv 钉定
- 行为=Avro+registry;OTel=OTLP+semconv;不要求 OTel span 过行为 Avro;统一治理目录。
- **OTel 信号(R3 措辞修正)**:**本期采用稳定 OTLP 管道 `traces/metrics/logs`**;GenAI event 表达为 **LogRecord 或 SpanEvent**;Events 是演进中的特殊 log 类型、Profiles 2026-03 进 public alpha——**均本期不纳入、预留扩展**(删 `otel.events` 是**本期范围裁决**,非"OTel 永远只有三信号"的事实判断)。
- **不冻结裸属性名,冻结** `semconv_source/semconv_version/schema_url/compatibility_policy`(pin OTel core 1.x + GenAI semconv release/commit)。**命名一致性**:有 pinned semconv 定义→用标准名(`gen_ai.provider.name`、`gen_ai.response.finish_reasons` 数组);**无标准定义→`aura.*`**(`aura.agent.code`/`aura.tool.name`/`aura.tool.outcome`/`aura.gen_ai.cost.*`/`aura.security.*`),**禁无 owner 的裸命名空间**。`gen_ai.usage.cost` 非现成标准→用 `aura.gen_ai.cost.*` 或 usage record。

### 2.9 共享底座:默认共享、逻辑分域、阈值可拆(拓扑可变、逻辑契约冻结)
部署单元默认共用一套 Kafka + 一套分析存储,经 topic/database/RBAC/quota 逻辑隔离;容量/合规/数据驻留/故障域达阈值时允许物理拆分、逻辑契约不变。本地 host-first 不跑两套 CH。

### 2.10 采样/TTL:统一策略模型 + 分域执行适配器
统一策略/治理模型 → `BehaviorSamplingAdapter`/`OtelTailSamplingAdapter`/`{ClickHouse,Postgres}RetentionAdapter`;执行机制分域(§2.5 采样单位)。

### 2.11 freeze / version 清单
| 冻结(不变量) | 版本化(可演进) |
|---|---|
| 数据归属/原始事实源唯一性(含计费=usage record) | TTL 天数 / 采样比例 |
| 关联键语义 + 强弱归因分级 + 1:N 基数 + correlation 快照 | ClickHouse 精确 DDL/分区数 |
| topic/database 命名 + wire + **Kafka key matrix** | **流处理引擎(Flink/Kafka Streams)** * |
| 可靠性分级 + outbox 同库约束 + 幂等键 | 物理集群数量 |
| 保真分级 + 成本独立记录 + 采样单位/测量模式 | 具体 widget/看板 |
| PII 分类 / Avro `BACKWARD_TRANSITIVE` | Mobile 时点 / 域 C 商业策略 |
| seam 输出模式 + 跨域引用 + **分析存储 port** | **ClickHouse 作为 reference impl 的实现细节** |

\* 引擎可变,但 session-preserving key(`tenant_id:client_session_id`)M1 即冻结。正文保持引擎中立(不把 Flink 写成已选)。

---

## 3. 共享底座
| 组件 | 共享方式 |
|---|---|
| **Kafka** | 一集群;topic 见 §2.7;key matrix 见 §2.7 |
| **分析存储** | **`AnalyticalStorePort` 不变量;ClickHouse=reference impl**;一集群分库 `behavior.*`/`otel_*` |
| **PostgreSQL** | 行为 minimal 存储 + `ab_ai_trace` 投影 + `ab_agent_observation` 判定 + 业务库内 outbox |
| **治理目录** | 事件/属性 catalog、owner/版本/分类、覆盖率、跨域关联键 |
| **薄上游共享** | context 获取、分类/脱敏/consent 接口、统一时钟+ULID/event-id、correlation 传播 |
| **本地** | host-first 零 docker;minimal 档零新增依赖 |

**ClickHouse reference DDL(版本化,非冻结;最终排序键由真实 workload 验证)**:
```sql
PARTITION BY toYYYYMM(occurred_at)             -- 低基数;按日 TTL 价值大再评估 toDate
ORDER BY (tenant_id, event_name, occurred_at, event_id)   -- tenant 进排序键,不进 PARTITION BY
```
**`interaction_trace_link`(1:N,显式投影,不长期 JOIN 两原始大表)**:
```text
link_id, tenant_id, interaction_id, behavior_event_id, trace_id, root_span_id, run_id,
link_method(explicit_header|server_outcome|reconstructed), link_confidence(exact|heuristic),
projection_version, first_seen_at, last_seen_at
唯一键允许一对多:(tenant_id, interaction_id, trace_id, behavior_event_id)
```

---

## 4. 可观测域(A)
### 4.1 现状(call-site 实证)
两套并行"鹰眼",各自真在跑、traceId 不互通:① 基础设施 tracing(OTel→Jaeger,HTTP 入站 span 自动 / 10 处 `@Observed` / `TraceIdResponseFilter`;dev 默认关、Jaeger 未自动起)【实测】;② 自研 AI 链路(`ab_ai_trace`/`_span` + `/aurabot/traces`,自研 UUID 与 OTel 无关)【实测】;③ L3 观测(6 发布者)/L4 评估/审计 全 wired【实测】。

**P0 运行时实证(2026-06-20,host-first 零 docker,runtime `obs-p0-baseline-54`/`auraboot_54`,跑预构建 bootJar + `MANAGEMENT_TRACING_ENABLED=true`)**:同一 `/chat/stream` 请求 → OTel `X-Trace-Id=db4e41a06e9f5dfbe2b8c5e23cc98cac`(32-hex)vs `ab_ai_trace.trace_id=72738caf-1d05-4b54-933a-77d91cbfe654`(UUID + 3 子 span `d1_grounding/resolve_tools/render_prompt`);**OTel id 在 `ab_ai_trace` 全表出现 0 次(零桥接)**;OTLP sink 收 28 个 span POST(导出真在跑);MQ 默认 `local`(默认配置不过 Kafka)。→ **「两套鹰眼不互通」由推断升为实证;§4.1 全部 call-site 结论(自研 span 名 / UUID 格式 / 导出管道)经真栈核对成立。**
### 4.2 Gap
A-G1 两 traceId 不通(🟡 **ab_ai_trace 已桥接** `94b6ef0a3`,审计/Kafka 待接)/ A-G2 审计无 trace_id(列已加 `49da15ebe`,填充待接)/ A-G3 ✅ 日志已带 traceId(`ed7f823d2`)/ A-G4 Kafka 断链 / A-G5 ✅ **P0 已实证(见 §4.1)** / A-G6 成本未计算。
### 4.3 目标
OTel 骨架;`ab_ai_trace` 翻转为投影 consumer(P1 盖 traceId→P3 投影→P5 关直写)。投影契约列为**验收目标**,明细回领域设计(UUID↔32-hex/span 映射/root 识别/乱序·late/幂等键 `(tenant_id,trace_id,span_id)`/`projection_version,is_complete`/backfill/双轨比对/flag 回滚)。
### 4.4 埋点(命名按 §2.8:标准名优先、否则 `aura.*`)
强制属性按 §2.3;LLM `gen_ai.provider.name`/`gen_ai.request.model`/`gen_ai.usage.input_tokens·output_tokens`/`gen_ai.response.finish_reasons` + **`aura.agent.code`/`aura.tool.name`/`aura.tool.outcome`** + 成本 `aura.gen_ai.cost.*`(或 usage record)+ 安全 `aura.security.*`。Kafka span:单消息无 ambient→MAY parent-child;批量/已有 ambient→SHOULD span links。Collector:Kafka exporter 同步 producer 须 batch+queued retry、重要链路 WAL、监控 queue/drop;tail sampling 处理 late/decision cache/affinity/内存。
### 4.5 分析
SRE(p50/p95/p99、错误率、慢查询→Prometheus+Grafana RED/USE)/ 安全(`trace_id` 串命令→查询→观测→审计)/ Agent 质量(runtime eval 同步、offline eval 读 CH 门禁)。

---

## 5. 行为分析域(B)
### 5.1 现状(带证据)
`grep spm` 全仓 0 命中【实测,领域设计 §2】;零件现成:`AdminLayout` pageview 钩子(落点状态覆盖表无流水)/ `ab_agent_observation` append-only 流水范式 / `KafkaMqProvider`+DLQ / DSL Dashboard 35+ 图表(funnel/heatmap 已有,~70% 现成)/ `BlockRenderer`+`block.id`+`fieldCode` 是 **UI 元素身份自动派生**基础。
### 5.2 Gap
① 事件流水留存 ② **UI 元素身份 + 自动采集** ③ 真实时流处理 ④ 采样/限流 ⑤ 留存/Sankey/实时大盘 widget ⑥ 隐私合规(详领域设计)。
### 5.3 架构(自动采集 + 声明式事件 + 服务端 outcome 入口)
```text
SDK(自动 pageview/click/exposure + 声明式语义事件)→ 批量缓冲 sendBeacon
   → /api/collect(服务端权威补全 tenant/user + registry 校验 + 采样 + 脱敏 + 背压)→ aura.behavior.events.v1(Avro)
   → StreamProcessorPort(full=Flink / minimal=微批,引擎中立)→ BehaviorStorePort(full=CH / minimal=PG)
   → /api/analytics/*(events/aggregate/funnel/retention/path/realtime-SSE)→ DSL Dashboard + 新 widget
服务端 outcome 入口(b):应用内 publisher →(outbox 同业务库事务,§2.4)→ aura.behavior.events.v1
```
### 5.4 事件模型 + UI 元素身份契约(删除 SPM,R3)
**主模型 = 事件优先**(`event_name` 表语义),对齐 GA4(event-based;UA 标准 property 2023-07-01 起停止处理新数据)/ Segment(语义事件 + Tracking Plan 治理 Page·Track)/ Amplitude·PostHog(自动采集 + 业务精确事件双轨)。**删除 SPM 模型**(四段码/`spm` 字段/语法/registry/SPM 专属分析);保留**薄 UI 元素身份契约**:
- **稳定 join key = `ui_element_id`**(不可变)。`ui_path`(如 `crm/lead-list/toolbar/create`)仅派生可读、非键、非冻结。事件元素信息:
  ```text
  ui_element: { definition_id(=ui_element_id), app_id, page_id, block_id, element_code,
                component_path[], identity_source(dsl|declared|autocapture), identity_quality(stable|degraded|heuristic) }
  ```
- **ID 生命周期**:rename→ID 不变;move→ID 不变、path 变;copy/clone→新 ID;delete/recreate→新 ID;migrate→registry 存历史 path alias。`component_path[]` 表达嵌套(page→tab→form→field→action),**不把整树压进单段**;查询 API 支持任意层级过滤;四段仅作 v1 可读形式,非唯一物理模型。
- **自动 vs 声明事件(必冻结其一,默认 `augment`)**:`augment`(自动 click + 语义事件都发,语义事件 `caused_by_event_id`→自动 click,共享 `interaction_id`)/ `replace`(声明节点只发语义事件)/ `off`。看板按 `event_category`(ui_interaction|navigation|business_intent|business_outcome|experiment)区分,**禁把 generic click 与 semantic action 混算**。**业务完成事件优先由服务端事务 seam 发布**(§2.4),不把按钮点击当业务成功。
- **autocapture 隐私 allowlist**:默认**禁**采 input/textarea value、innerHTML、完整 textContent、完整 href/query、未登记 `data-*`、全量 class、record/content id;默认**只允许** `ui_element_id`、tag、role、allowlist 内 aria、稳定 page/block/element 标识、清洗后 route template。autocapture(`identity_quality=heuristic`)**不可直接用于长期 KPI / 核心漏斗 / 发布门禁**,须先在治理页提升为 declared/stable。
- DOM 标记 `data-aura-element-id`(替代 `data-spm`)。**完整契约见领域设计的"UI Element Identity Contract"**(§五最小冻结清单)。

### 5.5 BehaviorEvent v1(信封关键字段)
`schema_version, event_id, event_name, event_category, source, occurred_at, received_at, tenant_id, user_id/anon_id, client_session_id, interaction_id, caused_by_event_id, trace_id, source_span_id, run_id, ui_element{...§5.4}, props, producer_name, producer_version, consent_state, consent_version, sampling_unit, sampling_probability`。约定:客户端 ULID 首次入队生成、重试不重生成;未知 event_name 进 quarantine;`api` 若指接口耗时→删归 OTel,若指业务动作→改业务名;**Server BusinessEvent 不要求 run_id,仅 Agent Outcome 要求**(§2.3);`derived_session_id` 不在原始信封(§2.3)。Agent 业务结果经入口(b)以业务 `eventName`(`agent.task.completed`/`agent.handoff`/`agent.abandoned`)发出。**完整信封/quarantine/权限 `analytics.*`/身份·consent 分层见领域设计。**
### 5.6 存储 SPI(`BehaviorStorePort`)
`writeBatch/queryEvents/queryAggregate/queryFunnel/queryRetention/queryPath/queryRealtime/deleteByUser(GDPR)`;full=CH/minimal=PG/可选 TDengine。
### 5.7 分析
产品:UV/PV/漏斗/留存/路径 + **UI Element Analysis**(元素级使用率/热力图,看板 70% 现成 + 新 retention/sankey/realtime widget)。运营回流:DSL 树派生稳定元素身份 → 页面/行动点使用度回流、A/B。M2 = **自动采集 + 声明式事件治理**(非"SPM 双轨")。

---

## 5C. 部署/实例遥测与授权域(域 C)— **non-normative,本轮不进冻结集**

> 开源 phone-home。**作为 architecture fitness test**:验证底座(topic/schema 治理、Kafka/CH、版本偏斜、幂等/重试、隐私、不可信公网客户端)可复用——方向对,但也证明当前 §2(入口/身份矩阵/topic/部署边界)仍按双域设计,需补**第四入口 + 独立身份 + 独立 schema + 授权控制面**才真正泛化。**本期不实施、不声称受 §2 冻结契约覆盖**;开源启动时按本节落地或正式纳入 §2。业界参考:GitLab Service Ping + Seat Link、PostHog/Sentry/Metabase/n8n/Grafana usage telemetry、HashiCorp license utilization reporting。

### 5C.1 拆 C1 / C2(性质不同,必须分开)
- **C1 部署/产品遥测**:版本分布、edition、功能采纳、聚合活跃量;可关闭;ClickHouse 分析。
- **C2 授权用量报告**:席位/配额/合同用量;与 entitlement/billing **对账**;按合同自动或人工提交;**权威落控制面 DB,不以 ClickHouse 为准**。链路:`LicenseUsageStatement → 验签/防重放 → entitlement·billing DB(权威）→ outbox → aura.license.events.v1 → CH 分析投影`。

### 5C.2 关键不同(不能照搬双域)
1. **第四入口**:公网 instance telemetry gateway(签名信封 + 反滥用/auth/replay protection + canonicalization → Kafka/控制面);**不复用 `/api/collect`、不让外部实例直连 Kafka/registry**。
2. **独立记录类型**:`DeploymentTelemetrySnapshot` / `LicenseUsageStatement`(**不塞进 BehaviorEvent/OTel span**);topic `aura.deployment.telemetry.v1`(C1)/ `aura.license.events.v1`(C2)**不在 §2.7 冻结列表**(non-normative)。
3. **身份(不用 raw license key 作身份/长期凭据)**:`installation_id`(首装随机、持久、可重置)+ `subscription_id` + `license_id`(非 secret)+ `reporting_credential`(bootstrap 后签发、可轮换/撤销)+ `report_sequence` + `schema_version`。raw `license_key` 仅首次 bootstrap 换取受限 credential;不用硬件指纹作主身份;尽量实例内聚合、不发用户级数据;报告带 sequence/时间窗/签名,服务端 replay protection。
4. **F0 ≠ never-lose**:公网/离线/air-gapped 不能承诺端到端不丢。客户端=有界 durable queue + retry backoff + deterministic report id + **人工 export fallback**;服务端=idempotent at-least-once + sequence/replay detection + ack。"精确"指**幂等投影后的业务计数效果**,非传输 exactly-once。
5. **opt-out 与合同计量分开**:三开关 `product_usage_telemetry` / `version_update_check` / `license_utilization_reporting`(产品分析按法律 opt-in/out;版本检查独立;license 用量是否必需取决于合同/部署模式;air-gapped 支持人工报告;管理员可预览待发 payload;**默认开/关不写成架构不变量**,由产品/法务/版本策略定)。

---

## 6. 跨域关联(产品行为 ↔ 技术链路)
```text
点击(域B,interaction_id=I123,ui_element_id=elm_…)
   └► X-Aura-Interaction-Id ─► HTTP root span(aura.interaction.id=I123,trace_id=T)─► Kafka(traceparent)─► Agent run(R)─► tool ─► LLM(域A,T)
        ↘ outcome:agent.task.completed(域B,outbox,带 interaction_id/trace_id/run_id)
```
基数 1:N;精确诊断走 `interaction_trace_link`(§3),user/session/window 仅归因(heuristic)。

---

## 7. 实施路线(seam-first;S0 在 §2 批准后)
```text
T0(并行):P0 现状实证(不焊契约) ∥ §2 评审 + 领域设计去冲突 + 批准
§2 批准后:S0 共享代码契约(含 topic/schema/key constants = 固化契约,故必在批准后)
  CorrelationSnapshot / AgentTelemetryFacade / TechnicalTelemetryPort / BusinessOutcomePort / ObservationPort / OutboxPort
S0 合并后(并行):P1(日志 %X{traceId}+审计 trace_id+ab_ai_trace 盖 traceId) ∥ M1(Web 采集+/api/collect+Kafka+PG minimal+自动采集+基础 UV/PV+多租户+权限+隐私基线+golden;Agent outcome 走 outbox 进 Kafka+PG,不依赖 Flink)
随后:P2(OTel adapter+Kafka traceparent) ∥ M2(UI 元素身份+治理+漏斗);Observation 切 derived-only
最后:P3/P4/P5(Collector/CH/投影/分析/收口) ∥ M3/M4(深度分析/实时流)
```
**P0 验收(基线拓扑,不要求统一端到端)— ✅ 已完成 2026-06-20(实证见 §4.1)**:① HTTP span(X-Trace-Id 32-hex + OTLP 28 POST)② 自研 trace 写库 + 3 子 span ③ 两 ID 确认不同 + 零桥接 ④ MQ 默认 local + KafkaMqProvider 无 traceparent(code)⑤ 基线证据已产出。统一端到端=P2 验收。

---

## 8. 测试策略(真栈,host-first 零 docker)
- **happy golden**:行为(UI 元素身份派生纯函数 + 真浏览器点击→`/api/collect`→落库反查)/ 可观测(P0 Jaeger 基线 + Kafka traceparent round-trip + 审计 trace_id 反查)/ 存储 SPI 双实现同契约套 / dashboard golden(真数据→真 widget→断言数值)。
- **失败场景(必验)**:业务提交后/发布前 crash · Kafka 不可用/恢复/replay · Collector queue 满 · 重复/乱序 · late span/late 行为 · 时钟偏差 · 新旧 schema 混跑 · 投影重启+backfill · 跨租户伪造 tenantId · consent 撤回 · PII 脱敏 · GDPR 删除 · tail-sampling 压力 · CH 查询时重复 · 并发点击 interaction 不串线。
- **跨域 golden 拆三步**:A 浏览器→behavior→behavior 表;B HTTP/Agent→OTel→trace 表;C `interaction_id`→`interaction_trace_link` 断言关联。
- **平台 SLO**:accepted/rejected/sampled/dropped · Kafka lag · Collector queue · DLQ/quarantine age · correlation coverage · duplicate rate · ingest-to-query freshness · `ab_ai_trace` projection lag · 查询 p95 · 删除 SLA · 插桩对业务 p99 开销。

---

## 9. 风险与缓解
| 风险 | 缓解 |
|---|---|
| 本地零 docker | host-first 原生 binary;minimal 零依赖 |
| 业务提交后丢事件 | F0 同库 transactional outbox(§2.4) |
| outcome 重试双计 | 终态/可重复幂等键 + sink 去重(§2.4) |
| 采样致成本/eval/漏斗失真 | 计费独立 usage record + session 级确定性采样 + measurement_mode(§2.5) |
| 异步丢 ThreadLocal | seam 同步快照(§2.6) |
| 并发点击 interaction 串线 | 显式 `withInteraction` 作用域(§2.3) |
| 高基数分区 | 低基数时间分区 + tenant 进排序键(§3) |
| 撞 chokepoint | S0 seam-first 一次插桩;收口走 `ConversationTurnService` |
| semconv 漂移 | pin 版本 + 兼容策略 + 命名一致(§2.8) |
| autocapture 采 PII | 默认 deny allowlist + heuristic 不入 KPI/门禁(§5.4) |
| 匿名/公网伪造身份 | 受信 key/签名 token;域 C installation_id+reporting_credential、不用 raw license key(§5C) |
| 隐私合规 | user.hash 不传明文 / 不作 Prometheus label / prompt·completion 默认关+源端脱敏 / GDPR 删除覆盖 user_id+anon_id+identity link+原始+投影 |

---

## 10. 待 owner 拍板
**A. §2 已冻结 v1.0(2026-06-20,采最佳实践默认,owner 可随时 override)**:① topic + Kafka key matrix 按 §2.7 确认冻结 ② 规模假设采 SaaS 千万级/天、私有化 ≤百万级/天(仅影响 sizing,本就 version-able)③ 业务 outcome **本期进入**(经 outbox,M1 即支持、低成本)④ 审计事件流 `aura.audit.events.v1` **本期 defer**(审计已 wired,独立事件流作 M2+ 增量)⑤ 执行门禁:两份下位领域设计去冲突已随本轮处理(superseded banner + 关键冲突段对齐,§1.3)。
**B. 已由本版裁决(owner 可override)**:分析存储 = port 冻结 + ClickHouse reference impl(原"OLAP 待选"open item 关闭);域 C = non-normative;事件模型删 SPM。
**C. 不阻塞(版本化)**:Flink vs Kafka Streams / Mobile 时点 / widget / 采样率 / TTL / 域 C 商业策略。

---

## 11. 修订历史
**R1(2026-06-19)** 两草稿合并单一 SoT。
**R2(2026-06-20,第二轮评审 8 项)** 文档身份/层级;wire contract 定死 + 删 `otel.events`;关联键词典+矩阵+`interaction_id`+session 两层;可靠性分级+outbox;保真分级 F0–F3+`GenAiUsageRecord`;semconv pin;S0 seam-first + P0 基线验收;ClickHouse 低基数分区 + `interaction_trace_link` + 失败矩阵 + SLO。
**R3(2026-06-20,第三轮评审)**
1. **删除 SPM 模型** → 事件优先 + `ui_element_id` 稳定 join key + ID 生命周期 + auto/declared(augment 默认)+ caused_by + event_category + autocapture 隐私 allowlist + 导航分场景(`navigation_id`/显式 `withInteraction`);`data-spm`→`data-aura-element-id`。
2. **token 事实源裁决**:OTel=诊断 usage 属性,`GenAiUsageRecord`=唯一计费源(§2.0/§2.1/§2.5)。
3. **observation 引用**:`subject_type+subject_id`+`source_ref`≥1;trace/span 仅 span-subject 必填。
4. **幂等**:终态 vs 可重复(handoff)分键;**Server BusinessEvent 不要求 run_id**(仅 Agent Outcome)。
5. **derived_session_id** 移出原始信封(投影态)。
6. **Kafka key matrix**(按来源)+ `partition_key_kind/version`;Avro `BACKWARD_TRANSITIVE`。
7. **outbox 同业务库事务**约束 + seam 位置;示例置于 `@Transactional` 状态 seam。
8. **保真**:`sampling_unit`/`measurement_mode=exact|estimated`;漏斗默认 session 级确定性采样。
9. **semconv 措辞**:OTLP 三信号为本期范围裁决(非事实判断),Events/Profiles 预留;`aura.*` 命名一致(无裸命名空间)。
10. **interaction 基数 1:N**;`interaction_trace_link` 加 link_method/confidence/version。
11. **OLAP 二义消解**:`AnalyticalStorePort` 冻结 + ClickHouse reference impl;CH DDL/分区移入版本化。
12. **域 C non-normative**:拆 C1/C2 + 第四入口 + 独立 record/topic + `installation_id`/`reporting_credential`(非 raw license key)+ F0 改"Unsampled+Durable"+ 三 opt-out 开关。
13. **措辞**:"两个核心领域 + 可扩展领域";**合并门禁**(领域设计去冲突同 PR)。

---

## 12. 实现执行 backlog(剩余切片 → 精确触点 → 验证法)

> 给未来会话/owner 直接接力,免重新推导。**已交付(均运行时验证)**:SoT 冻结 v1.0 · P0 实证 · A-G3 日志(`ed7f823d2`)· A-G1/A-G2 迁移(`49da15ebe`,`V20260620000000`)· **ab_ai_trace↔OTel 桥接**(`94b6ef0a3`,seam-snapshot;实证 `otel_trace_id`==`X-Trace-Id`,且证伪了 in-service 取法=异步丢上下文→§2.6 成立)。下表为剩余,按 §7 顺序;⚙️=需 jar rebuild 验证(rebuild 实测 ~7s 增量,可行)。

| 切片 | 精确触点 | 验证 |
|---|---|---|
| **P1 审计填充接线** ⚙️〔ab_ai_trace 桥接已 ✅ `94b6ef0a3`〕 | 审计 trace_id:`CommandEffectExecutor.saveAuditLog`(command)/ `AdminEventLogService`(admin)/ `QueryAuditServiceImpl:1417`(query)——**这些跑在请求线程(同步命令执行),`Tracer.currentSpan()` 可直接取**(无需 chat 那种 seam-threading);给 3 entity + insert 加 `trace_id/span_id` 列写入 | rebuild → 命令请求 → 审计行 `trace_id` = `X-Trace-Id`(真栈,同 P0 法) |
| **A-G4 Kafka 透传** ⚙️ | `platform-mq-kafka/.../KafkaMqProvider.send` producer 注入 W3C `traceparent` 到 record headers;consumer 提取续 span(`record.headers().add` 已有框架) | 跨语言 round-trip 契约测试(MQ=kafka);trace 过 Kafka 不断 |
| **S0 seam ports** ⚙️ | 新建 `CorrelationSnapshot`/`AgentTelemetryFacade`/`TechnicalTelemetryPort`/`BusinessOutcomePort`/`ObservationPort`/`OutboxPort`(framework/agent 或 observability 包);chokepoint `AgentRunService`/`ToolLoopService`/`AuraBotChatService` 改用 facade(一次插桩,§2.6) | 单测 facade + 真栈 chokepoint 不旁路 `ConversationTurnService` |
| **P2 OTel 化 agent/LLM** ⚙️ | 上述 chokepoint 发 OTel span(GenAI semconv,§4.4 命名);LLM provider 层接 `gen_ai.*` | Jaeger 端到端链(P0 升 P2 验收) |
| **P3 事件流** ⚙️ | OTel Collector(host-first binary)→ Kafka exporter;ClickHouse sink(`AnalyticalStorePort` impl);`ab_ai_trace` 投影 consumer(替直写,§4.3) | 同 span 进 Jaeger+CH;`/aurabot/traces` 改读投影行为不变 |
| **M1 行为采集底座** ⚙️ | `@aura/track` SDK(`web-admin/app/shared/track/`)+ `BeaconController`(`/api/collect`)+ `aura.behavior.events.v1` + `BehaviorStorePort` PG impl + 自动 pageview/click + 基础 UV/PV dashboard(DSL)+ server outcome publisher(outbox) | 真浏览器 golden:点击→`/api/collect`→落库反查;outcome→PG |
| **P4/P5 · M2–M4** | 见 §7（分析层 / SPM-free UI 元素身份 M2 / ClickHouse tier M3 / 实时流 M4 / 域 C non-normative） | 见 §8 |

**P1 填充接线是 unblocked 的下一步**(S0 facade 可同期建,outcome 用它);其余按依赖序。所有 ⚙️ 切片须 jar rebuild + 真栈验证(host-first,复用 `obs-p0-baseline-54` 法)。

---

## 附录 A — 实证锚点(file:line)
| 主题 | 锚点 |
|---|---|
| AI Trace 表/Service | `V20260618000000__baseline_core_schema.sql`(`ab_ai_trace`/`ab_ai_trace_span`);`framework/agent/trace/AiTraceService.java`;`TraceContext.java` |
| AI Trace 写入点 | `AgentRunService.java:185/427/454/464`、`ToolLoopService.java:221/240/269/319`、`AuraBotChatService.java:352/375/416/695` |
| AI Trace 读侧 | `AiTraceController`(`/api/ai/traces`);`web-admin/app/plugins/core-aurabot/pages/ai-trace/index.tsx` |
| Agent 观测/评估 | `AgentObservationService`(`@Async @EventListener`,6 发布者);`ScheduledOnlineEvalJob`(默认关);`AgentOnlineEvalService`;`HeuristicTurnQualityJudge` |
| L1 配置/依赖 | `application.yml`/`application-dev.yml`(`tracing.enabled:false`);`build.gradle`(micrometer-tracing-bridge-otel/opentelemetry-exporter-otlp/micrometer-registry-prometheus,Spring Boot 3.5.14) |
| `@Observed`/Metrics | command/query/permission/plugin import/rest pipeline/dynamic data(10 处);`MetaPerformanceMonitor`;`SlowQueryInterceptor`;`TraceIdResponseFilter` |
| Kafka | `platform-mq-kafka/.../KafkaMqProvider.java`;`KafkaSchemaRegistryClient`(已有未用) |
| 行为现状 | `AdminLayout.tsx:37-51`;`UserEngagementServiceImpl:53-83,127-152`;`BlockRenderer.tsx:62-76`;`HttpClient.ts:59-77`;DSL `widgetRegistry` |
| 审计表(无 trace 列) | `ab_command_audit_log`/`ab_admin_event_log`/`ab_query_audit_log`/`ab_agent_observation` |

## 附录 B — 关联文档 / 领域设计 / 外部对照
- 方向对齐:`auraboot-enterprise/docs/backlog/2026-06-16-agent-os-gap-analysis.md`
- 现有 canonical:`auraboot-enterprise/docs/standards/meta/observability.md`、`.../subsystems/40-可观测性系统.md`
- 下位领域设计(**须去冲突后保留**):`feat/observability-unification-plan`、`feat/behavior-analytics-spec`
- 外部对照(实现时按 pin 版本核对):OTel signals(traces/metrics/logs stable;Events/Profiles 演进)/ GenAI semconv / messaging span links / Collector kafka exporter + tail sampling / ClickHouse 分区键 + ReplacingMergeTree / Debezium outbox / Kafka idempotence / OTel sensitive data / GA4 event model / Segment Track spec / GitLab Service Ping + Seat Link / HashiCorp license utilization / PostHog autocapture allowlist

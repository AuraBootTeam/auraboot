---
type: plan-design
status: superseded
created: 2026-06-18
superseded_by: docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md
---

# AuraBoot 行为采集与数据分析平台 — 完整设计方案

> 🛑 **SUPERSEDED / 已去冲突(2026-06-20)** — 本文降为**下位领域设计**;**上位裁决以统一 SoT 为准**:`auraboot/docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md`(§2 已冻结 v1.0)。以下本文与 SoT 冲突段一律以 SoT 覆盖,实现以 SoT 为准:
> - **SPM 模型已删除** → 用 SoT §5.4「UI 元素身份契约」(`ui_element_id` 稳定 join key;`spm/path` 仅派生可读、非键);**事件优先**(`event_name`+params)为主模型。
> - **`eventType=agent_obs` 收编已废** → Agent 技术遥测归 OTel(SoT §2.1);行为域只接 Agent **业务结果**(`agent.task.completed`/`handoff`/`abandoned`,经服务端 **outbox**)。
> - **topic = 单 `aura.behavior.events.v1`**(非按 eventType 分);wire/Kafka key matrix 见 SoT §2.7。
> - 可靠性(outbox 同库事务)/ 幂等(终态 vs 可重复)/ 采样保真(F0–F3 + measurement_mode)/ 关联键(词典+矩阵+`interaction_id` 1:N)以 SoT §2.3–§2.5 为准。

> 日期:2026-06-18 · 状态:草案待评审(brainstorming 产出,待 owner 分析后转 writing-plans)
> 定位:production-ready 平台原生能力,**非 MVP/PoC/demo**

## 0. TL;DR

把"埋点 → 上报 → 流处理 → 存储 → 分析 → 治理"全链做成 **AuraBoot 平台原生、可裁剪分层** 的能力。采集走**双轨**(自动 pageview/点击/曝光 + SPM 位置码,位置码从 DSL 组件树自动派生);流处理与存储做成 **SPI 可换 tier**(SaaS 全量档 = Flink + ClickHouse;私有化降级档 = Kafka-consumer 微批 + PG 分区);分析层复用 DSL Dashboard + 新增行为专用 widget;并把现有 `ab_agent_observation` 收编进统一事件模型,让 Agent/AI 行为天然纳入同一套分析。

已澄清的关键决策(brainstorm):
- **部署形态**:SaaS 全量 + 私有化可降级 → 架构分层可裁剪。
- **实时性**:需真实时流处理(秒级在线数 / 实时漏斗 / 实时告警 / 会话化)。
- **采集模型**:双轨(自动 + SPM 声明),以 SPM 声明为骨架、自动采兜底。
- **用途**:① 产品分析 ② 低代码运营回流 ③ Agent/AI 行为。

---

## 1. 背景与目标

### 1.1 诉求
- 像 **百度统计 / Google Analytics / 阿里 SPM** 那样做用户行为采集,但要**平台原生、低代码友好、多租户**。
- 不止"埋点埋好",还要**支撑后续完整数据分析**(UV/PV、漏斗、留存、路径、实时大盘)。
- 服务三类用途:**① 产品分析 ② 低代码运营回流 ③ Agent/AI 行为**。
- 部署:**SaaS 全量 + 私有化可降级**,架构需分层可裁剪。
- 实时性:**需真实时流处理**(秒级在线数 / 实时漏斗 / 实时告警 / 会话化)。

### 1.2 成功标准
1. 任意 DSL 低代码页面**零手写埋点**即自动带 pageview + 元素级 SPM 位置码采集。
2. 关键行动点可**声明式标注**业务语义(event name + 业务参数)。
3. 端到端:浏览器点击 → Kafka → 流处理/落库 → 查询 API → dashboard 数值正确(真栈 golden 可验)。
4. 同一套查询接口下,**ClickHouse 与 PG 后端可换**、**Flink 与微批可换**,私有化档零重组件可运行。
5. Agent/AI 行为与 UI 行为在**同一事件模型与看板**内可统一分析。

---

## 2. 现状盘点(带证据)

| 现状系统 | 是什么 | 性质 | 与行为采集的关系 |
|---------|--------|------|----------------|
| **User Engagement**(`framework.engagement`,`ab_user_engagement`) | 收藏 / 最近访问 / 置顶 | **幂等覆盖**,只存最新状态,无流水,recent_view 硬裁剪 max 20(`UserEngagementServiceImpl:53-83,127-152`) | 已有**全局路由级 pageview 钩子**(`AdminLayout.tsx:37-51` → `recordVisit` → sendBeacon),但落点是状态覆盖表,**没留流水** |
| **ab_agent_observation**(`AgentObservationService`) | Agent/LLM 遥测 | **append-only 流水**,`@Async @EventListener` 异步落库,带时序索引;无采样全量写 | 唯一真正的遥测流水范式;覆盖 agent 维度,非 UI 行为;上有 L3/L4 eval 闭环可借鉴 |
| **EventStore / EventBus**(`framework.meta` / `framework.bpm`) | 业务领域事件(事件溯源 / BPM) | 带 aggregate version 乐观并发 | **不适合**高频无归属行为流水(版本竞争瓶颈) |
| **Kafka**(`KafkaMqProvider`) | 消息总线 | topic 前缀 `aura.event.<type>` + DLQ + 重试 | ✅ 可直接复用作摄取主干;缺 Avro registry、高频分区策略 |
| **TDengine**(`TDengineTimeSeriesPort`) | 时序库,iot 30-100 万测点/租户 | tag 分组 + 时间戳主键 | ✅ 时序存储范式可借鉴;但行为分析需 OLAP 聚合,非纯时序点查 |
| **DSL Dashboard**(`widgetRegistry`) | 35+ 图表,含 **funnel/heatmap/line/combo** 已实装 | `namedQuery` + `POST /api/meta/chart-data` aggregate | ✅ 分析层 ~70% 现成;缺留存/Sankey/实时推送 |
| **DSL 渲染管道**(`BlockRenderer`/`BlockRegistry`) | 每块有 `block.id`/`blockType`,字段有 `field` code | — | ✅ **SPM 位置码可自动派生**的结构性基础;当前无位置码体系、无透传、无曝光/点击监听 |

**结论**:`grep spm` 全仓 0 命中——**无任何通用埋点/分析采集方案**。但全链路零件几乎都现成,缺的是把它们拼成行为采集流水线,尤其缺 **① 事件流水留存 ② 元素级 SPM 位置码**。

---

## 3. Gap 分析(已有地基 × 待建能力)

| 能力层 | 现状 | 复用地基 | 缺口 |
|------|------|---------|------|
| 前端 pageview 自动采集 | 🟡 钩子已有、落点错 | `AdminLayout` 路由 effect + sendBeacon | sink 改到事件流水(别走 engagement upsert) |
| 元素级 SPM 位置码 | ❌ 无 | `BlockRenderer`/`block.id`/`fieldCode` 可派生 | 位置码生成器 + 渲染管道透传 + 曝光/点击监听 |
| 声明式埋点 | ❌ 无 | DSL block schema 有扩展位 | block schema 加 `spm`/`track` 字段 + 渲染注入 |
| 批量上报端点 | ❌ 无 | sendBeacon/keepalive 范式 | 新 `POST /api/collect` beacon controller |
| 摄取管道 | ✅ 基建在 | Kafka MqProvider + DLQ | behavior topic + consumer/Flink source |
| 事件流水存储 | 🟡 部分(仅 agent_obs) | TDengine / agent_obs 范式 | 行为事件明细 + 预聚合(ClickHouse/PG SPI) |
| **流处理(真实时)** | ❌ 无 | — | Flink 作业(会话化/在线数/实时漏斗/告警);微批降级 |
| 多租户隔离 | ✅ 完整 | MetaContext ThreadLocal | 直接用 |
| 采样/限流 | ❌ 无(agent_obs 都全量) | — | 按租户/事件类型采样配置 |
| 基础看板 UV/PV/漏斗 | ✅ 几乎现成 | Dashboard DSL + funnel + namedQuery | 仅建 query + 拼 dashboard |
| 留存/Sankey/实时大盘 | ❌ 无专用件 | chart 家族可扩展 | 新 widget + 离线 ETL + SSE 实时推送 |
| 隐私合规 | ❌ 无 | — | 脱敏 / consent / DNT / 按 userId 删除 |

---

## 4. 设计原则与约束

1. **平台原生,不外挂**:做成 AuraBoot 能力沉淀,吃 DSL 树派生 SPM + 与 Agent observation 统一两个红利。
2. **分层可裁剪(SPI)**:流处理 / 存储两层抽象为 SPI,full 与 minimal tier 可换;私有化档零重组件可跑。
3. **复用优先**:Kafka / sendBeacon / MetaContext / DSL Dashboard / widget 家族 / registry 范式一律复用,不重造(memory:勿重造规则引擎,实时告警下沉 automation/alarm)。
4. **真实可用产品,非 MVP**:每个里程碑是 production-ready 纵深切片(范围小但纵深完整:多租户 + 权限 + 测试 + 监控 + i18n + 隐私)。
5. **本地 host-first 零 docker**:ClickHouse(原生 binary)、Flink(standalone)按 host-first 装入常驻 broker 集;minimal tier 不依赖它们。
6. **隐私内建**:PII 脱敏 / consent / Do-Not-Track / 按用户删除,从 schema 起就有位置。

---

## 5. 目标架构总览

```
┌─────────────────────────── 采集层(Web / Mobile / Server / Agent)───────────────────────────┐
│  双轨 SDK:  自动采集(pageview·click·exposure)   +   SPM 声明(DSL 树派生 a.b.c.d + 手动标注)    │
│  批量缓冲 → navigator.sendBeacon / keepalive fetch                                           │
└───────────────────────────────────────────┬─────────────────────────────────────────────────┘
                                             ▼
                         POST /api/collect  (OSS 核心 BeaconController)
                         · 服务端补全 tenantId/userId(MetaContext,不信客户端)
                         · schema registry 校验 + 采样 + 脱敏 + 背压
                                             ▼
                         Kafka  aura.event.behavior.<eventType>   (复用 KafkaMqProvider + DLQ)
                                             ▼
              ┌──────────────── StreamProcessorPort (SPI) ────────────────┐
              │  full tier:  Flink 作业                 minimal tier: Kafka-consumer 微批  │
              │  · 会话化(session window 30min)         · 批量落明细                        │
              │  · 实时 rollup(1-min tumbling)→在线数/PV/UV   · 周期 rollup 查询             │
              │  · 实时漏斗(CEP) / 实时告警(阈值)                                            │
              └──────────────────────────┬──────────────────────────────┘
                                         ▼
              ┌──────────────── BehaviorStorePort (SPI) ──────────────────┐
              │  full: ClickHouse(明细 MergeTree + 物化视图预聚合)          │
              │  minimal: PG 分区表 + rollup 表   (可选 TDengine)            │
              └──────────────────────────┬──────────────────────────────┘
                                         ▼
        分析层:  /api/analytics/*  (events / aggregate / funnel / retention / path / realtime-SSE)
                 复用 DSL Dashboard + chart 家族(funnel/heatmap 已有)+ 新 widget(retention/sankey/realtime)
                                         ▼
        治理层:  事件 schema registry  +  埋点元数据管理页(低代码)  +  采样/限流配置  +  隐私/数据质量
```

---

## 6. 采集层详设(双轨)

### 6.1 自动采集(无侵入)
- **pageview**:复用 `AdminLayout` 路由 effect,但 sink 改为新 `track()` → `/api/collect`(不再走 engagement upsert)。engagement 的"最近访问"功能保留,改为消费行为流水的派生,或并行保留。
- **click**:在根容器挂**事件委托**(单个 `document` 级 listener),冒泡时读最近祖先的 `data-spm` 解析点位。
- **exposure(曝光)**:全局 **IntersectionObserver**,对带 `data-spm-exposure` 的元素采可见曝光(去重 + 停留时长)。
- **采集中间件**:在 `HttpClient.fetchResult()`(`HttpClient.ts:59-77`)插入可选请求耗时/错误采集(API 性能维度)。

### 6.2 SPM 位置码模型(核心差异化)
**格式**:`spm = a.b.c.d`(对齐阿里 SPM 语义)
- `a` = 应用/业务域(app code,如 `crm` / `iot` / 租户应用标识)
- `b` = 页面(`pageKey`,来自 DSL page)
- `c` = 区块(`block.id` 或 `blockType`,来自 DSL block 树)
- `d` = 点位(`fieldCode` / button `action.command` / rowAction / 自定义元素 code)

**自动派生(关键)**:DSL 页面是组件树,`BlockRenderer`(`BlockRenderer.tsx:62-76`)在渲染每个 block / field / button 时**自动注入 `data-spm="${a}.${pageKey}.${blockId}.${elementCode}"`**。→ 任意低代码页面零配置即全页元素带稳定位置码。
- **稳定性**:位置码锚定 DSL 的稳定标识(pageKey/blockId/fieldCode),而非 DOM 路径,跨版本不漂。
- **手动标注**:block / field schema 增加可选 `spm`(覆盖自动 d 段)与 `track`(声明业务 event name + 业务参数映射)字段,供关键行动点表达语义。
- **可选 scm**(Show Content Model,内容/推荐曝光位):为推荐位/内容卡预留,M4+ 再做。

### 6.3 SDK 形态
- OSS 核心包 `@aura/track`(web-admin `app/shared/track/`):`track(event)` / `trackPageview()` / 自动 click/exposure 安装器 / 批量队列(N 条或 T 秒 flush)/ sendBeacon 优先 + 离线 localStorage 缓冲补发。
- **Mobile**:iOS/Android 各出对等轻量 SDK(同事件信封),M4+ 跟进(本期先 Web 纵深)。

### 6.4 隐私
- 客户端尊重 **Do-Not-Track / consent**;PII 字段白名单 + 服务端脱敏;IP 仅存匿名化网段;支持按 `userId` 的 **GDPR 删除**(下沉到 BehaviorStorePort)。

---

## 7. 统一事件模型(收编 Agent observation)

**事件信封 `BehaviorEvent`**(JSON,跨 Web/Mobile/Server/Agent 统一):
```jsonc
{
  "eventId": "ulid",
  "tenantId": 123,            // 服务端从 MetaContext 补全,不信客户端
  "userId": 456,             // 登录用户;匿名期用 anonId
  "anonId": "uuid",          // 设备/浏览器匿名标识
  "sessionId": "ulid",       // 流处理会话化生成 / SDK 兜底
  "source": "web",           // web | ios | android | server | agent
  "eventType": "click",      // page_view | click | exposure | custom | api | agent_obs
  "eventName": "lead.create.submit",  // 业务语义(声明式)
  "spm": "crm.lead_list.toolbar.create",
  "spmSource": "declared",   // auto | declared
  "pageKey": "lead_list",
  "blockId": "block_toolbar_1",
  "elementCode": "create",
  "ts": 1718700000000,       // 客户端时间
  "serverTs": 1718700000123, // 服务端落地时间
  "props": { "modelCode": "crm_lead", "recordId": "..." },  // 业务参数
  "context": { "ua": "...", "referrer": "...", "route": "/p/c/lead_list",
               "viewport": "1440x900", "appVersion": "...", "locale": "zh-CN" }
}
```
**Agent observation 收编**:`eventType=agent_obs`、`source=agent`,`ab_agent_observation` 的 `observation_type/severity/detail` 映射到 `eventName/props`。→ 现有 agent 遥测**双写或迁移**到统一流水,Agent/AI 行为进同一分析体系(保留现有 L3/L4 eval 链不破坏)。

---

## 8. 上报层 `/api/collect`
- OSS 核心 `BeaconController`,接收**批量数组**,`text/plain`(sendBeacon 限制)或 JSON。
- 职责:**服务端权威补全** `tenantId/userId/serverTs`(从 MetaContext / session,绝不信客户端 tenant 绑定)→ schema registry 校验 → 采样判定 → 脱敏 → 投递 Kafka。
- 背压:超限快速 202 丢弃 + 计数;失败不影响主业务(fire-and-forget 语义)。
- 反滥用:匿名上报限流(IP/anonId 维度)。

---

## 9. 摄取与总线
- Kafka topic:`aura.event.behavior.<eventType>`(按 eventType 分,便于流处理订阅);高基数 key 用 `tenantId:anonId` 保证同会话有序。
- 复用 `KafkaMqProvider` + DLQ;**启用 Avro/schema registry**(平台已有 `KafkaSchemaRegistryClient` 未用)做强类型契约。
- 分区策略:按 `tenantId` 哈希,保证多分区水平扩展且同租户局部有序。

---

## 10. 流处理层 `StreamProcessorPort`(SPI)

| 作业 | full tier(Flink) | minimal tier(Kafka-consumer 微批) |
|------|-------------------|-----------------------------------|
| 会话化 | session window(gap 30min)→ `sessions` | SDK 端兜底 sessionId + 周期补算 |
| 实时 rollup | 1-min tumbling → 在线数 / PV / UV / 各 SPM 点击 | 每 N 秒批量 + 周期聚合查询 |
| 实时漏斗 | CEP 模式匹配 | 不支持秒级(降级 T+0 批漏斗) |
| 实时告警 | 阈值/突变 → 复用 automation/alarm 引擎 | 周期批检测 |

- **SPI 抽象**:`StreamProcessorPort` 定义"消费 behavior topic → 产出 rollup/session/alert",full=`FlinkStreamProcessor`,minimal=`MicroBatchStreamProcessor`。配置 `aura.analytics.stream.tier=flink|microbatch`。
- **复用**:实时告警下沉到平台已有 **automation/alarm-worker** 引擎(memory:勿重造规则引擎),流处理只产出信号。

---

## 11. 存储层 `BehaviorStorePort`(SPI)

**接口**(仿 `TimeSeriesPort`):
```java
interface BehaviorStorePort {
  void writeBatch(List<BehaviorEvent> events);
  PageResult<BehaviorEvent> queryEvents(EventQuery q);
  AggregateResponse queryAggregate(AggregateRequest q);   // 复用 chart-data shape
  FunnelResponse queryFunnel(FunnelRequest q);
  RetentionResponse queryRetention(RetentionRequest q);
  PathResponse queryPath(PathRequest q);                  // Sankey
  RealtimeSnapshot queryRealtime(RealtimeRequest q);      // 在线数/实时大盘
  void deleteByUser(long tenantId, long userId);          // GDPR
}
```

| tier | 实现 | 明细 | 预聚合 | TTL |
|------|------|------|--------|-----|
| **full** | `ClickHouseBehaviorStore` | MergeTree,`PARTITION BY (tenantId, toYYYYMMDD(ts))`,`ORDER BY (tenantId, eventType, ts)` | 物化视图(daily/hourly UV/PV/funnel-step per page/spm) | 明细 90d、聚合长留(可配) |
| **minimal** | `PostgresBehaviorStore` | 按月分区表 | 定时 job rollup 表 | 明细 30d |
| 可选 | `TDengineBehaviorStore` | 复用 iot 时序栈(点查强、复杂 OLAP 弱) | — | — |

配置 `aura.analytics.store.tier=clickhouse|postgres|tdengine`。

---

## 12. 分析层

- **查询 API**:`/api/analytics/{events,aggregate,funnel,retention,path,realtime}` → 路由到 `BehaviorStorePort`;沿用 `chart-data` 的 `AggregateRequest` 形状,前端复用 `chartDataService` 范式。
- **看板**:复用 **DSL Dashboard + 现有 widget**(`smart-funnel-chart`/`smart-heatmap-chart`/`smart-line-chart`/`smart-number-card` 已有)。
- **新增 widget**(前端 BlockRegistry + 后端 DslRegistry.BlockType **两边都注册**,避免 S-PAGE-BLOCK-TYPE):
  - `smart-retention-chart`(留存 cohort)
  - `smart-sankey-chart`(用户路径)
  - `smart-realtime-board`(实时大盘,SSE/WebSocket 推送,复用 `ResponseSink`/SSE 范式)
- **探索查询**:基于 namedQuery + query-builder 给运营做自助分组聚合。

---

## 13. 治理层

- **事件 schema registry**(`ab_behavior_event_schema`,仿 DSL `registry` 范式):定义合法 `eventType/eventName/props schema`,上报时校验,未知事件**隔离到 quarantine** 而非静默吞(可观测)。
- **埋点元数据管理页**(低代码 DSL 页,非 tsx):管理 SPM 码 / event 定义 / 查看**埋点覆盖率**(哪些页面/行动点已埋)。
- **采样 / 限流**:按租户 × eventType 配采样率(客户端 + 服务端双重)。
- **数据质量**:维度字典、必填校验、坏数据看板。

---

## 14. 多租户与权限
- 每条事件 `tenantId` 由服务端从 MetaContext 权威绑定(客户端不可伪造)。
- 新权限码族 `analytics.*`(`analytics.dashboard.view` / `analytics.event.manage` / `analytics.raw.query` …),按 `module.resource.action` 规范 + bootstrap 注册 + `@RequirePermission`。
- 跨租户隔离:所有查询强制 `tenantId` 谓词;raw query 受 `analytics.raw.query` 管控。

---

## 15. 三类用途落地映射
| 用途 | 落地 | 吃到的红利 |
|------|------|-----------|
| **产品分析** | UV/PV/漏斗/留存/路径 dashboard + SPM 点位分析 | 看板 70% 现成 |
| **低代码运营回流** | DSL 树派生 SPM → 配置页面即自动带埋点 → 页面/行动点使用度回流优化、A/B | **别家手写埋点做不到**的结构性优势 |
| **Agent/AI 行为** | agent_obs 收编统一事件模型 + 统一看板 | 复用现有 observation + L3/L4 eval |

---

## 16. 分层裁剪矩阵(SaaS full vs 私有化 minimal)
| 层 | SaaS full | 私有化 minimal |
|----|-----------|---------------|
| 采集 SDK | 双轨全量 | 同 |
| 上报 `/api/collect` | 同 | 同 |
| 总线 | Kafka + Avro | Kafka(可单分区) |
| 流处理 | **Flink** | **Kafka-consumer 微批**(无秒级实时) |
| 存储 | **ClickHouse** | **PG 分区**(可选 TDengine) |
| 分析 | 全 widget(含实时大盘) | 实时大盘降级为轮询/不可用,其余可用 |
| 新增依赖 | ClickHouse + Flink(host-first) | **零新增**(全复用现有 Kafka/PG) |

---

## 17. 里程碑(production-ready 纵深切片)
- **M1 采集底座纵深**:统一事件模型 + `/api/collect` + Kafka 摄取 + 最小存储(PG)+ 自动 pageview/click + 基础 UV/PV/Top 页面 dashboard + 多租户 + 权限 + 隐私基线 + 真栈 golden。**端到端可用**。
- **M2 SPM 双轨 + 治理**:DSL 树派生 SPM 位置码 + 曝光采集 + 声明式 `track`/`spm` schema + schema registry + 埋点元数据治理页 + 漏斗分析(复用 funnel widget)。
- **M3 ClickHouse tier + 深度分析**:`BehaviorStorePort` SPI + ClickHouse 明细/物化视图 + 留存/路径 widget + 探索查询。SaaS 全量档成型。
- **M4 实时流处理 + Agent 统一**:`StreamProcessorPort` + Flink(会话化/在线数/实时漏斗/实时告警)+ 实时大盘 widget + agent_obs 收编统一看板。Mobile SDK 跟进。

每个 M 都含成对 golden(浏览器 E2E + 后端运行时/落库反查),非 happy-path 单边。

---

## 18. 测试策略(真栈)
- **采集 SDK**:SPM 派生纯函数单测 + 真浏览器 E2E golden(点击 → `/api/collect` 收到正确 spm/props → 落库反查)。
- **摄取/流处理**:真 Kafka round-trip IT + 流作业落 rollup 断言(full=Flink mini-cluster,minimal=consumer)。
- **存储 SPI**:ClickHouse / PG 双实现各跑契约测试(同一 `BehaviorStorePort` 测试套)。
- **分析**:dashboard golden(真数据 → 真 widget 渲染 → 断言数值,非"页面渲染了")。
- **跨层 seam**:assembled-product 运行时 golden(浏览器点击 → Kafka → 流处理 → 存储 → 查询 API → dashboard 数值闭环)。
- host-first 零 docker(ClickHouse/Flink 原生装入常驻 broker 集)。

---

## 19. 风险与缓解
| 风险 | 缓解 |
|------|------|
| ClickHouse/Flink 破"本地零 docker" | host-first 原生 binary 装入常驻 broker;minimal tier 完全不依赖二者 |
| 高频写打爆存储 | 采样 + 批量 + 背压 + 分区 + TTL |
| SPM 码跨版本漂移 | 锚定 DSL 稳定标识(pageKey/blockId/fieldCode)+ registry 锚定,不用 DOM 路径 |
| 隐私合规 | consent/DNT/脱敏/按 userId 删除内建 |
| 与现有 EventStore/EventBus 混淆 | 行为流水**独立**,不复用领域事件溯源表(版本竞争不适用) |
| agent_obs 收编破坏现有 eval | 先**双写**过渡,L3/L4 链不动,验证后再切 |
| Flink 私有化运维重 | 私有化默认 minimal 微批档;Flink 仅 SaaS/愿意承担的客户启用 |

---

## 20. 待确认问题
1. **规模假设**(SaaS 千万级/天、私有化 ≤百万级/天)是否符合预期?影响采样与存储 sizing。
2. **流处理引擎**:full tier 锁 **Flink**,还是接受 **Kafka Streams**(更轻、与现有 Kafka 同栈)作为 full 的另一选项?
3. **agent_obs**:走"双写过渡后迁移"还是"长期双轨保留"?
4. **Mobile 采集**:本期(M1-M4)只做 Web 纵深、Mobile 放 M4+,可接受吗?
5. **埋点元数据治理页**优先级:M2 必须,还是可推迟到 M3?

---

## 附:决策记录(brainstorm 已定)
- 部署形态 = 两者都要(可裁剪)→ 分层 SPI。
- 实时性 = 需真实时 → 流处理层(Flink full / 微批 minimal)。
- 采集模型 = 双轨(自动 + SPM 声明)。
- 推荐方案 = 方案 A(自建分层平台,最大化复用现有平台基建);方案 B(轻量不上 Flink/ClickHouse)收编为 A 的私有化降级档;方案 C(接入 PostHog 等外部分析平台)排除(破坏平台沉淀/低代码原生/多租户复用)。

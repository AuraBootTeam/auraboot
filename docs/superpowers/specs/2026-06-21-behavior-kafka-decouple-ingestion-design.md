---
type: plan-design
status: active
created: 2026-06-21
slug: behavior-kafka-decouple-ingestion-design
related:
  - docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md
  - docs/backlog/2026-06-21-site-key-anonymous-telemetry-subsystem-decomposition.md
  - docs/superpowers/specs/2026-06-21-site-key-sp3-sp4-public-sdk-and-golden-design.md
---

# 行为采集 Kafka 解耦层(`aura.behavior.events.v1`)— 设计方案

> 遥测平台**下一线**。统一遥测 SoT(`2026-06-19-...-architecture.md`)§12 M1「行为采集底座」backlog 中,除本切片外其余全部 ✅ 已交付:`/api/collect`(M1)+ UV/PV 分析 API + `@auraboot/track` SDK(#966)+ **公开/匿名 ingestion 全链(SP1-SP4,本会话)**+ UV/PV dashboard。**唯一剩**:`aura.behavior.events.v1` Kafka 解耦层(+ 独立的 server-outcome outbox 线,非本切片)。代码已自标:`BehaviorCollectService` javadoc「The Kafka decoupling layer (aura.behavior.events.v1) is the production ingestion path (follow-up)」。

## 0. 用户/系统场景

当前 `/api/collect`(鉴权)和 `/api/collect/keyed`(匿名,SP2)在 **HTTP 请求线程内同步**把事件写 `ab_behavior_event`。问题:① 写库慢/抖动直接拖慢采集端点延迟(公开端点尤甚);② 写库故障 = 丢事件(无重试/缓冲);③ 无背压、无回放;④ 脏事件(SP4 resilience 跳过的超长/malformed)被静默 skip,不可观测。**解耦**=端点只做**同步校验 + 入队**(快速返回),由 consumer **异步、可重试、幂等**落库;脏事件进 quarantine topic(可观测、可回放),不是黑洞。

## 1. 锁定的设计决策(SoT 已冻结契约 + 阶段0 实证)

| # | 决策 | 选定 | 理由 / 证据 |
|---|------|------|-------------|
| D1 | MQ 实现 | **复用平台 MQ 抽象 `MqProperties`(type: memory \| redis \| kafka)** | 阶段0 实证:平台已有 `infrastructure/mq/MqProperties`(memory/redis/kafka/rabbitmq 可插拔)。本地/host-first golden 用 `memory`(**零 docker**,§11),prod 用 `kafka`。**禁**直接裸 `KafkaTemplate` 绑死 kafka |
| D2 | 校验 + 鉴权在哪跑 | **端点同步跑(入队前)**:keyed guard(resolve tenant / origin / 限流 / batch 上限)在 `/api/collect/keyed` **同步**执行,未知/disabled/超限**同步**返 403/429/400;只有通过校验的事件带**已解析的 tenant**入队 | 公开端点必须同步拒绝滥用(SP2 契约);consumer 信任入队载荷的 tenant,不重解析。鉴权态同理:tenant/user 在端点从 `MetaContext` 解析后入队(consumer 无请求上下文) |
| D3 | quarantine 路由 | **malformed(缺 eventId/eventName)+ 约束违规(超长字段等 `DataIntegrityViolationException`)→ `aura.behavior.quarantine.v1`(带 reason)** | 把 SP4 的 `recordBatch` resilience(本会话 OSS#1013:逐事件 skip)**升级为可观测 quarantine**,而非静默 skip。SoT §2.7 topic + §5.5「未知 event_name 进 quarantine」|
| D4 | 可靠性级别 | **at-least-once + 幂等 consumer**(PG `unique(tenant_id,event_id)` 去重重投)+ producer idempotence;**非** exactly-once | SoT §2.4/§2.5:behavior 是 at-least-once + 幂等;幂等键已在 PG(本会话 SP2 已验 DuplicateKey→accepted)|
| D5 | 响应语义 | `{accepted:n}` = **入队成功数**(通过端点校验 + publish 成功);异步落库失败走 quarantine + metrics,不回 HTTP | 解耦的本质;客户端 keepalive/beacon 不等落库 |
| D6 | rollout / 回退 | **`MqProperties.type` 即开关**:`memory`=进程内同步消费(等价旧同步路径,零行为变化,默认本地)/`kafka`=真异步解耦(prod)。无需额外 feature flag | memory provider 让既有 golden(SP4 AK-*)**零改动**仍绿(同步消费),kafka 路径由新 IT 验异步 |

## 2. 组件与边界

| Unit | 做什么 | 依赖 |
|------|--------|------|
| `BehaviorIngestPublisher`(新) | 接「已校验 + 已解析 tenant/user/anon 的事件批」→ publish 到 `aura.behavior.events.v1`(经平台 MQ port)| 平台 MQ 基础设施(MqProperties)|
| `BehaviorIngestConsumer`(新) | 订阅 `aura.behavior.events.v1` → 复用 `BehaviorCollectService` 的 `toEntity`+insert 落 `ab_behavior_event`;malformed/约束违规 → publish `aura.behavior.quarantine.v1`(带 reason)| MQ port + `BehaviorEventMapper`(现有)|
| `BehaviorCollectService`(改) | `record`/`recordAnonymous` 由**同步 recordBatch**改为**校验 + enqueue via publisher**;`recordBatch`(持久化 + 幂等 + quarantine 路由)下沉为 consumer 调用 | publisher / mapper |
| `KeyedCollectController` / `BehaviorCollectController`(不改契约) | 端点形态/请求响应 shape 不变(SP2/SP3 契约冻结);仅内部从 sync-persist 变 enqueue | guard / service |
| topic 定义 + consumer group | `aura.behavior.events.v1` / `aura.behavior.quarantine.v1`(SoT §2.7 冻结名)+ consumer group | MqProperties |

> **边界**:端点的**同步校验**(keyed guard / tenant 解析 / batch 上限)**不动**(SP2 契约);改的只是「校验后 → sync insert」变「校验后 → enqueue」。两条采集路径(auth / keyed)同一 publisher、同一 topic、同一 consumer、同一 store。

## 3. 数据流(解耦后)

```
访客/用户 → POST /api/collect[/keyed]
     │  端点同步:keyed guard(resolve tenant/origin/限流/batch 上限)| 或 JWT→tenant/user
     │  通过校验的事件(带已解析 tenant/user/anon)
     ▼
  BehaviorIngestPublisher.publish(batch) ──► aura.behavior.events.v1   ◄── 端点到此返回 {accepted:n}
     ▼ (异步)
  BehaviorIngestConsumer  ── recordBatch ─► ab_behavior_event(幂等 unique(tenant_id,event_id))
     │  malformed / 约束违规(超长等)
     ▼
  aura.behavior.quarantine.v1(reason)  ── 可观测 / 可回放 / DLQ age SLO(SoT §5.7)
```

## 4. 测试策略(host-first 零 docker;memory + 真 kafka 两档)

| 层 | 覆盖 |
|----|------|
| 单测 | `BehaviorIngestPublisher`(publish 正确 envelope 到 events topic);consumer(persist 到 PG;malformed/约束违规 → quarantine topic + reason);`record`/`recordAnonymous` 改为 enqueue(不再同步写库)|
| 真栈 IT(memory provider) | 端点 → publish → consumer → PG round-trip(同步消费,等价旧路径,既有断言全绿);幂等(同 event_id 重投 → 1 行);malformed/超长 → quarantine 有该条 + ab_behavior_event 无;keyed guard 仍**同步**拒未知/disabled/超限(入队前)|
| 真栈 IT(kafka provider,host-first 原生 broker) | 真异步:publish → 真 broker → consumer 落库(轮询至可见);broker 重投 → 幂等 1 行;consumer 抛 → 重试 → 最终 quarantine |
| 端到端 golden | SP4 匿名 loop(AK-02/03/04)在解耦路径下仍绿(事件异步落库后 dashboard UV 计匿名);新增 quarantine golden(发一条超长 → quarantine topic 有、PG 无、端点仍 200)|
| 静态门禁 | check-jsonb-typehandler / oss-boundary / 复用 SP4 的真栈 golden harness |

## 5. 交付定义(完成判定)

- [ ] `/api/collect` + `/api/collect/keyed` 内部改为校验 + enqueue;端点契约(请求/响应 shape)零变化(回归 SP2/SP3 IT + SP4 golden 全绿)。
- [ ] consumer 异步落 `ab_behavior_event`;幂等(重投 1 行)真栈验证(memory + kafka 两档)。
- [ ] malformed/约束违规 → `aura.behavior.quarantine.v1` 带 reason;quarantine golden(端点 200、PG 无、quarantine 有)。
- [ ] keyed guard 仍在端点**同步**拒滥用(入队前;SP2 防护不被解耦削弱)。
- [ ] `MqProperties.type=memory` 本地/golden 全绿(零行为回归);`=kafka` host-first 原生 broker 真异步 IT 绿。
- [ ] host-first 零 docker;静态门禁绿。

## 6. 非目标(不在本切片)

- server-outcome publisher(outbox,同业务库事务)= 遥测平台另一独立线(SoT §2.4/§2.6;agent outcome `agent.task.completed` 等)。
- OTel / 技术可观测(域 C)= 独立大线(SoT §1.1)。
- §5.4 完整 UI 元素身份治理 / 删除 SPM = 独立线(R3)。
- Schema Registry / Avro `BACKWARD_TRANSITIVE` 强校验(SoT §2.0)= 可作为本线 hardening follow-up(先用现有 JSON 信封 round-trip 契约测试,Avro 注册表后续)。

## 7. 执行顺序(build,fresh focused session)

1. Phase 0:确认平台 MQ port 接口(`MqProperties` + 现有 producer/consumer 范式,grep `infrastructure/mq` 全量)+ 选 publish/subscribe API。
2. TDD:publisher → consumer(含 quarantine 路由)→ service enqueue 改造(单测先行)。
3. 真栈 IT:memory 档(等价回归)→ kafka 档(host-first 原生 broker,异步 + 幂等 + 重试→quarantine)。
4. golden:SP4 AK-* 解耦路径回归 + quarantine golden。
5. 收口:回归 SP2/SP3 IT + SP4 golden 全绿;PR;canonical 固化(MQ-decouple 的端点-入队-消费-quarantine 模式)。

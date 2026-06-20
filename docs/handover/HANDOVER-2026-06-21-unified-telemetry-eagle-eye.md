---
type: handover
status: active
---

# Session Handover - 2026-06-21

## Session Summary
统一遥测与分析平台("分布式鹰眼" + 数据分析)的端到端开发会话(跨日,aura-endgame 编排)。从设计冻结 + P0 实证出发,逐切片交付并真栈 golden 了**两大域的后端埋点 + 分析 + 统一关联三层**:可观测域(A-G1..A-G6,含 LLM 成本端到端)+ 行为域(M1 采集 + UV/PV 分析)+ **统一鹰眼关联视图**(成本+行为+审计 by trace_id)。~18 实现 commit 全落 PR #909,SoT 落 PR #910,全 golden 无假报。

## Tasks Completed(本会话,均真栈 golden)
- [x] P1 trace correlation(早段,7 commit):ab_ai_trace↔OTel 桥接(运行时验证)+ 审计三表 trace_id stamp + 日志 %X{traceId} + A-G4 producer W3C traceparent(单测)+ 迁移 V20260620000000
- [x] **A-G6 LLM 成本可观测端到端**:`LlmProvider.chat/streamChat` chokepoint decorator(`UsageRecordingLlmProvider`,覆盖全 LLM 路径)+ `ab_gen_ai_usage` 账本 + `GenAiPricing`(单测 4/4)+ `GET /api/ai/usage/summary` 分析 API + 成本↔trace 关联(§2.6 seam-snapshot `MetaContext.OTEL_TRACE_ID`)。**6 轮 golden** 逐层定位 chokepoint 根因
- [x] **B1 审计↔trace golden**:`POST /environments/{pid}/lock` → `ab_admin_event_log.trace_id == X-Trace-Id`(admin 已验;command/query 同请求线程 Tracer 模式)
- [x] **M1 行为采集底座**:`/api/collect` + `ab_behavior_event`(§5.5 冻结 event-first 信封,ui_element_id 稳定键,props jsonb,幂等 unique(tenant_id,event_id))+ server 补全 tenant/user
- [x] **M1 行为分析 API**:`/api/analytics/behavior` overview(PV/UV/sessions)+ top-events + daily trend(时间序列)
- [x] **🦅 统一关联视图(鹰眼)**:`GET /api/observability/correlation/{traceId}` 按 trace_id JOIN 成本+行为+审计三域。golden:chat trace → {llmUsage:5, behaviorEvents:1, auditEvents:0} 全关联
- [x] 收口:SoT §4.2/§12 逐项 golden 状态在册;MEMORY active-work 遥测行更新为实况;golden 隔离栈 obs-golden-54..67 全部清理(含揪出 2 个 orphan backend)

## Tasks In Progress / 未起(剩余多周重型)
- [ ] **匿名 /api/collect**(write-key):当前仅 authenticated 采集 → 真匿名多访客 UV 需未登录 + tenant write-key 解析
- [ ] **Kafka 解耦层** `aura.behavior.events.v1`(契约 §2.7 生产 ingestion:/api/collect → Kafka → consumer → PG;当前直写 PG 是 minimal)
- [ ] **前端 `@aura/track` SDK + 自动 pageview/click + UV/PV dashboard(DSL)** — 需真浏览器 golden(Playwright + host Vite/BFF)
- [ ] **A-G4 consumer 侧**:跨语言下游 worker 续 span + round-trip 契约测试
- [ ] **P2-P5**:agent/LLM→OTel span(GenAI semconv)/ OTel Collector host-first → Kafka → ClickHouse / 分析层 SRE·安全 / eval 飞轮
- [ ] **M2-M4**:UI 元素身份治理页 / ClickHouse tier(AnalyticalStorePort)/ Flink 实时流 + Agent 业务结果 outbox
- [ ] **server BusinessEvent via outbox**(§2.4:业务完成由服务端事务 seam 发布,非按钮点击)
- [ ] **B1 full**:command/query 审计 golden(需 seed model+命令触发)

## Key Decisions
| Decision | Chosen Approach | Rationale | Alternatives |
|----------|----------------|-----------|--------------|
| LLM 成本捕获点 | `LlmProvider.chat/streamChat` chokepoint decorator(`LlmProviderFactory.getProvider` 包装) | 6 轮 golden 证明:per-path 插桩(recordGeneration / streamProvider)漏掉默认 tool-loop 路径;chokepoint 覆盖全部 LLM 调用一次 | per-path 捕获(漏路径)、AuraEventBus(语义不符) |
| async 线程 trace/tenant | seam-snapshot:请求线程捕获存 `MetaContext`(随 tenant 同传播),decorator 读 | OTel span 不传播到 async turn 线程(§2.6,同 A-G1 桥接坑);MetaContext 已被 controller 重设可达 | tracer.currentSpan()(async 为 null)、threading LlmChatRequest(多 build 点) |
| 成本账本 vs 诊断 span | 独立 `ab_gen_ai_usage` 账本(计费权威),不从 sampled OTel span 求和 | SoT §2.5;采样 span 不能做计费 | 复用 ab_ai_trace_span(可采样) |
| 跨域关联键 | `trace_id`(OTel traceId,贯穿 gen_ai_usage/behavior/audit/ai_trace.otel_trace_id) | 唯一三域共享的稳定键;interaction_id 仅行为域有 | interaction_id(域内)、session_id(弱) |
| 行为采集 ingestion | /api/collect 直写 PG(minimal)+ Kafka 作 follow-up 解耦层 | 第一片可验证 + 契约 event-first 模型先落地;Kafka 背压层后补 | 直接全 Kafka 管道(本片过重) |

## Files Changed(本会话,obs-p1 worktree → PR #909)
### Backend — 可观测/成本(A-G6)
- `framework/observability/GenAiPricing.java`(新,纯函数定价 + 单测)、`agent/trace/entity/GenAiUsageRecord.java`、`agent/trace/mapper/GenAiUsageMapper.java`(+summary 聚合)、`agent/trace/GenAiUsageRecorder.java`(新,DRY 写账本)、`agent/trace/GenAiUsageController.java`(新,/api/ai/usage/summary)
- `agent/provider/UsageRecordingLlmProvider.java`(新,chokepoint decorator)、`agent/provider/LlmProviderFactory.java`(包装 getProvider + 注入 recorder/Tracer)
- `agent/trace/AiTraceService.java`(recordGeneration 去内联→收口到 decorator)、`agent/runtime/ChatTurnRuntime.java`(revert 内联捕获)、`aurabot/controller/AuraBotController.java`(MetaContext.setOtelTraceId seam-snapshot,两 worker)、`application/tenant/MetaContext.java`(OTEL_TRACE_ID ThreadLocal)
- 迁移 `db/migration/core/V20260620000100__gen_ai_usage_ledger.sql`
### Backend — 行为(M1)
- `framework/behavior/`:entity/`BehaviorEvent`、mapper/`BehaviorEventMapper`(+overview/topEvents/dailyTrend)、dto/(`BehaviorEventInput`/`CollectRequest`/`BehaviorOverview`/`BehaviorEventCount`/`BehaviorDailyPoint`)、service/`BehaviorCollectService`、controller/`BehaviorCollectController`+`BehaviorAnalyticsController`
- 迁移 `db/migration/core/V20260620000200__behavior_event_store.sql`
### Backend — 统一关联(鹰眼)
- `framework/observability/`:dto/`CorrelationView`、`CorrelationQueryService`、`CorrelationController`(/api/observability/correlation/{traceId})
### Docs(SoT worktree → PR #910)
- `docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md`(§4.2/§12 逐项 golden 状态)

## 反思与经验固化 (Reflection & Codify)
### 本会话弯路 / 返工 / 翻车
1. **A-G6 成本捕获点用 6 轮 golden 才定位** — 代价:~5 轮起栈(每轮 ~5min)逐层排除(recordGeneration-only → streaming 未捕获 → tenant-null → traceId-null → 默认 chat 走 tool-loop adapter 绕过 per-path)— 本可如何更早避免:**做"在 chokepoint 捕获"类特性前,先 grep 该接口(LlmProvider.chat/streamChat)的全部调用路径**(streamProvider / tool-loop adapter / continuation / scoring / NlModeling),确认有多条路径就直接选 chokepoint decorator,而非先插一条路径再 golden 发现漏。— 根因:`D 验证(发现序)` —— 不过每轮 golden 都真抓到一层真因,是 golden 纪律的正向价值,非纯浪费
2. **golden 隔离栈 2 次留 orphan backend(golden65/66)** — 代价:后续轮 DROP DATABASE 被 "N sessions using" 挡,残留 auraboot_65/66;追查发现 cleanup 的 `kill $JPID` 杀到了子进程/watcher,真 `java -jar` listener(50495/27372)还活着占 DB 连接 — 本可如何更早避免:**清理 host-first golden 后端一律用 `lsof -nP -iTCP:<port> -sTCP:LISTEN -t` 找真 listener PID 再 kill,且 drop DB 前先 `pg_terminate_backend` 终止该 DB 连接,清理后 `lsof :port` 复核端口真空**。— 根因:`A 门禁(清理纪律不严)`
3. **UV golden 首测期望错**(给 authenticated 事件塞不同 anon_id 期望 UV=2,实际 1) — 代价:~1 次诊断 — 本可如何更早避免:理解 `COALESCE(user_id, anon_id)` 语义(登录用户按 user_id 计)。非代码 bug,API 与 DB 一致。— 根因:`D 验证(测试设计)`

### 为什么会发生(根因小结)
主要 `D 验证`(chokepoint 发现序 + 测试期望)与 `A 门禁`(host-first 清理纪律)。无 B/C 类(输入/提示词)问题——SoT 契约清晰、worktree 隔离一直正确。

### 应该有哪些改进
- **chokepoint 类特性**:实现前先 grep 接口全调用路径,多路径直选 decorator(写进 spike/verification 纪律)
- **host-first golden 清理**:固化"lsof 找真 listener + pg_terminate 再 drop + 端口复核"三步;cleanup 脚本不可信 `$JPID` 单值

### 已固化 / 待固化(更新文档)
- [x] SoT `docs/backlog/2026-06-19-...platform-architecture.md` §4.2/§12:每切片 golden 状态 + 6 轮定位过程 + 剩余清单(已逐 commit 写入,PR #910)
- [x] `MEMORY.md` active-work 遥测行:更新为两大域后端+分析层全 golden 实况 + 剩余
- [ ] 待固化 `auraboot-enterprise/docs/agent-rules/engineering-gotchas/deploy-docker-env.md`(或 test-infra.md):**host-first golden 后端清理三步**(lsof 找真 listener / pg_terminate 再 drop / 端口复核),草稿见上「改进」第 2 条。留 owner 决策(跨仓 canonical,需 worktree,本会话未写避免扩面)
- [ ] 待固化 `spike-verification-discipline.md`:**chokepoint 捕获类特性先 grep 接口全调用路径再选插桩点**。留 owner 决策

## 运行态快照 (Operational State)
### 分支 / Worktree / PR
- **当前分支(实现)**:`feat/obs-p1-trace-correlation`(base main,ahead/behind **39/18**),HEAD `659ced991`,与 origin 同步
- **SoT 分支**:`feat/unified-telemetry-analytics-platform`,HEAD `9d28f2c25`,与 origin 同步
- **Worktree**:实现 `/Users/ghj/work/auraboot-obs-p1`;SoT `/Users/ghj/work/auraboot-unified-telemetry-platform`(两者均 clean)
- **PR**:`#909 · OPEN · head 659ced991 · base main`(实现)/ `#910 · OPEN · head 9d28f2c25 · base main`(SoT)。**均未合**,待 owner review/merge(ahead 39 = 累积本会话全部实现 commit)
- **未提交改动**:无(两 worktree clean,全 push)

### Runtime / 端口(host-first slot,零 docker)
- **golden 隔离栈全部已清**:obs-golden-54..67 全 destroy;`auraboot_6x` DB 0 残留;端口 6454-6467 全 clean;0 obs-golden runtime。常驻 broker(Postgres 5432 / Redis 6379)未动
- **接手者起栈命令**(复刻任一 golden):`./dev.sh runtime allocate auraboot <name> --slot <n>` + `infra ensure` + flyway-migrate(`scripts/db/flyway-migrate.sh --edition oss` 带 PG_DB)+ `java -jar platform/build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar`(env 见 `.workspace/env/<name>.env`,设 `MANAGEMENT_TRACING_ENABLED=true`)+ bootstrap + curl
- **DeepSeek**:shell `DEEPSEEK_API_KEY` standing auth,`java -jar` 继承即真调

### Database / Seed
- 迁移:本会话新增 V20260620000100(gen_ai_usage)+ V20260620000200(behavior_event);flyway-migrate 干净应用。接手新栈先 flyway-migrate 再 bootstrap

## Next Steps(优先级)
1. **owner 决策 PR #909/#910 是否 merge**(累积 ~18 实现 commit,全 golden;merge≠生产部署)
2. 下一切片(backend tractable):**Kafka 解耦层**(契约生产 ingestion)或 **匿名 /api/collect**(write-key,真匿名 UV)或 **server BusinessEvent outbox**(§2.4)
3. 重型(需专门多会话):前端 `@aura/track` SDK + UV/PV dashboard(真浏览器 golden)、P3 OTel Collector + ClickHouse host-first、A-G4 跨语言 consumer round-trip
4. 收尾两条 [ ] 待固化(host-first 清理三步 / chokepoint 先 grep 全路径)

## Context for Next Session
- 起点真源:SoT `auraboot-unified-telemetry-platform/docs/backlog/2026-06-19-unified-telemetry-analytics-platform-architecture.md` §12(每切片精确触点 + golden 状态)
- 实现全在 `feat/obs-p1-trace-correlation`(PR #909);新切片基于它或新分支
- 并发检测:本会话 telemetry 工作与其它 active 会话(commerce/designer/AMOS 等,见 MEMORY)互不重叠(独立 worktree/包);开新切片前 `git log origin/main` 核对
- 鹰眼验证回路样板:chat 抓 `X-Trace-Id` → POST /api/collect 带 trace_id → `GET /api/observability/correlation/{T}`(见本会话 golden)

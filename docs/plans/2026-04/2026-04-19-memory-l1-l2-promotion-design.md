# L1 → L2 记忆晋升机制设计（Working Memory → Long-Term Memory）

**状态**：设计稿（未实现）
**起始 PR 编号**：PR-82（延续 memory-promotion PR-65..71 与 user-soul-profile PR-75..80 的编号体系）
**相关子系统**：
- `docs/core-concepts/memory-promotion.md`（user scope → tenant scope，**不是本设计**的范畴）
- `docs/core-concepts/user-soul-profile.md`（派生 summary，不是 L2 本身）
- `docs/core-concepts/learning-loop.md`（action → skill 晋升）

---

## 1. 背景

### 1.1 当前记忆生命周期现状

AuraBoot 的 agent 记忆分两个正交维度：

| 维度 | 取值 | 语义 | 字段 |
|------|------|------|------|
| **Lifecycle Tier（生命周期层）** | `session` / `user` / `agent` | 这条记忆属于"现在 / 一段时间 / 永久"哪一档 | `ab_agent_memory.category` |
| **Access Scope（访问边界）** | `user` / `tenant` / `global` | 谁能看到 | `ab_agent_memory.scope` + `scope_key` |

本设计只讨论第一维——Lifecycle Tier。

约定：
- **L1（Working Memory / 短期工作记忆）** = `category='session'`：在单次 agent run 期间写入，默认通过 `MemoryDecayService` 按 `staleDays=30` 线性衰减 `importance`，降到 0 即软删。`AgentMemoryConsolidationService.promoteSessionMemories(tenantId, agentCode, threshold)` 已存在一个**手工触发**的 session → user 升级路径，但没有真正的调度器、去重、打分。
- **L2（Long-Term Memory / 长期记忆）** = `category ∈ {user, agent}`：由 `MemoryDecayService` 按 `staleDays=30` 衰减 `importance`（步长 1），`importance <= 0` 时软删。`shareable=TRUE` 的行被保护不衰减。

### 1.2 使用路径

```
user message ──▶ GroundingService
                    │
                    ├─▶ ActiveMemoryService.snippet()
                    │       ├─ 关键字命中：L1 + L2 全量 scope-filtered
                    │       └─ importance 召回：top-N L2 user/tenant/global
                    │
                    └─▶ UserSoulProfileReader.loadForGrounding() — 派生 summary
                            └─ 来自 ≥ 3 条 high-importance `scope='user'` 记忆

AgentRunService.loadMemorySection() — prompt 拼装阶段再次读取 L2
```

关键观察：**L1 当前也会被注入 prompt**（`ActiveMemoryService` 不区分 category），长会话的 L1 无限堆积直接污染 LLM 上下文；`UserSoulProfileDeriver` 只消费 `scope='user'` memory，但不要求 `category='user'`——也就是说 L1 也会被 summary 化。

### 1.3 与 Soul Profile 的关系

User Soul Profile 是**派生工件**（derived summary，SHA-256 canonical hashing + 24h shadow + Activator 激活），它从 L2 汲取原料但不是 L2 本身；L2 依然以记忆行形态存在，可独立被 Grounding 召回。晋升到 L2 的记忆 **应当** 成为 Soul Profile 的 `source_memory_pids` 候选，但反向不成立（Soul Profile archive/forget 不删 L2）。

---

## 2. 问题陈述

1. **L1 无界膨胀**：一次长 agent run 可能写 100+ 条 `session`，`ActiveMemoryService` 关键字召回会把大量短期噪音塞进 system prompt。已知线上 AuraBot 长对话在 400 轮之后 prompt 长度翻倍，L1 占比 >60%。
2. **缺少重要性 × 访问频度 × 时间衰减的综合评分**：现有 `promoteSessionMemories` 只看 `importance >= threshold` 一个维度，不考虑这条记忆到底被 Grounding 读取过几次、最近是否还在被访问。
3. **重复 embedding 成本**：同一个用户在不同 session 反复说同一件事（"我习惯用 markdown"），每次都落一条新 `session` 行并各自计算 1536-dim embedding，晋升后仍然各自成一条 L2 行，`UserSoulProfileDeriver` 投影时只能靠 `ProfileHasher` 事后兜底。
4. **降级路径缺失**：L2 记忆若长期不被访问，只会被 `MemoryDecayService` 按固定步长衰减到软删；没有"降回 L1 短期观察"的机制，一旦软删就再也无法召回。
5. **观测性空白**：晋升 / 降级 / 去重命中率全部没有 metric，Grafana 上只能看 `ab_agent_memory` 的总行数。

---

## 3. 设计目标

| 指标 | 目标值 | 衡量方式 |
|------|--------|----------|
| 单 agent run L1 条数上限（软限） | ≤ 200 行 | `count(*) WHERE category='session' AND source_run_id=?` |
| L1 晋升延迟 P95 | ≤ 24h from first write | `promoted_at − created_at` 分位 |
| L1 重复率（语义去重命中） | ≥ 30% 命中现有 L2 时合并 | `promotion_dedup_hit_total / promotion_candidate_total` |
| Grounding 召回中 L1 占比 | ≤ 20%（长度 ≥ 50 的会话） | 采样 `ActiveMemoryService.snippet` 返回 |
| 晋升后 Soul Profile 再派生的 `source_memory_pids` 覆盖率 | 同一 canonical 内容去重后 ≥ 80% 来自 L2 | `ProfileProjector` 采样 |
| 被降级（L2 → L1）的 L2 行占比 | < 5% / 月 | `demotion_total / l2_active_count` |

---

## 4. 晋升策略（核心）

### 4.1 触发条件

采用**混合触发**：事件触发做近实时、cron 做兜底。

1. **事件触发（近实时，秒级）**：
   - `AgentRun` 结束时 `RunLifecycleService.onComplete` 发一个 `SessionEndedEvent(tenantId, agentCode, runId)`。
   - `MemoryL1L2Promoter` 作为 `@EventListener(SessionEndedEvent.class)` 异步处理该 run 对应的 L1。
   - 权衡：为什么不每条写入都判？——避免写放大，单条 L1 往往还没被任何后续 action 读过，评分偏差大。
2. **Cron 兜底（每 6h）**：
   - 扫描所有 `category='session' AND created_at < NOW() - INTERVAL '6 hours'` 的孤儿 L1（run 异常退出、事件丢失的 case），走一遍同样的评分管道。
   - 权衡：为什么 6h 而不是 24h？——配合 User Soul Profile 每日 04:00 派生，确保 cron 最近一次晋升晚于或等于事件最后一次。
3. **Threshold-based（两者共用）**：
   - 记忆进入 candidate pool 的硬门槛 `base_importance >= 6`（现有 `importance` 默认 5）；**不改** base importance 语义。
   - 综合得分 `score(m) >= score_threshold`（默认 0.65）才真正写 L2。

### 4.2 评分公式

```
score(m) = w_imp  * norm(importance, 0..10)
        + w_acc  * norm(log1p(access_count), 0..log1p(20))
        + w_rec  * recency(m)
        + w_uni  * uniqueness(m)

recency(m)    = exp(-age_hours(m) / 72)          // 72h 半衰期
uniqueness(m) = 1 - max(cosine(m.embedding, L2.embedding))
                where L2 ∈ same (tenantId, scope, scope_key)
```

默认权重：

| 因子 | 权重 | 说明 |
|------|------|------|
| `w_imp` | 0.35 | importance 是人工/LLM 显式打的分，信号最强 |
| `w_acc` | 0.25 | 这条 L1 在 run 内被 Grounding 召回过几次——间接证明"真的有用" |
| `w_rec` | 0.15 | 新鲜度；防止把一周前的 L1 cron 兜底时误判为高价值 |
| `w_uni` | 0.25 | 与现有 L2 余弦距离；压制"我再说一次我喜欢 markdown" |

全部权重在 `acp.memory.l1l2.weights.*` 配置；开发阶段允许破坏性调整，不提供版本兼容迁移。

权衡：为什么不用 logistic regression 学权重？——当前没有晋升质量的 ground truth 标注，手工权重足够；如后续启动 active learning，ground truth 可以来自 "L2 行在 90 天窗口内的 access_count" 做目标变量，届时再迭代。

### 4.3 去重策略

两层：

1. **内容哈希（精确去重）**：
   - `content_hash = SHA-256(lowercase(trim(memory_content)))` 存在 `content_hash CHAR(64)`。
   - 晋升前 `SELECT pid FROM ab_agent_memory WHERE tenant_id=? AND scope=? AND scope_key=? AND content_hash=? AND category IN ('user','agent') AND (deleted_flag IS NULL OR deleted_flag=FALSE)`。
   - 命中即**不新建 L2**，将命中行的 `access_count += 1`、`importance = GREATEST(importance, source.importance)`，并记 `promotion_dedup_hit_total{mode='hash'}`。
2. **语义去重（embedding 余弦）**：
   - 当 hash 未命中但 `uniqueness(m) < 0.15`（即 cosine ≥ 0.85，与 memory-promotion 的 `cross_user_agreement` 阈值对齐），视为语义重复，合并策略同 hash 命中，`mode='cosine'`。
   - 权衡：为什么不对 cosine-hit 做更激进的文本合并（比如让 LLM 重写）？——写路径不能依赖 LLM，会引入不可控延迟 + 失败面；文本合并留给 User Soul Profile 的渲染路径。

### 4.4 降级路径

新增 `DEMOTED` 事件而非硬删：

- `MemoryL1L2Demoter`（cron 每日）扫描 `category IN ('user','agent')` 且满足：
  - `last_accessed < NOW() - INTERVAL '60 days'`
  - `access_count < 3`
  - `shareable = FALSE`
  - `importance <= 3`
- 命中行**改回** `category='session'`，写审计事件 `L2_DEMOTED`；下一个 decay 周期由 `MemoryDecayService` 按 session 更激进的衰减处理（步长可用独立配置 `acp.memory.decay.demoted-session.decrement=2`）。
- 权衡：为什么不直接软删？——保留一次被再次访问、重新走评分管道的机会；60d + access<3 的硬门槛保证不会震荡。降级事件写计数 metric，便于观察抖动。

---

## 5. 存储模型变更

**不另建表**，`ab_agent_memory` 加列。理由：Grounding / Soul Profile / MemoryDecay 都已按 category 过滤；新表会让所有消费者 double-read，违反"最简洁最正确"。

`ab_agent_memory` 新增字段（开发阶段一次性 schema 改动，不考虑迁移）：

```sql
ALTER TABLE ab_agent_memory
  ADD COLUMN content_hash   CHAR(64),
  ADD COLUMN promoted_at    TIMESTAMPTZ,
  ADD COLUMN promoted_from_run_id VARCHAR(26),
  ADD COLUMN score_snapshot JSONB,
  ADD COLUMN demoted_at     TIMESTAMPTZ,
  ADD COLUMN demotion_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_agent_memory_content_hash
  ON ab_agent_memory (tenant_id, scope, scope_key, content_hash)
  WHERE (deleted_flag IS NULL OR deleted_flag = FALSE);

CREATE INDEX idx_agent_memory_category_last_accessed
  ON ab_agent_memory (category, last_accessed)
  WHERE (deleted_flag IS NULL OR deleted_flag = FALSE);
```

`score_snapshot` 示例：
```json
{
  "score": 0.72,
  "factors": { "imp": 0.7, "acc": 0.4, "rec": 0.92, "uni": 0.85 },
  "weights_version": "v1",
  "computed_at": "2026-04-19T08:15:00Z"
}
```

**新增审计表**（用于晋升 / 降级事件回放）：

```sql
CREATE TABLE IF NOT EXISTS ab_agent_memory_tier_event (
  id             BIGSERIAL PRIMARY KEY,
  pid            VARCHAR(26) UNIQUE NOT NULL,
  tenant_id      BIGINT NOT NULL,
  memory_pid     VARCHAR(26) NOT NULL,        -- 目标 ab_agent_memory.pid
  event_type     VARCHAR(20) NOT NULL,         -- L1_PROMOTED / L2_DEMOTED / DEDUP_HIT
  dedup_mode     VARCHAR(10),                  -- hash / cosine / null
  merged_into_pid VARCHAR(26),                 -- dedup hit 时的目标 L2 pid
  score_snapshot JSONB,
  source_run_id  VARCHAR(26),
  created_at     TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
  created_by     BIGINT
);

CREATE INDEX idx_memory_tier_event_memory ON ab_agent_memory_tier_event (memory_pid, created_at DESC);
CREATE INDEX idx_memory_tier_event_tenant_type ON ab_agent_memory_tier_event (tenant_id, event_type, created_at DESC);
COMMENT ON TABLE ab_agent_memory_tier_event IS 'Audit trail for L1<->L2 tier transitions';
```

权衡：为什么不复用 `ab_agent_memory_promotion`（scope promotion 的审计表）？——语义不同（scope 晋升是 user→tenant 访问边界扩大，带人工 review gate；lifecycle 晋升是自动、无 review），混用字段会让两边都长出 nullable 字段，不如分表。

---

## 6. 调度与并发

所有调度默认关闭（`acp.memory.l1l2.*.enabled=false`），沿用 Soul Profile 的 `TransactionTemplate.execute` 固定 JDBC 连接 + PostgreSQL advisory lock 模式。

| Service | Cron | Lock Key | 职责 |
|---------|------|----------|------|
| `MemoryL1L2Promoter`（事件驱动） | — | — | `SessionEndedEvent` 异步消费，单 run 内幂等 |
| `MemoryL1L2OrphanPromoter` | `0 0 */6 * * *` | `7309` | 扫描孤儿 L1，兜底晋升 |
| `MemoryL1L2Demoter` | `0 45 3 * * *` | `7310` | 每日降级扫描 |
| `MemoryL1L2Scorer`（函数库） | — | — | 纯函数，无事务，供 Promoter / UI 预览共用 |

Advisory lock 号选择：已用 `7303..7308`（memory-promotion + soul-profile），本设计占 `7309 / 7310`。

关键并发约束：
- Promoter 与 `MemoryDecayService`（lock 未占用，普通 @Scheduled）可能同时读 `ab_agent_memory`；均只做 UPDATE，无 race 风险。
- 同一 memory_pid 在 Promoter 和 Demoter 间由 `category` 值自然互斥（事件触发时 `category='session'`；降级扫描时 `category IN ('user','agent')`）。
- Session 结束事件同一 `runId` 可能被重发（Spring retry、多实例）：Promoter 通过 `SELECT ... FOR UPDATE SKIP LOCKED` 锁 L1 行并在同一事务内 UPDATE，天然幂等。

Java 骨架示意：

```java
@Component
@RequiredArgsConstructor
public class MemoryL1L2Promoter {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MemoryL1L2Scorer scorer;
    private final MeterRegistry meter;

    @Async
    @EventListener(SessionEndedEvent.class)
    public void onSessionEnded(SessionEndedEvent evt) {
        tx.execute(status -> {
            jdbc.execute("SELECT pg_advisory_xact_lock(7309, ?)",
                new Object[]{ evt.tenantId() }, PreparedStatement::execute);

            List<MemoryRow> candidates = jdbc.query(
                "SELECT pid, memory_content, importance, access_count, last_accessed, "
              + "       created_at, embedding, scope, scope_key "
              + "  FROM ab_agent_memory "
              + " WHERE tenant_id = ? AND source_run_id = ? "
              + "   AND category = 'session' "
              + "   AND importance >= 6 "
              + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
              + "   FOR UPDATE SKIP LOCKED",
                MemoryRowMapper.INSTANCE, evt.tenantId(), evt.runId());

            for (MemoryRow m : candidates) {
                ScoreResult r = scorer.score(m);
                if (r.score() < 0.65) continue;

                DedupResult d = findDuplicate(evt.tenantId(), m);
                if (d.hit()) {
                    mergeInto(d.targetPid(), m);
                    audit(evt.tenantId(), m.pid(), "DEDUP_HIT", d.mode(), d.targetPid(), r);
                    meter.counter("auraboot_memory_l1l2_dedup_hit_total",
                        "tenant", evt.tenantId().toString(), "mode", d.mode()).increment();
                    continue;
                }

                promoteToL2(m, r);
                audit(evt.tenantId(), m.pid(), "L1_PROMOTED", null, null, r);
                meter.counter("auraboot_memory_l1l2_promotion_total",
                    "tenant", evt.tenantId().toString()).increment();
            }
            return null;
        });
    }
}
```

---

## 7. 与 User Soul Profile 的协作

晋升成功写 `category='user'` + `scope='user'` 的 L2 行，**直接落入 `UserSoulProfileDeriver` 既有查询范围**（`ab_agent_memory WHERE scope='user' AND created_at > NOW() - look_back_days`），无需显式 hook。

但存在一个**新关系**：

- Soul Profile 的 `source_memory_pids` 应只引用 `category='user'`（L2）行；**禁止**引用 `category='session'`（L1）行。当前 `UserSoulProfileDeriver` 未做此过滤，本设计落地时在 `Deriver` 的 WHERE 加 `AND category IN ('user','agent')`。
- 反向：Profile archive / forget 不级联到 L2（维持现状），`ab_agent_user_soul_profile` 的 tombstone 只屏蔽 Deriver 不动 L2 行。L2 的删除由 GDPR 级联（`DELETE FROM ab_agent_memory WHERE tenant_id=? AND scope='user' AND scope_key=?`）单独处理，已有。

分工：

| 问题 | L1→L2 晋升 | Soul Profile |
|------|-----------|---------------|
| 控制 prompt 长度 | 压制 L1 注入比例 | 输出 ≤500 字的 summary 块 |
| 去重 | 写路径精确 + 语义 dedup | canonical JSON + SHA-256（读侧快照） |
| 用户编辑 | 不支持（系统自动） | pin / hide / edit / reset / forget |
| 生命周期 | 自动 | 24h shadow + Activator |

---

## 8. 可观测性

沿用 Soul Profile 命名风格（`auraboot_<subsystem>_<event>_total|count`）。

**Counters**：
```
auraboot_memory_l1l2_candidate_total{tenant}
auraboot_memory_l1l2_promotion_total{tenant, trigger}
  trigger ∈ {session_event, orphan_cron}
auraboot_memory_l1l2_skipped_total{tenant, reason}
  reason ∈ {below_threshold, importance_guard, scope_missing}
auraboot_memory_l1l2_dedup_hit_total{tenant, mode}
  mode ∈ {hash, cosine}
auraboot_memory_l1l2_demotion_total{tenant}
auraboot_memory_l1l2_score_compute_failed_total{tenant, reason}
```

**Gauges**（跨租户聚合）：
```
auraboot_memory_l1_active_count        # category='session' 非删
auraboot_memory_l2_active_count        # category IN ('user','agent') 非删
auraboot_memory_l1_per_run_p95         # 最近 24h 单 run L1 计数 P95
auraboot_memory_l1l2_promotion_latency_seconds_bucket  # histogram
```

**Grafana dashboard**：新增 `docs/operations/grafana-memory-l1l2.json`（7 panels：候选率、晋升率、跳过原因 pie、dedup 命中率、L1/L2 存量、晋升延迟 P95、降级趋势）。

**告警**（`docs/operations/learning-loop-alerts.yaml` 新 group `auraboot.memory_l1l2`）：
- `MemoryL1ExplosionPerRun`：`auraboot_memory_l1_per_run_p95 > 300` 持续 30 分钟。
- `MemoryL1L2PromotionStalled`：`rate(auraboot_memory_l1l2_candidate_total[1h]) > 10` 而 `rate(..._promotion_total[1h]) == 0` 持续 2 小时。
- `MemoryL2DemotionSpike`：`rate(..._demotion_total[1d]) / l2_active_count > 0.05`。

---

## 9. Phase 划分

按现有 PR 编号规则延续，起点 PR-82。

| PR | Phase | 范围 |
|----|-------|------|
| PR-82 | 1 | Schema（`ab_agent_memory` 新列 + `ab_agent_memory_tier_event`）+ `MemoryL1L2Scorer`（纯函数）+ 单元测试（评分、去重哈希） |
| PR-83 | 2 | `MemoryL1L2Promoter`（事件监听）+ `SessionEndedEvent` 发射点（`RunLifecycleService.onComplete`）+ 集成测试（真实 PG + 真实 embedding） |
| PR-84 | 3 | `MemoryL1L2OrphanPromoter`（cron 7309）+ `MemoryL1L2Demoter`（cron 7310）+ 配置项 + 集成测试 |
| PR-85 | 4 | `ActiveMemoryService` / `AgentRunService.loadMemorySection` 调整：读路径对 L1 设硬上限（`acp.memory.l1l2.grounding.max-l1=30`），`UserSoulProfileDeriver` WHERE 加 `category IN ('user','agent')` |
| PR-86 | 5 | Metric + Grafana dashboard + Prometheus alerts + Mission Control 只读页（`/aurabot/memory-tier-events`）显示 tier_event 流 |
| PR-87 | 6 | 真后端 E2E：模拟一次长 session 写 50 条 L1，断言事件触发晋升；断言 dedup hit 合并 access_count；断言降级 cron 把 60d 未访问 L2 改回 session |
| PR-88 | 7 | 子系统参考文档 `docs/core-concepts/memory-l1-l2-promotion.md` + 首周观察清单 + 调优手册 |

每个 Phase 的验收基线统一包含："集成测试通过 + Grafana 面板能看到对应指标 + Controller review E2E"。

### 9.1 Phase 2 实现备注（PR-83，已交付）

- `SessionEndedEvent` + `MemoryL1L2Promoter` 落地；已接入 `AgentRunService.completeRun` 在 `saveRunMemory` 之后发射事件（位置比设计 §4.1 建议的 `RunLifecycleService.onComplete` 更下游 —— 保证 run 内最后一条 memory 先入库再晋升）。
- 监听器目前为**同步** `@EventListener` + `@Transactional`（非 `REQUIRES_NEW`，遵守红线“禁止 REQUIRES_NEW 绕 rollback-only”）。`@Async` 延后到 Phase 3 线程池调优时再加。
- 去重：Phase 2 仅实现**hash dedup**（design §4.3 第一层），embedding 缺失时 `maxCosineToL2 = 0.0`（design §10 问题 4 方案 a）。**语义 cosine dedup 未实现**，与 design §4.3 第二层相比是缺口，Phase 3 随 cron orphan promoter 一同补。
- **Phase 3 backlog（PR-84 范围）**：
  1. `MemoryL1L2OrphanPromoter` cron 7309：处理事件丢失 / 同步 listener 异常造成的"孤儿 L1"（`category='session' AND created_at < NOW() - INTERVAL '6 hours'`）。
  2. 语义 cosine dedup（hash 未命中但 cosine ≥ 0.85 时合并）。
  3. `MemoryL1L2Demoter` cron 7310（design §4.4）。
  4. 多实例部署加 `advisory_xact_lock(7309, tenantId)` + `FOR UPDATE SKIP LOCKED`（design §6 草案骨架），当前同步 listener 在单节点部署下无需。

---

## 10. 开放问题

1. **L1 的 access_count 谁来累加？**
   当前 `ActiveMemoryService` 召回不回写 `access_count`；`MemoryAccessRecorder` 是否需要扩展到 L1？如果不扩，`w_acc` 因子永远是 0，公式退化。**建议**：Phase 2 同时补写访问记录器，但可能影响 grounding 延迟——需要基准测试。
2. **`category='agent'` 怎么产生？**
   当前代码只见消费，没见写路径。本设计假设 agent 级别的 L2 由管理员手工 seed 或由未来的 `AgentDefinition.memories` 写入；若要求 L1 也能晋升到 `agent`，需要额外规则（譬如 `shareable=TRUE` 且 `scope='global'`）。**建议**：本阶段只做 L1 → user，不触碰 agent 级别。
3. **降级阈值 60d / access<3 是否过紧？**
   没有线上数据支撑。**建议**：Phase 3 默认关闭 Demoter，收集 2 周 gauge 后再决定。
4. **embedding 缺失的 L1 怎么参与语义去重？**
   目前 embedding 是异步计算（Provider 不可用时 NULL）。Promoter 遇到 NULL embedding 时应该：a) 跳过语义 dedup 只做 hash dedup，或 b) 同步触发一次 embedding 计算？**建议**：a）避免写路径引入 LLM 调用。
5. **多实例部署下事件去重**
   Spring `@EventListener` 在多节点下每节点各自消费；Promoter 用 `advisory_xact_lock(7309, tenantId)` + `FOR UPDATE SKIP LOCKED` 保护，但**对同一个 runId** 而言不同节点仍会竞争。需要确认 `RunLifecycleService.onComplete` 的发射是否单节点（如走 leader-only cron），否则 Promoter 内部还要加 `seen_run_ids` 去重缓存。**待用户决定**部署拓扑。
6. **是否允许用户/管理员手动晋升 L1？**
   Soul Profile 有 `/derive-now`，memory-promotion 有 review gate。本设计不引入 UI 手工晋升，理由是 L1 晋升本身是自动化低价值操作；但如果运维需要"把某条重要 session 一键固化"，需要一个 `POST /api/admin/memory/{pid}/promote-now` 端点。**待用户决定**必要性。

---

## 附：相关文件速查

- `platform/src/main/java/com/auraboot/framework/agent/service/AgentMemoryConsolidationService.java` — 已有的手工 `promoteSessionMemories`，本设计将其重构为 `MemoryL1L2Scorer + Promoter` 的一部分
- `platform/src/main/java/com/auraboot/framework/agent/service/MemoryDecayService.java` — 现有 decay，本设计在其读路径加 `category='session' OR demoted_at IS NOT NULL` 的细粒度衰减
- `platform/src/main/java/com/auraboot/framework/agent/service/ActiveMemoryService.java` — 读路径，需要对 L1 注入加上限
- `platform/src/main/java/com/auraboot/framework/agent/service/UserSoulProfileDeriver.java` — WHERE 过滤需补 `category`
- `platform/src/main/resources/database/schema.sql:4908` — 表定义修改点

# Memory Tier Promotion — Subsystem Reference

**Status**: Phases 1-3 shipped (PR-82..PR-84). Phase 4+ (L1 read-cap / Grafana / real E2E / multi-instance / admin API) pending.
**Design**: [2026-04-19 L1→L2 promotion design](../plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md)
**Peer subsystem**: [User Soul Profile](./user-soul-profile.md) — 派生 summary，消费本子系统产出的 L2 行。

自动化的 agent 记忆生命周期管理：把短期 session 记忆（L1）按"重要性 × 访问频度 × 新鲜度 × 唯一性"综合打分后，选择性晋升到长期记忆（L2），并对长期不被访问的 L2 反向降级回 L1 以免无限膨胀。与 `memory-promotion.md` 描述的 **scope 晋升**（user → tenant 访问边界扩大）是正交维度，本子系统只处理 **lifecycle tier**。

## 术语

| 概念 | 取值 | 物理存储 | 语义 |
|------|------|----------|------|
| **Lifecycle Tier**（本子系统） | `L1` / `L2` | `ab_agent_memory.category` | 这条记忆活多久 |
| **L1 = Working Memory** | `category='session'` | 同表 | 单次 agent run 内写入，受 `MemoryDecayService` 线性衰减，降到 `importance=0` 即软删 |
| **L2 = Long-Term Memory** | `category ∈ {user, agent}` | 同表 | 晋升后的持久记忆，受 decay 保护；`shareable=TRUE` 永不衰减 |
| **Tier Event** | `L1_PROMOTED` / `L2_DEMOTED` / `DEDUP_HIT` | `ab_agent_memory_tier_event` | 审计行，一次 tier 变更写一条 |
| **Promotion Score** | `[0.0, 1.0]` | `ab_agent_memory.score_snapshot JSONB` | 晋升决策依据，默认阈值 `0.65` |

Tier 维度 vs. Scope 维度对照：

| 维度 | 字段 | 取值 | 谁改它 |
|------|------|------|--------|
| `AgentMemoryScope` | `scope` + `scope_key` | `user` / `tenant` / `global` | 写入时决定；由 memory-promotion 子系统扩大 |
| `MemoryTier`（本文） | `category` | `session`（L1）/ `user`\|`agent`（L2） | 写入时为 `session`；由本子系统自动翻转 |

**关键不变量**：tier 就是 `category`，不另建字段；晋升 = `category` 翻 `session → user`；降级 = 翻 `user|agent → session`。两个信号源头单一，遵守红线"禁止 dual source of truth"。

## 生命周期

```
  ┌─────────────────────────────────────────────────────┐
  │                                                     │
  │            category = 'session'  (L1)               │
  │                                                     │
  │   每轮 agent run 写入 · importance-based decay      │
  │                                                     │
  └──────────────┬──────────────────────┬───────────────┘
                 │                      ▲
        SessionEndedEvent               │
        (同步 listener)                 │
        + score ≥ 0.65                  │ 90d 未访问
        + 非 dedup hit                  │ + importance < 3
                 │                      │ + shareable=FALSE
                 ▼                      │
  ┌─────────────────────────────────────┴───────────────┐
  │                                                     │
  │        category ∈ {'user', 'agent'}  (L2)           │
  │                                                     │
  │   受 MemoryDecayService 保护 · 可被 Grounding 召回  │
  │                                                     │
  └─────────────────────────────────────────────────────┘
```

Dedup hit（hash 或 cosine）不新建 L2 行；把命中的现有 L2 行 `access_count += 1`、`importance = GREATEST(existing, candidate)`，并写一条 `DEDUP_HIT` 审计行。候选 L1 本身维持 `category='session'`，等待下一次 decay 自然清理。

## Derivation（评分公式）

纯函数 [`MemoryTierEvaluator`](../../platform/src/main/java/com/auraboot/framework/agent/memory/MemoryTierEvaluator.java)，无 IO、无状态，供事件 Promoter 与 orphan cron 共用：

```
score(m) = w_imp * norm(importance, 0..10)
        + w_acc * norm(log1p(access_count), 0..log1p(20))
        + w_rec * exp(-age_hours / 72)            // 72h 半衰期
        + w_uni * (1 - maxCosineToL2)
```

默认权重（`weights_version="v1"`）与基本参数：

| 因子 | 权重 | 为什么 |
|------|------|--------|
| `w_imp` | 0.35 | importance 是人工 / LLM 显式打的分，信号最强 |
| `w_acc` | 0.25 | 证明这条 L1 在 run 内真被 Grounding 读过 |
| `w_rec` | 0.15 | 压制 orphan cron 把老旧 L1 误判为高价值 |
| `w_uni` | 0.25 | 压制"我再说一次我喜欢 markdown" |
| **阈值** | **0.65** | 低于此分不晋升且不写 L2 |
| **硬门槛** | `importance >= 6` | 候选根本不进入评分管道 |

Evaluator 对越界输入 **直接抛 `IllegalArgumentException`**，不做 clamp / fallback。权重、阈值通过 `acp.memory.l1l2.*` 配置注入；开发阶段允许破坏性调整，不保留 v0。

## 两层去重

去重**先于**分数阈值门（否则语义重复行的 `uniqueness = 0` 会被低分 gate 拦下，永远走不到合并路径——这是 Phase 3 的一个关键顺序修正）：

| 层 | 触发 | 阈值 | 实现 | 审计 |
|----|------|------|------|------|
| **Layer 1 — Hash** | always | `SHA-256(lowercase(trim(content)))` 精确相等 | Phase 2 落地 | `dedup_mode='hash'` |
| **Layer 2 — Semantic Cosine** | hash 未命中且 embedding 存在 | `cosine ≥ 0.92`（配置 `acp.memory.l1l2.semantic-dedup-threshold`） | Phase 3 落地，pgvector `<=>` 直查 | `dedup_mode='cosine'` |

Embedding 缺失（`MemoryEmbeddingService.resolveEmbedding` 返回 `null`，provider 未配置）时优雅 **跳过 Layer 2** 回退到打分路径，不 throw、也不"自愈式触发 embedding 计算"——写路径严格禁止同步 LLM 调用。Layer 2 命中不新建 L2；合并到最近的那一条并写 `DEDUP_HIT` 审计行。

阈值 `0.92` 比设计稿 `0.85` 更保守，理由：写路径上合并等于"承认这两条是同一记忆"，假阳性代价（丢语义）大于假阴性代价（多存一条）。

## 调度与并发

所有调度默认关闭（`acp.memory.l1l2.*.enabled=false`），沿用 Soul Profile 的 `TransactionTemplate.execute` 固定 JDBC 连接 + PostgreSQL advisory lock 模式。

| 组件 | Cron | Lock Key | 角色 |
|------|------|----------|------|
| [`MemoryL1L2Promoter`](../../platform/src/main/java/com/auraboot/framework/agent/memory/MemoryL1L2Promoter.java) | — | — | 同步 `@EventListener(SessionEndedEvent)`；单 run 内幂等 |
| [`MemoryL1L2OrphanScanner`](../../platform/src/main/java/com/auraboot/framework/agent/memory/MemoryL1L2OrphanScanner.java) | `0 */15 * * * *` | `7311` | 每 15 分钟兜底扫描事件丢失的 L1（age ≥ 1h） |
| [`MemoryL1L2Demoter`](../../platform/src/main/java/com/auraboot/framework/agent/memory/MemoryL1L2Demoter.java) | `0 0 3 * * *` | `7312` | 每日 03:00 扫描长期未访问的 L2 并降级 |

并发约束：

- 同一 `memory_pid` 在 Promoter 与 Demoter 之间由 `category` 取值自然互斥（前者看 `session`，后者看 `user|agent`），UPDATE WHERE 带 `AND category = ...` 形成乐观并发控制；race 丢失方被计入 `skipped_dup` 或 `demote_skipped`。
- 同一 `runId` 可能重发（Spring retry、多实例），Promoter 依赖 UPDATE 的 WHERE `category='session'` 保护：第二次执行命中 0 行，不写重复审计。
- Cron 均用 `pg_try_advisory_lock` 抢锁，抢不到即该 tick no-op；抢到后走 `BATCH_CAP=500` 上限，防止 lock 长期占用。

设计稿原计划的 `7309/7310` 被其他 subsystem 先占；PR-84 brief 改为 `7311/7312`。

## 事件路径

```
AgentRunService.completeRun
        │
        ├─ saveRunMemory(...)        ← L1 行落库（category='session'）
        │
        └─ eventPublisher.publish(new SessionEndedEvent(tenantId, runId, agentCode, userId))
                │
                └─ MemoryL1L2Promoter.onSessionEnded  (@EventListener, @Transactional 同步)
                        └─ 对 (tenantId, runId) 范围内 importance ≥ 6 的 L1
                           逐条走 hash-dedup → semantic-dedup → score → promote 管道
```

**为什么同步 + 非 `@Async`**：Phase 2 刻意不加 `@Async`，原因有三：

1. 确定性：集成测试可以在同一个线程里观察结果，无需 await executor。
2. 红线合规：`@Async` 会让事件处理脱离发布者事务，一旦需要回滚会引入"REQUIRES_NEW 绕 rollback-only"反模式。
3. 崩溃兜底：JVM 在 `saveRunMemory` 和 listener 之间崩，那批 L1 会变成"孤儿"（`category='session' AND promoted_at IS NULL`），**由 orphan cron 负责捡回**——不依赖 listener 必然成功。

事件发布点在 `completeRun` 中 `saveRunMemory` **之后**，而不是设计稿建议的 `RunLifecycleService.onComplete`，理由是保证 run 内最后一条 memory 已入库再评估。

## Demoter（L2 → L1）

`MemoryL1L2Demoter` 降级规则（design §4.4，Phase 3 落地）：

```sql
WHERE category IN ('user','agent')
  AND (deleted_flag IS NULL OR deleted_flag = FALSE)
  AND (shareable IS NULL OR shareable = FALSE)     -- pinned 行不动
  AND importance < 3                                -- 低价值才候选
  AND (last_accessed IS NULL OR last_accessed < NOW() - INTERVAL '90 days')
```

命中后原子 UPDATE：`category = 'session'`，`demoted_at = NOW()`，`demotion_count += 1`，写 `L2_DEMOTED` 审计行。降级后由 `MemoryDecayService` 正常按 session 衰减；若在后续 session 里再被命中，会重新走 Promoter 管道，有机会**再次**升级（`demotion_count` 记录历次被降级的次数，用于观测抖动）。

**降级不硬删** 的理由：保留一次被再次访问、重新走评分管道的机会；90d + importance<3 + access NULL-or-stale 三重门槛保证不会震荡。

配置 knobs：`acp.memory.l1l2.demoter.age-days`（默认 90）、`acp.memory.l1l2.demoter.importance-max`（默认 3）、`acp.memory.l1l2.demoter.cron`（默认 `0 0 3 * * *`）。

## REST / 管理面

**本子系统不提供任何公开 REST endpoint**（截至 Phase 3）。运维与用户界面均在 Phase 4 backlog：

- `POST /api/admin/memory/{pid}/promote-now`（一键固化）— Phase 4+
- `GET /api/admin/memory-tier-events`（审计流）— Phase 4+
- Mission Control `/aurabot/memory-tier-events` 只读页 — Phase 4+

当前路径：**schedulers + event** 自包含运行。运维介入唯一手段是切换 feature flag 或运行 `*.runOnce()` via 测试 / 调试入口。

## Metrics

全部 Micrometer counter，cardinality 为 O(tenants × small-enum) Prometheus-safe。常量定义在 [`MemoryL1L2PromotionMetrics`](../../platform/src/main/java/com/auraboot/framework/agent/metrics/MemoryL1L2PromotionMetrics.java)。

```
auraboot_memory_tier_promotion_total{tenant, outcome}
  outcome ∈ { promoted, skipped_low_score, skipped_dup,
              skipped_dup_semantic, failed }

auraboot_memory_tier_demotion_total{tenant, outcome}
  outcome ∈ { demoted, skipped }           # skipped = 乐观并发丢失（已被 promoter 改走）

auraboot_memory_tier_event_total{tenant, event_type}
  event_type ∈ { L1_PROMOTED, DEDUP_HIT, L2_DEMOTED }
```

Failure counter (`outcome='failed'`) 在 RuntimeException 被**重抛前** 递增，保证告警看得到；事务随后回滚，不会留下不一致的晋升行。

## Operations

### Feature flags（全部默认 `false`）

| Property | 作用 |
|----------|------|
| `acp.memory.l1l2.orphan-scan.enabled` | 打开 orphan cron |
| `acp.memory.l1l2.demoter.enabled` | 打开 demoter cron |

事件 listener 没有 flag —— 一旦发布者开始发事件（`AgentRunService.completeRun` 无条件发布），listener 就会处理。若要完全关停晋升路径，需要阻止事件发布或在 Promoter 前加 enable gate（Phase 4 backlog）。

### Tuning knobs

| Property | 默认 | 何时调 |
|----------|------|--------|
| `acp.memory.l1l2.semantic-dedup-threshold` | `0.92` | 收紧到 0.95 减少假阳性；放宽到 0.85 提升合并率 |
| `acp.memory.l1l2.demoter.age-days` | `90` | 数据积压快时缩短到 60；保守租户延长到 120 |
| `acp.memory.l1l2.demoter.importance-max` | `3` | 观察到高 importance 也堆积时上调 |
| `acp.memory.l1l2.demoter.cron` | `0 0 3 * * *` | 避开业务高峰 |
| `acp.memory.l1l2.orphan-scan.cron` | `0 */15 * * * *` | 延长到每小时降低扫描成本，代价是孤儿 L1 延迟晋升 |
| `acp.memory.l1l2.weights.*` | 见上表 | 权重策略迭代；开发阶段直接覆盖，不保留 `v0` |

### 首周观察清单

- **晋升率突增**：`rate(..._promotion_total{outcome='promoted'}[5m])` 持续异常——权重/阈值过松。
- **Demoter 过杀**：`rate(..._demotion_total{outcome='demoted'}[1d]) / l2_active_count > 5%`——`importance-max=3` 太高或 `age-days=90` 太短。
- **审计表增长**：`ab_agent_memory_tier_event` 月增 > L1 总写入的 30%——dedup 管道失灵（hash 哈希函数变更 / embedding provider 全挂）。
- **`outcome='failed'` 非零**：必有未捕获的业务异常，立刻看日志。

## Known limitations

1. **无 L1 读上限**：`ActiveMemoryService` 对 L1 召回没有硬上限（设计稿 §4 的 `max-l1=30` 是 Phase 4 backlog）。长会话仍可能让 L1 压垮 prompt——本子系统控制的是**存量**，不是**召回量**。
2. **同步 listener 导致冷启动丢事件**：JVM 在 `saveRunMemory` 与 listener 返回之间崩溃，那批 L1 是孤儿；orphan cron 在 ≤ 15 分钟 + 1h age 门槛后捡回，但期间这些记忆无法参与 Grounding。
3. **Demoter 不复活**：pinned / high-importance / 近期访问的 L2 永不降级——这是**设计意图**而非限制。管理员想强制降级任一 L2 必须手工 SQL（Phase 4+ 考虑开放 admin API）。
4. **多实例部署**：advisory lock 在 DB 层天然跨实例，但 Phase 2 的**同步 listener** 在多实例下会每节点各自消费事件。`ab_agent_memory` 的 `category` 翻转 UPDATE 带 WHERE 保护，第二个节点命中 0 行并走 `skipped_dup`，天然幂等——但每节点都写一次 `DEDUP_HIT` 审计行会造成重复计数（Phase 4 考虑 `seen_run_ids` 缓存或 leader-only 消费）。
5. **无 Grafana dashboard / 告警**：PrometheusCounters 已发射但面板未建（设计稿 §8 的 7 panels 是 Phase 4 backlog）。

## 租户与隐私边界

- L1 → L2 晋升 **不跨租户**：Promoter SQL 条件始终带 `WHERE tenant_id = ?`，dedup 查找 partition 同样以 `(tenant_id, scope, scope_key)` 为 key。
- Advisory lock 抢占粒度：orphan cron / demoter 用全局 lock key（`7311` / `7312`），但扫描 SQL 按 tenant_id 分组；不存在跨租户信息泄漏——仅节省单实例并发度。
- `SessionEndedEvent` 把 `tenantId` 作为强校验字段（`Objects.requireNonNull`），禁止空值发布，保证所有下游审计 / metric 维度都带租户标签。
- Tier event 表 `ab_agent_memory_tier_event.tenant_id` NOT NULL + `idx_memory_tier_event_tenant_type`，审计查询强制按租户过滤。
- 与 Soul Profile 的协作：晋升后的 L2 行 `scope='user'` 会自动进入 `UserSoulProfileDeriver` 的 `source_memory_pids` 候选——两个子系统共享 `ab_agent_memory` 的行级 tenant_id，隔离由同一层保证。

## Related PRs

| PR | SHA | Phase | 范围 |
|----|-----|-------|------|
| PR-82 | `b427220c` | 1 | Schema（`ab_agent_memory` 新列 + `ab_agent_memory_tier_event`）+ `MemoryTier` 枚举 + `MemoryTierEvaluator` + 9 集成测试 |
| PR-83 | `cf1be2ca` | 2 | `SessionEndedEvent` + `MemoryL1L2Promoter` 同步 listener + `AgentRunService.completeRun` 发布点 + hash dedup + 6 集成测试 |
| PR-84 | `d0aa2296` | 3 | `MemoryL1L2OrphanScanner`（cron 7311）+ semantic cosine dedup（阈值 0.92）+ `MemoryL1L2Demoter`（cron 7312）+ 9 新增集成测试（共 24 green） |

## Related docs + dashboards

- 设计与 Phase checklist：[`docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md`](../plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md)（§9.1/§9.1a/§9.2 反映已交付状态）
- 对等子系统：[`user-soul-profile.md`](./user-soul-profile.md)（消费本子系统产出的 L2，派生 ≤ 500 字 summary）
- 正交维度：[`memory-promotion.md`](./memory-promotion.md)（scope 晋升 user → tenant，与 lifecycle tier 互不干涉）
- 关联原则：[`learning-loop.md`](./learning-loop.md)（action → skill 晋升；本子系统的 L2 是其信号源之一）
- Grafana dashboard：**待建**（Phase 4 backlog `docs/operations/grafana-memory-l1l2.json`）
- Prometheus alerts：**待建**（Phase 4 backlog，`auraboot.memory_l1l2` group）

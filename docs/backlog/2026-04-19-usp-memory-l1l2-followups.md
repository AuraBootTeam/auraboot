# USP / Memory L1L2 / Admin Guard — 后续 backlog（2026-04-19）

本文件记录 2026-04-19 session 中**没有随 main 合并落地**的遗留项。每项给出 what / why / 建议 owner 或触发条件，避免散落在 memory 或提交说明里。

相关子系统文档：
- [`docs/core-concepts/user-soul-profile.md`](../core-concepts/user-soul-profile.md)
- [`docs/core-concepts/memory-tier-promotion.md`](../core-concepts/memory-tier-promotion.md)
- [`docs/plans/2026-04/2026-04-19-platform-admin-guard-design.md`](../plans/2026-04/2026-04-19-platform-admin-guard-design.md)

---

## 1. USP real spec `--workers>1` 验证

**What**：`TestUserSpoofFilter`（`X-Test-Spoof-User-Id` header，test profile 限定）已于 `b84803fb` 合并，设计目的是让 USP real spec 支持 `--workers=4 --repeat-each=3` 稳定通过。

**Why 未完成**：当前 worktree 的 OSS bootstrap 状态不满足（reset-and-init 未跑完），跑 parallel 回归需等待干净环境；设计 claim 是"33+ passes × 4 workers"，未经实测验证。

**建议触发**：下一次 CI 全量 / 人手跑 `oss-reset-and-init.sh` 后，执行

```
LOG=/tmp/pw-usp-parallel-$(date +%Y%m%d-%H%M%S).log
NO_PROXY=localhost npx playwright test tests/e2e/user-soul-profile/real-parallel.spec.ts \
  --workers=4 --repeat-each=3 2>&1 | tee "$LOG"
```

通过标准：33 个 unique pid 无重叠 `uq_user_soul_profile_active` 冲突。

---

## 2. `PLATFORM_ADMIN` 角色

**What**：`InfrastructureController` / `EnvironmentController` / `CloudConfigController` 操作的是**跨租户**资源，但当前仅由 `TENANT_ADMIN` 拦截器守护（设计 §7 "open question" 已留痕）。

**Why**：Plan C HandlerInterceptor 先统一 `tenant_admin` 是明确权衡，目的是"零漏网"；跨租户控制器的收紧是 **增量工作**。

**建议 owner**：Platform 组下一个迭代；引入 `platform_admin` 角色 + `adminPathScopes` 配置，路径级白名单决定 required role。

---

## 3. ~~`TimezoneMigrationController` 生命周期~~ (DONE — renamed to `TenantTimezoneController`)

**Resolution (PR-B)**：实际调查显示该 controller 操作的是 `TenantPreference`（单租户配置），并非"平台 ops 一次性迁移"工具。已重命名为 `TenantTimezoneController`，路径从 `/api/admin/timezone` 改为 `/api/admin/tenants/timezone`，保留 `tenant_admin` gate。

---

## 4. Caffeine 缓存 Admin role lookup

**What**：`AdminRoleInterceptor` 目前每个 `/api/admin/**` 请求都查一次 `ab_user_role × ab_role × ab_tenant_member`。Plan C 设计留了缓存占位，但未实现。

**Why 延后**：admin 流量本身低，实测前收益未知。Phase 4 优先落拦截器本体，缓存被刻意推到 follow-up。

**建议**：`(userId, tenantId, role) → boolean` 60s TTL Caffeine cache；缓存 miss 走现行 JDBC 路径。改动窄、风险低，任何 Plan C 审阅者都能完成。

---

## 5. 通用 admin 访问审计表

**What**：USP 已有 `ab_agent_user_soul_profile_admin_action`（list/stats/forget 三条路径都写）。**其他 8 个 admin controller 没有对等的 paper trail**。

**Why**：GDPR 只强制"取得个人数据时"审计，USP 是唯一直接返回用户画像的 admin；但 Infrastructure / CloudConfig 的操作性访问也值得留痕——尤其是未来外部审计来查。

**建议 owner**：Platform 组；最小方案是新增 `ab_admin_action_log(tenant_id, actor_user_id, path, method, status, created_at)` + interceptor 旁路写一行。不阻塞功能，属于 ops 可观测性增强。

---

## 6. Admin promote-now UI

**What**：`POST /api/admin/memory/{pid}/promote-now` 已上线且拦截 `tenant_admin`，Grafana dashboard 和 counter 也打通，但 Mission Control 没有对应按钮。

**Why**：ops 一键固化本就是低频诊断工具，优先级低。

**建议触发**：`ab_agent_memory_tier_event` 审计页（Phase 5 backlog）一起做时，把 "promote now" 按钮挂在 L1 行上。

---

## 7. `GET /api/user/soul-profile/export` 无审计

**What**：用户导出自己的 profile JSON dump 时，后端不写审计行。

**Why 有争议**：用户读自己的数据，GDPR 未强制；但多数企业合规场景会要求保留 **exported_at** 时间戳以证明用户曾取得自己的数据。

**建议**：加一行 `admin_action_type='user_export'` 到 `ab_agent_user_soul_profile_admin_action`（或专用 `user_action_log`），成本 < 20 行代码；是否上线由合规团队决定。

---

## 8. Gradle wrapper jar 在 worktree 里缺失

**What**：多个 subagent 报告 worktree 检出时 `platform/gradle/wrapper/gradle-wrapper.jar` 不存在，说明它被 `.gitignore` 过滤了。

**Why**：worktree 执行 `./gradlew` 必须带 jar；每次手动 `cp` from 主仓库容易漏。

**建议**：追加到 `.gitattributes` 强制 binary 跟踪，或者确认 `.gitignore` 中 jar 的排除规则没误伤 `gradle-wrapper.jar`。任何 DevEx 维护者可做。

---

## 9. L1L2 Memory Tier 前端 Admin UI

**What**：目前 L1/L2 计数、降级审计、admin promote action 只能在 Grafana 看；Mission Control 没有对应 dashboard / tab。

**Why 延后**：Phase 4 明确范围是 Grafana + alerts + API；UI 属于 Phase 5+。

**建议触发**：当有租户实际启用 L1L2 schedulers、需要日常诊断时再做；否则只服务于内部测试。

---

## 10. L2 reader clamp

**What**：`AgentMemoryService.loadScopedByImportanceL2Only` 目前 top-50 硬编码，无 per-tenant cap 配置。

**Why**：当前租户 L2 规模 < 1000 行；50 上限足够。未来某租户积累几万条 L2 时，`UserSoulProfileDeriver` 的 projection 输入可能突然膨胀。

**建议**：引入 `acp.memory.l1l2.reader.l2-cap-per-tenant`（默认 50），在 reader 层按 tenant 分片限流。当任一租户 L2 active count > 5000 时触发此工作。

---

## 11. L1L2 Phase 5 生产验证

**What**：Phase 4 交付了 alert + dashboard + admin API，但**还没有真实租户负载观测过**。Memory tier 子系统现仅在集成测试 + fake E2E 下跑过。

**Why**：需要一个愿意把 `acp.memory.l1l2.*.enabled=true` 开到 staging 的租户样本。

**建议**：当第一家试点租户启用后跑 7 天观察期，重点关注：
- `skipped_dup_semantic` 占比（衡量 cosine 阈值是否过松/紧）
- Demoter 下杀率（若超过 l2_active_count 5% / day 要收紧）
- `outcome='failed'` 非零（必定业务 bug）
- Orphan backlog（orphan-scan 15min cron 是否追得上 event loss 速度）

---

## 归类说明

本文件不做 timeline/roadmap（红线），仅当**观测到 trigger** 或**收到 owner 承接信号**时推进。每一项都**可以独立上线**，没有锁死的顺序。

# 2026-05-07 4-Slice Review Session Followups

会话目标:对 2026-04-29 全平台 19 模块 audit 之后的 8 天 / 139 commit 新代码做 diff-driven review,4 个并行 subagent 各审一片。

**汇总**:24 finding(P0 = 0 / P1 = 13 / P2 ≈ 8 / P3 = 3)。完整原始报告位于 session-local `/tmp/review-2026-05-07-slice{1..4}-*.md`,**未持久化**(若需归档请人工 cp 到此目录)。

## ✅ 已闭合上线

| ID | 描述 | Commit / 来源 |
|---|---|---|
| Slice 3 P1-1 | `MetaModelMapper.updateForPluginImport` 三个 semantic 字段加 COALESCE(防重导入静默清空) | `85f3b72b` on main |
| Slice 1 P1-1 | `PromotionServiceImpl.create` 校验 source/target env_id 属当前 tenant | `e9e194ff` on main |
| Slice 1 P1-3 | `EnvironmentResolverInterceptor` 注册顺序提前到 PermissionInterceptor 之前 | `e74525ad` on main |
| Slice 4 R-1 (GAP-311) | AgentReplyTask post-runTurn `ImMessageBroadcaster.publish(MESSAGE)` | `3c53d327` on main(via `feat/conv-turn-svc-followup` PR merge,review 时该分支未在 main) |

## ⏸ Stack 在未 merge 父分支上,等父合 main 后处理

| ID | 描述 | 当前分支 / SHA | 父分支 |
|---|---|---|---|
| Slice 2 P1-2 | `AdminAuditService.actor_user_id` NOT-NULL 契约对齐(删 null 分支 + Javadoc 改 + `Objects.requireNonNull`) | `fix/admin-audit-actor-user-id-contract @ 690e5d31` | `feat/admin-guard-v2`(PR #45)未 merge |

**触发**:`feat/admin-guard-v2` 合 main 后,cherry-pick `690e5d31` 到 main 直推。

## ⏸ Slice 2 — Admin Guard v2 audit trail completeness(P1 batch,~6.5h)

`feat/admin-guard-v2` merge 之后的 followup,建议打包为单 PR `feat(admin-guard): audit trail completeness pass`:

| # | 描述 | 工时 |
|---|---|---|
| S2-1 | `ab_admin_action_log` schema 加 `remote_ip` / `user_agent` / `trace_id` 三列;interceptor 写入。**schema 是新表,现在加几乎免费,后面要 migration** | 2.5h |
| S2-3 | `RequestBodySummarizer` 支持 `application/x-www-form-urlencoded` 与 `multipart/form-data`(只记 keys,values redact) | 1.5h |
| S2-4 | `AdminAuditService.@Async` `DiscardPolicy` 静默丢日志 → 注册 `RejectedExecutionHandler` + Micrometer counter `aura.admin.audit.dropped`,或换 `CallerRunsPolicy` | 1h |
| S2-5 | `AdminRoleChecker` Caffeine 60s cache 无 invalidation hook → 在 `UserRoleService` role assign/revoke 处调 `cache.invalidate` | 1.5h |
| S2-6 | `resp.getStatus()=200` 无法区分 ApiResponse 业务错误码 → 用 `ContentCachingResponseWrapper` 抓 body.code 写 `business_status` 列 | 1.5h |

S2-2 已修(见上,等父分支)。

## ⏸ Slice 4 — Conv-turn / ACP Replay UI(剩余 P1+P2)

| ID | 描述 | 优先级 | 工时 |
|---|---|---|---|
| R-2 | 群聊 char-by-char streaming UX 在 commit `d5093130` body 第 30 行夹带降级,**未经显式签字** — 决策项:接受降级写 changelog,或新增 `STREAM_CHUNK` frame type 恢复 | P1(决策) | 决策 + 实施 |
| R-3 | `aurabot.run.read` permission 与 `TENANT_ADMIN` role 前后端不一致(菜单可见但后端 403) | P2 | 0.5h |
| R-4 | Replay UI admin GET 落 `ab_admin_action_log` 审计 + JSONB 内容脱敏(prompt/PII) | P2 | 2h |
| R-5 | `AgentReplyTask:L134` pre-runTurn `TYPING_INDICATOR` 收进 `ResponseSink.onStart()` hook,消除 chat impl 内手写 frame 边缘破例 | P2 | 1.5h |
| R-6 | `caller_overrides_used` metric 接 dashboard / alert(否则 sunset 判定无人读) | P3 | 0.5h |
| R-7 | `AgentRunController.list` `intent_summary` correlated subquery → `LATERAL JOIN`(commit message 与代码不符,顺手修) | P3 | 0.5h |
| R-8 | `AgentRunDetail.actions` 截断 1000 时缺 `actionsTruncated: bool` 提示 | P3 | 0.2h |
| R-9 | 94b97ad6 Replay UI 缺 Playwright E2E(硬约束) | P2 | 2-3h |
| R-10 | `imWsClient` 重连 / 大小写 dispatch 缺单测 | P3 | 1h |
| R-11 | `imWsClient` 断线后无 seq gap 检测 / 缺失消息 catch-up(架构债) | P3 | 4h |

## ⏸ Slice 3 — Plugin import + semantic fields 残留

| ID | 描述 | 优先级 | 工时 |
|---|---|---|---|
| P1-2 | `domain_category` 大小写漂移:9×`HR` vs 3×`hr`,无 CHECK 约束 | **暂缓 / 待决策** | 1h |
| P2-2 | pcba-solution 跨插件占用 22 个权限码 | P2 | — |
| P2-3 | `MetaModelCreateRequest.java` javadoc 大写 vs 实际全小写 | P2 | 0.1h |
| P2-4 | connectors menus 硬编码英文 name(违反 i18n 红线) | P2 | 0.5h |
| P3-1 | fail-fast 错误信息泄漏容器内绝对路径 | P3 | 0.2h |
| P3-2 | `loadResourceListFromZip` 静默吞子文件解析错误(违反"禁止 fallback") | P3 | 0.5h |

memory `project_semantic_fill_runtime_gap` 标 CLOSED 偏早,P1-2 / P2-2 应作为 residual 重开。

## ⏸ Slice 1 — env-layering PoC residuals

详见 `docs/backlog/2026-05-07-env-layering-followups.md` §7。

---

## 教学价值(沉淀到 review-baseline.md 候选)

1. **`d5093130` 真 schema 负向断言**(slice 4):测试与产品 fixture 同步用错字段名导致"测试通过但产品没保护"是常见反模式,显式负向断言"legacy 字段不再被接受"可以堵这类假绿灯。建议进 `auraboot-enterprise/docs/agent-rules/review-baseline.md` 第 7 类。

2. **review 看 PR diff ≠ 看 main 状态**(slice 4 R-1 误报 + slice 2 stack 在未 merge 分支):reviewer 在 PR diff 看了 chokepoint commits 以为在 main,把 GAP-311 标"实质未关闭"。审 review 报告时,P0/P1 finding 必须先确认涉及的 commit 是否 in main / in feature branch / in stack;否则 finding 的紧迫性失真。

## 关联资源

- 2026-04-29 全平台 audit:`auraboot-enterprise/docs/analysis/project-review/2026-04-29/`
- 2026-04-29 audit followup backlog:`auraboot-enterprise/docs/backlog/2026-04-29-review-followups.md`
- review-baseline.md:`auraboot-enterprise/docs/agent-rules/review-baseline.md`
- env-layering 专项 backlog:`docs/backlog/2026-05-07-env-layering-followups.md`

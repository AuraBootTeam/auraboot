---
type: backlog
status: active
created: 2026-06-23
updated: 2026-06-23
area: permission / rbac / data-scope
relates_to:
  - docs/architecture/rbac-data-scope-runtime-and-e2e.md
  - docs/plans/2026-06/2026-06-23-rbac-data-scope-endgame-matrix.md
  - docs/superpowers/reports/feature-coverage-rbac-data-scope-2026-06-23.md
---

# RBAC Data Scope Follow-up Tasks

本文承接 RBAC DataScope M1 基线，并记录本轮一期收口后的真实状态。核心原则不变：

- 普通角色通过 `self` scope 只能看自己创建的数据。
- 管理员通过 `all` scope 可以看当前租户内全部数据。
- 管理员不是业务代码特殊分支，仍走统一授权策略。
- `self` 一期统一使用 `created_by`。
- 报价员、BOM 转换员目前是角色；是否看自己由授权绑定上的 data scope 决定。
- 小组长/team scope 暂不实现运行时，等数据来源明确后再扩展。

## 任务总览

| ID | 优先级 | 任务 | 当前状态 | 本轮收口口径 |
|---|---|---|---|---|
| RDS-FU-001 | P0 | UI E2E 矩阵补齐 | done-m1 | 真实浏览器覆盖 self/admin 的 list、detail、chart；relation 保持后端回归证据，浏览器 relation fixture 单列后续。 |
| RDS-FU-002 | P0 | `all` scope 当前租户边界 | done-backend | 标准 Dynamic/aggregate/scoped bulk 均保留 tenant 条件；`all` 只移除 row owner filter，不移除租户隔离。 |
| RDS-FU-003 | P1 | NamedQuery DataScope 声明与接入 | done | `ab_named_query`、DTO、importer 支持 `resourceCode/actionCode`；执行和 aggregate 复用 `DataPermissionEngine`。 |
| RDS-FU-004 | P1 | 自定义 Controller/PF4J Handler 授权 helper | done | 新增 `DataAccessAuthorizationHelper`，提供 list filter、record、recordId 三类授权入口。 |
| RDS-FU-005 | P1 | 批量操作 scoped bulk 优化 | done-delete | `batchDelete` 改为 tenant + DataScope + domain filter 的 scoped bulk；batch update 暂不扩性能优化。 |
| RDS-FU-006 | P2 | 小组长/team scope 预研 | deferred | 只固化扩展原则：不写死角色名，不提前绑定组织/项目/业务字段。 |
| RDS-FU-007 | P2 | Full Playwright setup 非强依赖清理 | done | `PW_PROFILE=rbac` 使用最小 setup/auth/test-fixtures，不依赖 `system_overview/page_schema`。 |

## 已落地范围

| 能力 | 落地点 | 证据 |
|---|---|---|
| NamedQuery schema | `ab_named_query.resource_code`、`ab_named_query.action_code`；schema snapshot 已更新。 | `V20260623010000__named_query_data_scope_declaration.sql`，schema drift 通过。 |
| NamedQuery runtime | `NamedQueryServiceImpl.executeQuery/export` 和 `AggregateQueryServiceImpl` namedQuery aggregate 在声明存在时追加 DataScope row filter。 | `NamedQueryServiceImplTest`、`AggregateQueryServiceImplDataScopeTest`。 |
| 自定义入口 helper | `DataAccessAuthorizationHelper` / `DataAccessAuthorizationContext`。 | `DataAccessAuthorizationHelperImplTest`。 |
| action-aware record verdict | `DataPermissionEngine.canAccessRecord(tenantId, resource, action, userId, record)`。 | `DataPermissionEngineImplDataScopeTest#canAccessRecord_usesExplicitAction`。 |
| scoped bulk delete | `DynamicDataServiceImpl.batchDelete` 生成 scoped bulk DELETE SQL，影响行数不等于输入 id 数时 fail-closed。 | `DynamicDataServiceImplDataScopeRuntimeCoverageTest`。 |
| aggregate cache 隔离 | aggregate cache key 增加 `tenantId/userId/memberId/bypass|scoped` 上下文，避免 self 用户图表缓存污染 admin。 | `MetaCacheKeyGeneratorTest`，RBAC E2E chart case。 |
| RBAC targeted E2E setup | `web-admin/playwright.config.ts` 增加 `rbac-setup`、`rbac-auth`、`rbac-chromium`。 | `PW_PROFILE=rbac ... --project=rbac-chromium` 通过 17 tests。 |

## 本轮 E2E 矩阵

| 角色 | Scope | 数据 | list | detail | chart |
|---|---|---|---|---|---|
| self-scoped user | `self` | 自己记录 + admin 记录 | 自己可见，admin 记录不可见 | 自己可访问，admin 记录直接访问被拒绝或无数据 | 统计数量为 1 |
| tenant admin | `all` | 同一租户自己记录 + 普通用户记录 | 两条均可见 | 两条均可访问 | 统计数量为 2 |

运行口径：

```bash
cd web-admin
PW_PROFILE=rbac PW_WORKERS=1 \
BACKEND_URL=http://127.0.0.1:6487 SPRING_BOOT_URL=http://127.0.0.1:6487 \
BE_PORT=6487 BFF_PORT=3501 \
PGHOST=127.0.0.1 PGPORT=5432 PGUSER=auraboot PGDATABASE=auraboot_87 PGPASSWORD=auraboot \
PG_HOST=127.0.0.1 PG_PORT=5432 PG_USER=auraboot PG_DB=auraboot_87 \
NO_PROXY=localhost,127.0.0.1 \
npx playwright test -c playwright.config.ts --project=rbac-chromium --reporter=line
```

结果：`17 passed`。该 profile 的 `--list` 只包含 `00-bootstrap`、`01-multi-role-users`、`03-import-test-fixtures`、`rbac-auth` 和目标权限 spec，不包含 `02-test-pages`。

## Backlog 明细

以下条目作为本轮后的正式 backlog 追踪。状态均为 `backlog`，不影响一期已合并结论。

| ID | 优先级 | 任务 | 状态 | 触发条件 | 验收口径 |
|---|---|---|---|---|---|
| RDS-BL-001 | P0 | relation 浏览器证据 | backlog | 有稳定 relation UI fixture。 | 浏览器验证 source 记录不可见时不能通过 relation 泄漏子表；source 可见时 target 仍按 DataScope 过滤。 |
| RDS-BL-002 | P0 | 跨租户浏览器 seed | backlog | 可稳定创建租户 A/B、用户、授权和测试数据。 | 租户 A 管理员持有 `all` 后，只能在 list/detail/chart 中看到租户 A 数据，看不到租户 B 数据。 |
| RDS-BL-003 | P1 | NamedQuery validator/import warning | backlog | 受保护页面或插件开始规模化使用 NamedQuery dataSource/dashboard。 | 缺 `resourceCode/actionCode` 的受保护 NamedQuery 给 import warning、validator error 或明确的页面级阻断策略。 |
| RDS-BL-004 | P1 | `batchUpdate` scoped bulk 预研 | backlog | 出现真实大批量更新性能压力。 | 在不降低授权等价的前提下，明确是否引入 tenant + DataScope + domain filter 的 scoped bulk update。 |
| RDS-BL-005 | P2 | team/group/project scope 设计 | backlog | 小组长范围来源明确。 | 先定组织架构、项目组或业务对象字段来源，再扩 `DataScopeEvaluator`、record verdict 和 E2E matrix。 |
| RDS-BL-006 | P2 | full Playwright setup 归类治理 | backlog | full setup 继续被非权限 demo/page-schema 资源打断。 | RBAC targeted profile 继续独立；full setup 失败能明确归因到对应模块，不阻塞权限 E2E。 |

## 不重复踩的坑

| 坑 | 固化规则 |
|---|---|
| 用角色名判断管理员 | 禁止。管理员只是拥有 `all` data scope 的角色。 |
| 把 `all` 理解为跨租户 | 禁止。`all` 只代表当前租户。 |
| 用 SQL 结构推断 NamedQuery resource | 禁止。NamedQuery 必须显式声明 `resourceCode/actionCode`。 |
| list 通过就认为 detail/chart 安全 | 禁止。list、detail、aggregate/cache 必须分别有证据。 |
| RBAC E2E 依赖全量 demo page setup | 禁止作为必要前置。权限 targeted profile 应独立于 `system_overview/page_schema`。 |

---
type: plan-design
status: closed
created: 2026-06-23
updated: 2026-06-23
area: permission / rbac / data-scope
source:
  - docs/backlog/2026-06-23-rbac-data-scope-follow-up-tasks.md
  - docs/architecture/rbac-data-scope-runtime-and-e2e.md
  - docs/superpowers/reports/feature-coverage-rbac-data-scope-2026-06-23.md
---

# RBAC DataScope Endgame Matrix

本文是 RBAC DataScope 一期后续任务的实施矩阵。目标不是扩展复杂 ABAC，而是把最小闭环做干净：普通角色看自己，管理员看当前租户全部，同时为 NamedQuery、自定义入口、team scope 和大批量场景保留稳定扩展点。

## 终局原则

| 原则 | 固定口径 |
|---|---|
| 管理员判断 | 不按角色名或硬编码 admin 分支判断，统一读取授权策略。 |
| 普通角色范围 | 一期 `self` 固定按 `created_by = currentUserId`。 |
| 管理员范围 | `all` 仅表示当前租户内全部业务数据，不跨租户。 |
| 业务角色 | 报价员、BOM 转换员先作为角色；数据范围由授权绑定上的 scope 决定。 |
| NamedQuery | 显式声明 `resourceCode/actionCode` 后复用 DataScope；不解析 SQL 推断资源。 |
| 自定义入口 | Controller/PF4J Handler 使用统一 helper；不手写 `created_by` 或 `if admin`。 |
| team scope | 本轮只保留扩展点，不把组织/项目/业务对象字段提前写死。 |

## Gap 收口

| ID | Gap | 本轮处理 | 后端证据 | UI/E2E 证据 | 状态 |
|---|---|---|---|---|---|
| RDS-FU-001 | UI E2E 缺矩阵化 self/admin 证据。 | `dynamic-data-scope-runtime.spec.ts` 改为 persona matrix，覆盖 list/detail/chart。 | 既有 Dynamic runtime + aggregate 单测。 | `rbac-chromium` 17 passed；chart count 发现并回归 cache bug。 | done-m1 |
| RDS-FU-002 | `all` 当前租户边界缺专门回归。 | aggregate/all 继续走 tenant-aware mapper；batchDelete scoped SQL 强制 tenant 条件。 | `AggregateQueryServiceImplDataScopeTest`、`DynamicDataServiceImplDataScopeRuntimeCoverageTest`。 | 同租户 admin list/detail/chart 通过；跨租户浏览器 seed 后续补。 | done-backend |
| RDS-FU-003 | NamedQuery 缺显式资源动作和 DataScope 接入。 | 增 `resource_code/action_code` 字段；执行、导出、aggregate 按声明追加 DataScope。 | `NamedQueryServiceImplTest`、`AggregateQueryServiceImplDataScopeTest`、importer tests。 | 暂不伪造 NamedQuery 页面证据；缺声明不标记为已保护。 | done |
| RDS-FU-004 | 自定义 Controller/PF4J 缺统一授权 helper。 | 新增 `DataAccessAuthorizationHelper` 和 `DataAccessAuthorizationContext`。 | `DataAccessAuthorizationHelperImplTest`。 | 不适用。 | done |
| RDS-FU-005 | batchDelete 安全但非 scoped bulk。 | 改为 tenant + id set + DataScope + domain filter scoped bulk；部分命中 fail-closed。 | `DynamicDataServiceImplDataScopeRuntimeCoverageTest`。 | 批量删除 UI 后续按同矩阵补。 | done-delete |
| RDS-FU-006 | team scope 数据来源未定。 | 只沉淀扩展原则和后续入口，不实现 runtime。 | 不改运行时。 | E2E matrix 可直接新增 persona/scope case。 | deferred |
| RDS-FU-007 | RBAC E2E 被 full setup 非 RBAC 资源影响。 | 增 `PW_PROFILE=rbac` 最小依赖 profile，排除 `02-test-pages`。 | Playwright project list 可验证依赖集。 | `rbac-chromium` 不依赖 `system_overview/page_schema`。 | done |

## 通用测试矩阵

| Case | persona | scopeType | resource | action | 入口 | browser evidence | backend evidence | 期望 |
|---|---|---|---|---|---|---|---|---|
| DS-UI-01 | self-scoped user | `self` | `e2et_order` | `read` | list | 搜索 own 可见，admin/other 不可见。 | list SQL/mapper 追加 row filter。 | 普通只看自己。 |
| DS-UI-02 | self-scoped user | `self` | `e2et_order` | `read` | detail direct | own 可访问，admin/other 直接访问失败或无数据。 | `canAccessRecord` verdict。 | 不能绕列表看他人。 |
| DS-UI-03 | self-scoped user | `self` | `e2et_order` | `read` | chart/aggregate | count 为 own 记录数。 | aggregate SQL 追加 row/domain filter，cache key 区分 user/member。 | 统计不泄漏。 |
| DS-UI-04 | tenant admin | `all` | `e2et_order` | `read` | list/detail/chart | 当前租户 own/admin 记录均可见，count 为当前租户记录数。 | tenant-aware mapper 保留 tenant 条件。 | 当前租户全量可见。 |
| DS-REL-01 | self-scoped user | `self` | parent/child relation | `read` | relation | 后续有稳定 fixture 后补浏览器断言。 | `getRelationData` 先校验 source，再过滤 target。 | 关系入口不泄漏。 |
| DS-NQ-01 | self-scoped user | `self` | declared NamedQuery resource | `read` | NamedQuery execute/export | 后续有页面 datasource fixture 后补。 | NamedQuery SQL 追加 DataScope row filter。 | NQ 与 Dynamic list 等价。 |
| DS-NQ-02 | tenant admin | `all` | declared NamedQuery resource | `read` | NamedQuery aggregate | 后续有 dashboard fixture 后补。 | NQ aggregate 使用声明 resource/action。 | admin 当前租户全量。 |
| DS-HELPER-01 | custom role | `self/all/none` | custom handler | `read/delete` | helper call | 不适用。 | helper 返回 filter context 或 fail-closed verdict。 | 自定义入口复用统一策略。 |
| DS-BULK-01 | self-scoped user | `self` | `e2et_order` | `delete` | batchDelete | 后续 UI 可补批量操作。 | scoped bulk 只影响 own ids；混入他人 id fail-closed。 | 不越权批量删。 |
| DS-BULK-02 | tenant admin | `all` | `e2et_order` | `delete` | batchDelete | 后续 UI 可补批量操作。 | scoped bulk 保留 tenant 条件。 | 不跨租户批量删。 |

新增报价员、BOM 转换员、小组长时，不复制测试代码，只新增矩阵项：

| 新角色 | 一期/未来 scope | 测试扩展 |
|---|---|---|
| 报价员 | `self` | `persona=quote_owner`，resource 换成报价模型，期望仍是 created_by owner。 |
| BOM 转换员 | `self` | `persona=bom_converter`，resource 换成 BOM 任务模型，期望仍是 created_by owner。 |
| 小组长 | future `team/group/project` | 明确数据来源后新增 scope evaluator 和 persona case。 |
| 管理员 | `all` | 对所有资源复用当前租户全量断言。 |

## 实现决策

| 决策 | 选择 | 放弃 | 理由 |
|---|---|---|---|
| NamedQuery 声明字段 | 在 `ab_named_query` 增加 `resource_code/action_code`。 | metadata 隐式字段或 SQL 推断。 | 列字段可验证、可导入、可被 runtime 显式消费。 |
| NamedQuery 缺声明 | 不追加 DataScope，也不声称受保护。 | 猜主表或猜字段。 | 避免多表/聚合/跨模型查询误判。 |
| 自定义 helper 形态 | 轻量 facade：list context、record verdict、recordId loader。 | 做完整 ABAC 表达式平台。 | 一期减少重复判断，保持扩展口径。 |
| scoped bulk delete | SQL 中同时包含 tenant、ids、DataScope、domain filter，影响行数不等 fail。 | 按 id 集合直接删除。 | 保留授权等价和可解释失败。 |
| team scope | 设计先行，本轮不实现 runtime。 | 现在写死小组长角色或组织字段。 | 用户已明确来源后期再说。 |
| RBAC targeted setup | `PW_PROFILE=rbac` 最小依赖。 | 让 RBAC E2E 依赖 full setup 的 demo/page-schema。 | 权限语义不应被无关 aggregate model 阻断。 |

## 验证命令

后端 targeted：

```bash
cd platform
./gradlew :test \
  --tests 'com.auraboot.framework.meta.cache.MetaCacheKeyGeneratorTest' \
  --tests 'com.auraboot.framework.meta.service.impl.DataAccessAuthorizationHelperImplTest' \
  --tests 'com.auraboot.framework.meta.service.impl.DataPermissionEngineImplDataScopeTest' \
  --tests 'com.auraboot.framework.meta.service.impl.DynamicDataServiceImplDataScopeRuntimeCoverageTest' \
  --tests 'com.auraboot.framework.meta.service.impl.NamedQueryServiceImplTest' \
  --tests 'com.auraboot.framework.meta.service.impl.AggregateQueryServiceImplDataScopeTest' \
  --tests 'com.auraboot.framework.plugin.service.impl.PluginResourceImporterImplApplyTest2.importNamedQuery_create_happyPath' \
  --tests 'com.auraboot.framework.plugin.service.impl.PluginResourceImporterImplApplyTest2.importNamedQuery_update_preservesDataScopeDeclaration'
```

RBAC targeted E2E：

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

E2E truth 自检：

```bash
cd web-admin
PW_PROFILE=rbac npx playwright test -c playwright.config.ts --list
rg -n "test\\.only|describe\\.only|\\.skip\\(|test\\.skip|fixme|waitForTimeout|toPass|retry|threshold" \
  tests/e2e/permission/dynamic-data-scope-runtime.spec.ts \
  playwright.config.ts \
  tests/api/setup/03-import-test-fixtures.spec.ts
```

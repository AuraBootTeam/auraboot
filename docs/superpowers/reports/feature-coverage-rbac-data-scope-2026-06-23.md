---
type: test-report
status: closed
created: 2026-06-23
updated: 2026-06-23
area: permission / rbac / data-scope
---

# RBAC DataScope Feature Coverage Matrix

本文记录 RBAC DataScope 一期后续任务的覆盖状态。结论口径：

- 可以声明：一期核心闭环已覆盖普通看自己、管理员看当前租户全部、NamedQuery 显式 DataScope、自定义授权 helper、scoped bulk delete、RBAC targeted E2E setup。
- 不声明：relation 浏览器证据、跨租户浏览器 seed、team scope runtime、batchUpdate scoped bulk 已完成。

## 覆盖矩阵

| 来源 | 功能 / 行动点 | 类型 | browser evidence | backend evidence | 测试文件 | 当前状态 |
|---|---|---|---|---|---|---|
| RDS-FU-001 | self role list 只看 owner 数据 | UI + API | `dynamic-data-scope-runtime.spec.ts` persona matrix 搜索 own/admin 记录。 | Dynamic list runtime 追加 DataScope row filter。 | `web-admin/tests/e2e/permission/dynamic-data-scope-runtime.spec.ts` | done |
| RDS-FU-001 | admin all list 看当前租户全部 | UI + API | 同一 prefix 下 own/admin 两条记录均可见。 | `all` 不追加 owner filter，仍走 tenant-aware query。 | 同上 + `AggregateQueryServiceImplDataScopeTest` | done |
| RDS-FU-001 | self role detail direct 拒绝他人记录 | API + UI | persona matrix 对 own/admin record 分别断言 detail 结果。 | `DataPermissionEngine.canAccessRecord` verdict。 | 同上 + `DataPermissionEngineImplDataScopeTest` | done |
| RDS-FU-001 | chart/aggregate 不泄漏 | dashboard/chart API | persona matrix 调 `/api/meta/chart-data`，self count=1，admin count=2。 | aggregate SQL 追加 row/domain filter，cache key 按 data access context 隔离。 | 同上 + `AggregateQueryServiceImplDataScopeTest` + `MetaCacheKeyGeneratorTest` | done |
| RDS-FU-001 | relation 不泄漏 | relation | 后续补稳定 relation UI fixture。 | source record 先授权，target 查询再过滤。 | `DynamicDataServiceImplDataScopeRuntimeCoverageTest` | accepted-backend |
| RDS-FU-002 | `all` 不跨租户 | tenant isolation | 后续补租户 A/B 浏览器 seed。 | aggregate 使用 tenant-aware mapper；scoped bulk SQL 固定带 tenant 条件。 | `AggregateQueryServiceImplDataScopeTest` + `DynamicDataServiceImplDataScopeRuntimeCoverageTest` | done-backend |
| RDS-FU-003 | NamedQuery `resourceCode/actionCode` schema/DTO | platform schema | 不适用。 | migration、entity、DTO、import DTO、schema snapshot。 | `PluginResourceImporterImplApplyTest2` + schema drift | done |
| RDS-FU-003 | NamedQuery execute/export 应用 DataScope | dataSource/list | 后续有 NamedQuery 页面 fixture 后补。 | 声明存在时追加 `DataPermissionEngine.buildRowFilter(tenant, resource, action, user)`。 | `NamedQueryServiceImplTest` | done-backend |
| RDS-FU-003 | NamedQuery aggregate 应用 DataScope | chart/aggregate | 后续有 NamedQuery dashboard fixture 后补。 | namedQuery aggregate 使用声明 resource/action。 | `AggregateQueryServiceImplDataScopeTest` | done-backend |
| RDS-FU-004 | custom Controller/PF4J helper | helper/API | 不适用。 | list context、record verdict、recordId loader、fail-closed。 | `DataAccessAuthorizationHelperImplTest` | done |
| RDS-FU-004 | 示例不写 `if admin`/`created_by` | docs/example | 不适用。 | system-reference 明确 custom handler 调 helper，不手写 admin/owner 分支。 | `docs/architecture/rbac-data-scope-runtime-and-e2e.md` | done-doc |
| RDS-FU-005 | scoped bulk delete self | batch operation | 后续可补 UI 批量删除。 | mixed ids 影响行数不匹配时 fail-closed。 | `DynamicDataServiceImplDataScopeRuntimeCoverageTest` | done-backend |
| RDS-FU-005 | scoped bulk delete all 当前租户 | batch operation | 后续可补 UI 批量删除。 | SQL 固定包含 `tenant_id = #{params.tenantId}`。 | 同上 | done-backend |
| RDS-FU-006 | team scope 预研 | design | 不适用。 | 不改 runtime，保留矩阵和 scope resolver 扩展方向。 | `docs/plans/2026-06/2026-06-23-rbac-data-scope-endgame-matrix.md` | deferred |
| RDS-FU-007 | RBAC targeted setup 不依赖 `system_overview/page_schema` | test infra | `PW_PROFILE=rbac --list` 不包含 `02-test-pages`；targeted run 17 passed。 | Playwright config 拆 `rbac-setup/rbac-auth/rbac-chromium`。 | `web-admin/playwright.config.ts` | done |

## 验证记录

| 命令 | 结果 | 说明 |
|---|---|---|
| `scripts/db/generate-schema-snapshot.sh --edition oss` | pass | 更新当前 schema snapshot。 |
| `scripts/db/check-schema-drift.sh --edition oss` | pass | Flyway fresh DB + snapshot drift 检查通过。 |
| backend targeted Gradle tests | pass | 覆盖 NamedQuery、aggregate、helper、DataPermissionEngine、scoped bulk、importer、cache key。 |
| `PW_PROFILE=rbac npx playwright test -c playwright.config.ts --list` | pass | 证明 targeted profile 不依赖 `02-test-pages`。 |
| `PW_PROFILE=rbac ... --project=rbac-chromium --reporter=line` | `17 passed` | 真实后端 + 真实浏览器权限矩阵通过。 |

## e2e-truth 结论

| 检查项 | 结论 |
|---|---|
| 覆盖矩阵 | 本文件列明 done、done-backend、accepted-backend、deferred；不把 deferred 项包装成已完成。 |
| PUT/API 兜底 | setup 和部分 chart/detail 使用 API；list 可见性走真实 UI，chart/detail 是 targeted runtime coverage。 |
| skip/fixme | 目标权限 spec 不使用 `.only`、`fixme` 或 test-level skip；setup gate 中的 `test.skip` 仅用于非匹配 profile 跳过导入。 |
| waitForTimeout | 目标权限 spec 不使用 `waitForTimeout`。 |
| threshold/retry | 未放宽断言阈值或依赖 retry。 |
| full vs targeted | 本结论只声明 `PW_PROFILE=rbac` targeted profile，不声明 full Playwright 全套通过。 |

## 发现并修复的问题

RBAC E2E 首次跑 chart matrix 时暴露了真实缺陷：aggregate cache key 只按 tenant + request hash 区分，self 用户的 chart 结果可能被 admin 复用。修复后 cache key 增加 `tenantId/userId/memberId/bypass|scoped`，并通过 `MetaCacheKeyGeneratorTest` 和 targeted E2E 回归。

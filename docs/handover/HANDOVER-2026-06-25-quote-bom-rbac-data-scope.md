---
type: handover
status: active
created: 2026-06-25
---

# Session Handover - 2026-06-25 00:49 CST

## Session Summary

本会话围绕 RBAC DataScope 一期收口：普通角色看自己、管理员看当前租户全部，同时不堵住报价员、BOM 转换员、小组长、NamedQuery、自定义 Handler 和批量操作的后续演进。实现已通过 PR #1065 合并；后续 backlog 通过 PR #1066 追加并合并。

## Tasks Completed

- [x] 明确一期原则：管理员不是业务绕过分支，只是持有 `all` data scope 的角色；`all` 只限当前租户。
- [x] `self` 创建人字段一期统一为 `created_by`；报价员、BOM 转换员当前首先是角色，数据范围由授权绑定上的 scope 决定。
- [x] NamedQuery 增加显式 `resourceCode/actionCode` 声明并接入统一 DataScope，不靠 SQL 推断资源。
- [x] 新增自定义 Controller/PF4J Handler 授权 helper，避免手写 `if admin` 或 `created_by` 判断。
- [x] `batchDelete` 改为 tenant + id set + DataScope + domain filter 的 scoped bulk；影响行数不匹配时 fail-closed。
- [x] 修复 aggregate cache 按 tenant/request 复用导致 self/admin chart 结果串用的问题。
- [x] 增加 `PW_PROFILE=rbac` targeted Playwright profile，权限 E2E 不再强依赖 `system_overview/page_schema` setup。
- [x] 增加 UI E2E persona matrix，覆盖 self/admin 的 list、detail、chart。
- [x] 沉淀系统参考、测试矩阵、feature coverage、follow-up backlog。
- [x] 合并 PR #1065 和 #1066 到 `main`，当前 `origin/main` 已包含本会话提交。

## Tasks In Progress

无进行中代码任务。剩余项均已记录为 backlog，见本文 “Next Steps” 和 `docs/backlog/2026-06-23-rbac-data-scope-follow-up-tasks.md`。

## Key Decisions

| Decision | Chosen Approach | Rationale | Alternatives Considered |
|---|---|---|---|
| 管理员判断 | 通过授权策略计算，管理员角色获得 `all` data scope。 | 避免业务代码散落管理员特殊分支，后续角色可复用。 | 在业务代码中判断角色名 `admin`，已放弃。 |
| `self` owner 字段 | 一期统一使用 `created_by`。 | 满足当前“普通看自己”核心需求，减少模型级配置复杂度。 | 每模型 ownerField 配置，后续可独立增强。 |
| 报价员 / BOM 转换员 | 当前按角色处理，数据语义仍为 `self`。 | 业务角色和数据范围解耦；未来可加 `assigned/team` scope。 | 把角色名直接编码成数据范围，已放弃。 |
| `all` scope | 只表示当前租户内全部业务数据。 | 多租户隔离不应被管理员 scope 绕过。 | 跨租户平台管理员语义，另走 platform admin，不混入租户业务。 |
| NamedQuery DataScope | 显式声明 `resourceCode/actionCode`。 | 多表、聚合、跨模型 SQL 无法可靠自动推断。 | 解析 SQL 主表或字段名，已放弃。 |
| 自定义入口 | 提供 `DataAccessAuthorizationHelper`。 | PF4J/Controller 可复用统一策略，减少重复硬编码。 | 每个 handler 手写 `created_by`/admin 分支，已放弃。 |
| RBAC E2E setup | `PW_PROFILE=rbac` 最小依赖 profile。 | 权限验证不应被非权限 demo/page-schema setup 打断。 | 继续依赖 full setup，已放弃。 |

## Files Changed

### Backend

PR #1065 修改/新增：

- `platform/src/main/java/com/auraboot/framework/meta/cache/MetaCacheKeyGenerator.java` - 增加 data-access cache suffix，按 tenant/user/member/bypass 隔离 aggregate cache。
- `platform/src/main/java/com/auraboot/framework/meta/dto/NamedQueryCreateRequest.java` - 新增 `resourceCode/actionCode`。
- `platform/src/main/java/com/auraboot/framework/meta/dto/NamedQueryDTO.java` - DTO 暴露 DataScope 声明。
- `platform/src/main/java/com/auraboot/framework/meta/dto/NamedQueryUpdateRequest.java` - 更新请求支持 DataScope 声明。
- `platform/src/main/java/com/auraboot/framework/meta/entity/NamedQuery.java` - 持久化 `resourceCode/actionCode`。
- `platform/src/main/java/com/auraboot/framework/meta/service/DataPermissionEngine.java` - 增加 action-aware row filter 和 record verdict overload。
- `platform/src/main/java/com/auraboot/framework/meta/service/DataAccessAuthorizationContext.java` - 新增自定义入口授权上下文。
- `platform/src/main/java/com/auraboot/framework/meta/service/DataAccessAuthorizationHelper.java` - 新增自定义入口授权 facade。
- `platform/src/main/java/com/auraboot/framework/meta/service/impl/DataAccessAuthorizationHelperImpl.java` - helper 实现，fail-closed。
- `platform/src/main/java/com/auraboot/framework/meta/service/impl/DataPermissionEngineImpl.java` - action-aware DataScope 解析与 record verdict。
- `platform/src/main/java/com/auraboot/framework/meta/service/impl/DynamicDataServiceImpl.java` - `batchDelete` scoped bulk 化。
- `platform/src/main/java/com/auraboot/framework/meta/service/impl/NamedQueryServiceImpl.java` - execute/export 按声明追加 DataScope。
- `platform/src/main/java/com/auraboot/framework/meta/service/impl/AggregateQueryServiceImpl.java` - aggregate cache key 修复；NamedQuery aggregate 接入 DataScope。
- `platform/src/main/java/com/auraboot/framework/plugin/dto/imports/NamedQueryDefinitionDTO.java` - plugin importer DTO 支持声明。
- `platform/src/main/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImpl.java` - 导入/更新 NamedQuery 时保留声明。
- `platform/src/main/resources/db/migration/core/V20260623010000__named_query_data_scope_declaration.sql` - NamedQuery schema migration。
- `platform/src/main/resources/database/schema.sql` - schema baseline 更新。
- `platform/src/main/resources/db/snapshots/schema-current.sql` - Flyway snapshot 更新。

### Backend Tests

- `platform/src/test/java/com/auraboot/framework/meta/cache/MetaCacheKeyGeneratorTest.java` - cache key 按 user/member/bypass 隔离。
- `platform/src/test/java/com/auraboot/framework/meta/service/impl/DataAccessAuthorizationHelperImplTest.java` - helper list/record/recordId/fail-closed 测试。
- `platform/src/test/java/com/auraboot/framework/meta/service/impl/DataPermissionEngineImplDataScopeTest.java` - action-aware record verdict。
- `platform/src/test/java/com/auraboot/framework/meta/service/impl/DynamicDataServiceImplDataScopeRuntimeCoverageTest.java` - scoped bulk delete、relation/custom count 回归。
- `platform/src/test/java/com/auraboot/framework/meta/service/impl/NamedQueryServiceImplTest.java` - NamedQuery 声明存在/缺失两种路径。
- `platform/src/test/java/com/auraboot/framework/meta/service/impl/AggregateQueryServiceImplDataScopeTest.java` - dynamic/namedQuery aggregate DataScope 和 tenant-aware 回归。
- `platform/src/test/java/com/auraboot/framework/plugin/service/impl/PluginResourceImporterImplApplyTest2.java` - NamedQuery import create/update 保留声明。

### Frontend / E2E

- `web-admin/playwright.config.ts` - 增加 `rbac-setup`、`rbac-auth`、`rbac-chromium` profile。
- `web-admin/tests/api/setup/03-import-test-fixtures.spec.ts` - 允许 `PW_PROFILE=rbac` 导入 test-fixtures。
- `web-admin/tests/e2e/permission/dynamic-data-scope-runtime.spec.ts` - persona matrix 覆盖 self/admin list/detail/chart。

### Documentation

- `docs/architecture/rbac-data-scope-runtime-and-e2e.md` - system reference 更新。
- `docs/core-concepts/permissions.md` - 明确 wildcard 不是数据绕过，`ALL` 仅当前租户，`SELF` 使用 `created_by`。
- `docs/core-concepts/plugin-manifest.md` - plugin manifest 索引补 DataScope/NamedQuery 声明口径。
- `docs/plugin-development/plugin-manifest-reference.md` - roles `defaultDataScopeType` 和 NamedQuery 声明示例。
- `docs/backlog/2026-06-23-rbac-data-scope-follow-up-tasks.md` - 一期状态、backlog 明细。
- `docs/plans/2026-06/2026-06-23-rbac-data-scope-endgame-matrix.md` - endgame 测试矩阵。
- `docs/superpowers/reports/feature-coverage-rbac-data-scope-2026-06-23.md` - feature coverage 和 e2e-truth 结论。
- `docs/handover/HANDOVER-2026-06-25-quote-bom-rbac-data-scope.md` - 本 handover。

### Follow-up Backlog PR #1066

- `docs/backlog/2026-06-23-rbac-data-scope-follow-up-tasks.md` - 把后续项正式编号为 `RDS-BL-001` 至 `RDS-BL-006`。

## Commands Run

### Schema / Migration

```bash
scripts/db/generate-schema-snapshot.sh --edition oss
scripts/db/check-schema-drift.sh --edition oss
```

结果：`check-schema-drift` 通过，Flyway fresh DB 应用到 `v20260623010000`，snapshot 无 drift。

### Backend Targeted Tests

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

结果：`BUILD SUCCESSFUL`；关键用例均 pass，包括 NamedQuery、aggregate、helper、DataPermissionEngine、scoped bulk、importer、cache key。

### RBAC Targeted E2E

```bash
cd web-admin
PW_PROFILE=rbac npx playwright test -c playwright.config.ts --list
```

结果：列出 17 tests，只有 `00-bootstrap`、`01-multi-role-users`、`03-import-test-fixtures`、`rbac-auth`、目标权限 spec；不包含 `02-test-pages`。

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

结果：`17 passed`。执行后 `./scripts/oss-golden-stack.sh destroy rbac-scope` 已清理隔离 runtime。

### E2E Truth / Static Checks

```bash
rg -n 'test\.only|describe\.only|\.skip\(|test\.skip|fixme|waitForTimeout|toPass|retry|threshold' \
  web-admin/tests/e2e/permission/dynamic-data-scope-runtime.spec.ts \
  web-admin/playwright.config.ts \
  web-admin/tests/api/setup/03-import-test-fixtures.spec.ts
git diff --check
```

结果：目标权限 spec 无 `.only`、`fixme`、`waitForTimeout`、阈值放宽；`test.skip` 仅为 setup gate；`trace/video on-first-retry` 是 Playwright artifact 配置。`git diff --check` 通过。

### Git / PR

```bash
git push -u origin codex/rbac-data-scope-endgame
gh api repos/AuraBootTeam/auraboot/pulls -X POST ...
gh api repos/AuraBootTeam/auraboot/pulls/1065/merge -X PUT ...
git push -u origin codex/rbac-data-scope-backlog-followups
gh api repos/AuraBootTeam/auraboot/pulls -X POST ...
gh api repos/AuraBootTeam/auraboot/pulls/1066/merge -X PUT ...
```

备注：`gh pr create` / `gh pr checks` / `gh pr merge` 多次遇到 GitHub GraphQL `EOF`，改用 REST API 成功创建和合并 PR。

## Test Results

| Area | Command / Evidence | Result |
|---|---|---|
| Backend targeted tests | Gradle targeted command above | `BUILD SUCCESSFUL` |
| Schema drift | `scripts/db/check-schema-drift.sh --edition oss` | pass，snapshot matches Flyway result |
| RBAC E2E project list | `PW_PROFILE=rbac ... --list` | 17 tests，不含 `02-test-pages` |
| RBAC E2E runtime | `PW_PROFILE=rbac ... --project=rbac-chromium` | `17 passed` |
| E2E truth grep | grep command above | no target-spec `.only/fixme/waitForTimeout/threshold` |
| Whitespace | `git diff --check` | pass |
| PR #1065 | REST API merge | merged，merge commit `7c715d622c813f75bae27cf3f6aa77df71b16037` |
| PR #1066 | REST API merge | merged，merge commit `e4cdef05ae3cba9c7b4f3c15ba7867ad1006e4ac` |

## Evidence Paths

- System reference: `docs/architecture/rbac-data-scope-runtime-and-e2e.md`
- Follow-up backlog: `docs/backlog/2026-06-23-rbac-data-scope-follow-up-tasks.md`
- Endgame matrix: `docs/plans/2026-06/2026-06-23-rbac-data-scope-endgame-matrix.md`
- Feature coverage / e2e-truth report: `docs/superpowers/reports/feature-coverage-rbac-data-scope-2026-06-23.md`
- E2E local result directory: `web-admin/test-results/rbac-data-scope-e2e-87/`
  - `artifacts-spec/.last-run.json`: targeted spec passed.
  - `artifacts-auth/.last-run.json`: auth setup passed.
  - `artifacts/.last-run.json`: contains older unrelated `02-test-pages` full setup failure; do not use this as RBAC targeted result.
- PR #1065: `https://github.com/AuraBootTeam/auraboot/pull/1065`
- PR #1066: `https://github.com/AuraBootTeam/auraboot/pull/1066`
- Merge commit #1065: `7c715d622c813f75bae27cf3f6aa77df71b16037`
- Merge commit #1066: `e4cdef05ae3cba9c7b4f3c15ba7867ad1006e4ac`
- Current main at handover time: `d62c14bb80bc83510168dc2967c260c9a736f6e1`

## Pitfalls & Workarounds

1. **Problem**: full Playwright setup 里 `system_overview/page_schema` 相关资源会打断权限 E2E。
   - **Root Cause**: 权限验证依赖了非权限 demo/page-schema setup。
   - **Solution**: 增加 `PW_PROFILE=rbac`，只跑 bootstrap、multi-role users、test-fixtures、rbac-auth 和目标 spec。
   - **Prevention**: 权限 targeted profile 保持最小依赖；full setup 失败要归因到对应模块。

2. **Problem**: chart matrix 首次暴露 self 用户 aggregate cache 结果被 admin 复用。
   - **Root Cause**: aggregate cache key 只按 tenant + request hash，不包含 user/member/bypass 状态。
   - **Solution**: `MetaCacheKeyGenerator.getDataAccessContextSuffix()` 加 tenant/user/member/bypass；aggregate cache 使用该 suffix。
   - **Prevention**: 所有受 DataScope 影响的缓存都必须包含 data-access context。

3. **Problem**: `gh pr create` / `gh pr merge` / `gh pr checks` 多次 GitHub GraphQL `EOF`。
   - **Root Cause**: GitHub CLI GraphQL 通道不稳定。
   - **Solution**: 使用 `gh api repos/.../pulls` 和 `pulls/{num}/merge` REST endpoint 创建/合并。
   - **Prevention**: GitHub GraphQL EOF 时先查是否半创建成功，再切 REST；不要重复盲发。

4. **Problem**: 路径/仓库容易混淆，早期工作在临时 worktree，当前环境主仓在 `/Users/ghj/work/auraboot/auraboot`。
   - **Root Cause**: 多 repo / 多 worktree 并存，且外层 `/Users/ghj/work/auraboot` 也是一个 workspace repo。
   - **Solution**: 以 `git remote -v`、`git status -sb`、`git worktree list` 确认当前 handover 写入 actual `AuraBootTeam/auraboot` repo。
   - **Prevention**: handover 必须记录 repo/worktree 路径和 current commit。

## Lessons Learned

- 管理员不是特殊绕过分支；所有业务数据访问统一计算授权策略。
- `all` 不等于跨租户；tenant isolation 与 DataScope 是两层约束。
- 报价员、BOM 转换员这类业务身份先作为角色处理，是否看自己由 scope 决定。
- NamedQuery 必须显式声明授权资源和动作，不能靠 SQL 结构猜。
- list 通过不代表 detail/chart/relation/bulk 安全，必须逐入口验证。
- 权限 E2E 的最小 profile 能显著减少无关 setup 对核心权限验证的干扰。

## 反思与经验固化 (Reflection & Codify)

### 本会话弯路 / 返工 / 翻车

1. **把 full setup 的 page-schema 问题误当成权限 E2E 阻塞风险** — 代价：多轮解释和文档澄清 — 本可如何更早避免：先拆权限最小依赖 profile，再把 full setup 作为独立治理项 — 根因：`[B 输入 / D 验证]`
2. **aggregate cache 权限上下文缺失由 E2E 才暴露** — 代价：一次失败后补修复和单测 — 本可如何更早避免：在设计 DataScope aggregate 时同步审计缓存 key — 根因：`[A 门禁质量]`
3. **GitHub GraphQL EOF 导致 PR/check/merge 常规命令失败** — 代价：多次重试 — 本可如何更早避免：第一次 EOF 后直接查半创建状态并切 REST API — 根因：`[D 验证]`
4. **多 worktree 路径在后续 handover 环境里发生变化** — 代价：需要重新定位 actual repo — 本可如何更早避免：每次 final 都明确 actual repo path、main checkout path 和 workspace shell repo 差异 — 根因：`[B 输入]`

### 为什么会发生(根因归类小结)

主要根因是输入/运行态信息容易失真，以及验证边界需要拆清。真正的缺陷不是“权限方案复杂”，而是权限 runtime 涉及 list/detail/chart/cache/setup 多条路径，任何一条用旧假设都会漏。

### 应该有哪些改进

- 权限/数据范围任务的设计 checklist 增加 “cache key 是否含 data-access context”。
- 权限 E2E 默认优先 targeted profile，full setup 仅作为套件健康度，不作为权限语义阻塞。
- PR 自动化遇到 GraphQL EOF 时使用 REST fallback，并先确认是否已创建/合并。
- Handover 固定写 actual repo path、current commit、PR merge commit、runtime name/ports。

### 已固化 / 待固化(更新文档)

- [x] 已写入 `docs/architecture/rbac-data-scope-runtime-and-e2e.md`: 管理员/`all`/NamedQuery/helper/scoped bulk/RBAC targeted setup 规则。
- [x] 已写入 `docs/core-concepts/permissions.md`: wildcard 不等于数据绕过；`ALL` 只限当前租户；`SELF` 使用 `created_by`。
- [x] 已写入 `docs/plugin-development/plugin-manifest-reference.md`: `defaultDataScopeType` 和 NamedQuery `resourceCode/actionCode` 示例。
- [x] 已写入 `docs/backlog/2026-06-23-rbac-data-scope-follow-up-tasks.md`: `RDS-BL-001` 至 `RDS-BL-006` 后续 backlog。
- [ ] 待 owner 决策：是否把 “DataScope cache key 必须包含 data-access context” 上升为全局工程红线。

## 运行态快照 (Operational State)

### 分支 / Worktree / PR

- **当前 repo**: `/Users/ghj/work/auraboot/auraboot`
- **Remote**: `git@github.com:AuraBootTeam/auraboot.git`
- **当前分支**: `main`
- **HEAD**: `d62c14bb80bc83510168dc2967c260c9a736f6e1`
- **ahead/behind**: `origin/main...HEAD = 0/0`
- **未提交改动**: 本 handover 文件新增后会显示为 unstaged；生成前 `git status -sb` 为 clean。
- **Worktree snapshot**:
  - `/Users/ghj/work/auraboot/auraboot` -> `main` @ `d62c14bb8`
  - `/Users/ghj/work/auraboot/auraboot/.worktrees/permission-ux-simplify` -> `codex/permission-ux-simplify` @ `d62c14bb8`
- **本会话关键 commits**:
  - `07e030d4d8dd68813a23dd6d52df98ae4774c7e7` - implementation commit for PR #1065.
  - `7c715d622c813f75bae27cf3f6aa77df71b16037` - merge commit for PR #1065.
  - `75fb70b3875bf97774aa2f127d9cdf1f4da31db6` - backlog docs commit for PR #1066.
  - `e4cdef05ae3cba9c7b4f3c15ba7867ad1006e4ac` - merge commit for PR #1066.
- **PR**:
  - `#1065 · closed/merged · head 07e030d4d8dd68813a23dd6d52df98ae4774c7e7 · base main · merge 7c715d622c813f75bae27cf3f6aa77df71b16037`
  - `#1066 · closed/merged · head 75fb70b3875bf97774aa2f127d9cdf1f4da31db6 · base main · merge e4cdef05ae3cba9c7b4f3c15ba7867ad1006e4ac`
- **Merge evidence**: `git branch --all --contains 07e030d4...`, `7c715d622...`, `e4cdef05...` all include `main` and `origin/main`.

### Runtime / 端口(host-first slot 模型,零 docker)

- **Runtime used during E2E**: `rbac-scope`, slot `87`.
- **E2E backend**: `http://127.0.0.1:6487`
- **E2E BFF port env**: `3501`
- **E2E DB**: `auraboot_87`
- **Runtime cleanup**: `./scripts/oss-golden-stack.sh destroy rbac-scope` 已执行。
- **当前监听**:
  - `6487`: 无监听。
  - `3501`: 无监听。
  - 常驻 Postgres `5432`: running。
  - 常驻 Redis `6379`: running。
  - 常驻 Kafka `9092`: running。
- **依赖的常驻 broker**: Postgres、Redis、Kafka。未使用 docker。
- **接手者起栈命令**:
  ```bash
  ./scripts/oss-golden-stack.sh up rbac-scope --slot 87 --no-frontend --ttl 2h --plugin test-fixtures
  ```

### Database / Seed 状态

- PR #1065 新增 Flyway migration `V20260623010000__named_query_data_scope_declaration.sql`。
- `scripts/db/check-schema-drift.sh --edition oss` 已用 fresh DB 验证 schema snapshot。
- E2E 使用 `test-fixtures` 插件与 `PW_PROFILE=rbac` setup，runtime 已 destroy；接手若复跑需重新起 `rbac-scope`。

## Next Steps

1. `RDS-BL-001` - 补 relation 浏览器证据：稳定 relation UI fixture 后验证 source invisible 不泄漏 target rows。
2. `RDS-BL-002` - 补跨租户浏览器 seed：租户 A admin 的 `all` 不可见租户 B 数据。
3. `RDS-BL-003` - NamedQuery validator/import warning：受保护页面缺 `resourceCode/actionCode` 时给 warning/error/阻断策略。
4. `RDS-BL-004` - `batchUpdate` scoped bulk 预研：出现真实大批量性能压力后再设计。
5. `RDS-BL-005` - team/group/project scope：先定小组长数据来源，再扩 runtime 和 E2E matrix。
6. `RDS-BL-006` - full Playwright setup 归类治理：full setup 的 demo/page-schema 问题不阻塞 RBAC targeted E2E。

## Context for Next Session

从这些入口继续即可：

- 方案/system reference: `docs/architecture/rbac-data-scope-runtime-and-e2e.md`
- backlog: `docs/backlog/2026-06-23-rbac-data-scope-follow-up-tasks.md`
- 覆盖矩阵: `docs/plans/2026-06/2026-06-23-rbac-data-scope-endgame-matrix.md`
- 测试报告: `docs/superpowers/reports/feature-coverage-rbac-data-scope-2026-06-23.md`
- E2E spec: `web-admin/tests/e2e/permission/dynamic-data-scope-runtime.spec.ts`
- Playwright profile: `web-admin/playwright.config.ts`
- 复跑前确认:
  ```bash
  git status -sb
  git worktree list
  scripts/db/check-schema-drift.sh --edition oss
  PW_PROFILE=rbac npx playwright test -c web-admin/playwright.config.ts --list
  ```

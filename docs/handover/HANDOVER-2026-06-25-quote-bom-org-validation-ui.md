---
type: handover
status: active
created: 2026-06-25
---

# Session Handover - 2026-06-25 18:31

## Session Summary

本次会话围绕 Quote/BOM 118 验证环境补齐组织与权限相关 UI、接口和 DSL 页面缺口:动态组织详情页不再空白,团队新增后可通过真实租户成员加入团队,租户/LLM/系统偏好入口补上权限兜底,并完成真栈截图验证。

## Tasks Completed

- [x] 修复 `/p/org_position/view/:pid`、`/p/org_employee/view/:pid`、`/p/org_department/view/:pid` 空白配置页问题。
- [x] 修复 `/organization/teams` 新增团队后无法加成员的问题,避免 JS `Long` 精度丢失。
- [x] 调整团队详情页与成员添加弹窗的中文文案、表格样式、重复成员过滤。
- [x] 调整 `/p/tenant_member`、`/p/org_employee` 列表外观与 `/organization/members/:pid` 详情页宽度和显示名。
- [x] 为租户信息、租户编辑、系统偏好、LLM provider 页面补充 route-level permission guard。
- [x] 保留 BOM 相关 select 依赖项空值时不打开空 dropdown 的前端修复与测试。

## Files Changed

### Backend

- `platform/src/main/java/com/auraboot/framework/organization/dto/TeamMemberAddRequest.java` - 团队加成员请求兼容 `userId`、`userPid`、`memberPid`。
- `platform/src/main/java/com/auraboot/framework/organization/dto/TeamMemberResponse.java` - 团队成员响应补充 `userPid`、`memberPid`。
- `platform/src/main/java/com/auraboot/framework/organization/service/impl/TeamMemberServiceImpl.java` - 解析 `memberPid/userPid`,保留旧 `userId`,并校验当前租户成员边界。

### Frontend

- `web-admin/app/plugins/core-organization/pages/organization/team-detail.tsx` - 团队详情页改为 POST 搜索租户成员、提交 `memberPid`,更新中文 UI 和重复成员过滤。
- `web-admin/app/plugins/core-organization/pages/organization/member-detail.tsx` - 会员详情页加宽,显示员工姓名优先于账号 pid。
- `web-admin/app/framework/meta/rendering/pages/ListPageContent.tsx` - 动态列表页增加统一背景和卡片外框。
- `web-admin/app/framework/meta/rendering/pages/list/ListPageHeader.tsx` - 动态列表 header 视觉收敛。
- `web-admin/app/framework/meta/rendering/pages/list/ListToolbar.tsx` - 动态列表 toolbar 控件高度和间距收敛。
- `web-admin/app/plugins/core-aurabot/pages/aurabot/providers.tsx` - LLM provider 页面按 `ai_center/system_management` 做 route guard。
- `web-admin/app/plugins/core-settings/pages/settings/system-preferences.tsx` - 系统偏好页按 `system_management` 做 route guard。
- `web-admin/app/routes/enterprise/TenantInfo.tsx` - 租户信息页按 `org.tenant.read/update` 控制读写入口。
- `web-admin/app/routes/enterprise/TenantEditForm.tsx` - 租户编辑页按 `org.tenant.update` 控制访问。
- `web-admin/app/ui/PermissionGuard.tsx` - 新增 `RouteAccessDenied`。
- `web-admin/app/ui/base-fields/BaseSelect.tsx` - 无 options 时不渲染空 dropdown content。
- `web-admin/app/ui/smart/form/Select.tsx` - 支持依赖字段未满足时不打开/不 refetch。
- `web-admin/app/ui/smart/form/__tests__/Select.test.tsx` - 覆盖空 options、loading-only、dependent select 三个回归场景。
- `web-admin/app/shared/services/teamService.ts` - 团队成员 id 类型改为 string 并兼容 pid 请求。
- `web-admin/app/shared/services/__tests__/teamService.test.ts` - 更新团队成员服务测试夹具。

### Plugin DSL

- `plugins/org-management/config/pages/org_position_detail.json` - 新增岗位只读详情页。
- `plugins/org-management/config/pages/org_employee_detail.json` - 新增员工只读详情页。
- `plugins/org-management/config/pages/org_department_detail.json` - 新增部门只读详情页。

## Verification

- `pnpm typecheck` in `web-admin` - passed.
- `./gradlew compileJava bootJar` in `platform` - passed.
- `git diff --check` - passed.
- Runtime `quote-bom-final-verify-118` restarted with:
  - backend `http://127.0.0.1:6568`
  - BFF `http://127.0.0.1:6268`
  - web `http://127.0.0.1:5268`
- Plugin import: `org-management`, `jiejia-integration`, `bom-standardization` all OK.
- Playwright manual screenshots:
  - `verify-org-position-detail-20260625.png`
  - `verify-org-employee-detail-20260625.png`
  - `verify-org-department-detail-20260625.png`
  - `verify-team-member-added-final-20260625.png`

## Pitfalls & Workarounds

1. **Problem**: 团队加成员最初仍返回 `User ID is required`.
   - **Root Cause**: 只构建了 `platform/bootJar`,但 host-stack 默认 jar 名和实际 jar 不一致;运行时仍在用旧 jar。
   - **Solution**: 重启时显式传 `BOM_HOST_BOOT_JAR=/Users/ghj/work/auraboot/.worktrees/quote-bom-final-verify/auraboot/platform/build/libs/AuraBoot-1.0.0-SNAPSHOT-boot.jar`。
   - **Prevention**: 代码改动涉及后端 jar 时,必须确认 host-stack 实际 `BOM_HOST_BOOT_JAR` 与构建产物一致。
2. **Problem**: `org_position/org_employee/org_department` 详情页都显示“此页面尚未配置内容”。
   - **Root Cause**: `org-management` 只有 list/form 页面资源,没有 `*_detail` 页面。
   - **Solution**: 补齐三个 detail JSON,并重新导入插件资源。
   - **Prevention**: 动态模型暴露 `/p/{model}/view/:pid` 时,插件必须随 list/form 一起交付 detail 页面或明确隐藏详情入口。
3. **Problem**: 团队加成员前端从成员搜索拿不到安全的数字 `userId`。
   - **Root Cause**: 租户成员搜索主要返回 public pid;后端团队成员接口只接受 Java `Long userId`,前端 `Number(...)` 会丢精度。
   - **Solution**: 后端兼容 `memberPid/userPid`,前端提交 `memberPid`。
   - **Prevention**: public API 优先使用 pid,只有服务层内部解析 Long id。

## 反思与经验固化 (Reflection & Codify)

### 本会话弯路 / 返工 / 翻车

1. **后端 jar 构建与运行 jar 不一致** - 代价:多一次重启和一次失败 UI 提交 - 本可更早避免:重启前检查 `platform/build/libs` 与 host-stack 默认 `BOM_HOST_BOOT_JAR` - 根因:`[D 验证纪律]`
2. **只补岗位/员工详情,后续才发现部门详情也缺** - 代价:用户再次指出同类 URL - 本可更早避免:看到 `org-management` list/form-only 后应一次性检查三个组织模型 - 根因:`[D 验证纪律]`
3. **团队成员下拉第一版未过滤已加入成员** - 代价:多一次前端修复和重启 - 本可更早避免:happy path 通过后立刻重开弹窗验证重复选择 negative case - 根因:`[A 门禁质量,D 验证纪律]`

### 为什么会发生

本会话主要问题不是输入不足,而是真栈验证粒度不够系统:局部 URL 修通后没有立即枚举同插件 sibling model;接口 happy path 通过后没有先覆盖重复选择和运行 jar 归属。

### 应该有哪些改进

- 对 DSL 插件补页面时,按 model family 一次性检查 list/form/detail 三件套。
- 对 host-stack jar 模式,把 `BOM_HOST_BOOT_JAR` 写入验证步骤,避免默认 jar 名漂移。
- 对“新增成员/角色/权限”类 UI,必须验证 happy path + duplicate/撤回/无权限至少一个 negative case。

### 已固化 / 待固化

- [x] 已写入本文档:组织模型 detail 三件套、团队成员 pid contract、host-stack jar 指定方式。
- [ ] 待固化到 `docs/agent-rules/runtime-artifact-contract.md`:host-stack jar 模式必须核对 `BOM_HOST_BOOT_JAR` 与最新 bootJar 文件名。
- [ ] 待固化到 org-management 插件规范:新增组织模型时 list/form/detail 必须成套导入。

## 运行态快照 (Operational State)

### 分支 / Worktree / PR

- **当前分支**:`codex/quote-bom-final-verify`
- **Worktree**:`/Users/ghj/work/auraboot/.worktrees/quote-bom-final-verify/auraboot`
- **PR**:提交前未开新 PR。
- **未提交改动**:见本文件提交所在 commit。

### Runtime / 端口

- **Runtime**:`quote-bom-final-verify-118`
- **Env**:`/Users/ghj/work/auraboot/.workspace/env/quote-bom-final-verify-118.env`
- **端口**:backend `6568`, web `5268`, BFF `6268`
- **命名空间**:Postgres DB `enterprise_118`, Redis prefix `aura:auraboot-enterprise:118:`, Kafka prefix `enterprise.118.`
- **启动方式**:host-stack + `BOM_HOST_BACKEND_MODE=jar` + explicit `BOM_HOST_BOOT_JAR`

## Next Steps

1. 合并前确认 `codex/quote-bom-final-verify` rebase 到最新 `origin/main` 后无冲突。
2. 若继续做组织权限验收,补菜单权限、数据权限、操作权限的撤销截图矩阵。
3. 若继续收敛 Quote/BOM SOT,同步 enterprise/root 两份 SOT 文档的最终路径和归档状态。

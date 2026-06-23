---
type: plan-design
status: active
created: 2026-06-22
relates_to:
  - docs/backlog/2026-06-22-saved-view-feishu-parity-gaps.md
  - docs/assets/mockups/saved-view-vnext-mockup.html
---

# SavedView Feishu Parity Requirements

## 背景和目标

SavedView 是 DSL 列表页的视图状态载体: 保存同一业务列表上的列、排序、筛选、密度、视图类型和高级视图字段映射。它不是一个每天必须打开的独立管理页,而是列表页顶部的日常切换入口,管理页只在创建、改名、共享、审计和复制时打开。

长期目标是对齐飞书多维表格的成熟体验:

- 用户在页面标题旁直接切换当前视图,不用先进入管理页。
- 视图按个人、团队、全员分组,清晰显示默认、预置、需要配置等状态。
- 新建高级视图时先做能力判断,数据字段无法支撑时阻止半成品视图落库。
- 团队共享是高级能力,底层复用平台 `ab_team` 与 `ab_team_member`; `SavedView.teamId` 保存 team pid。
- 公共接口不暴露内部 Long id,对外统一使用 `pid`、role code 或稳定业务 code。

外部对标来源:

- Feishu: `使用多维表格视图` — https://www.feishu.cn/hc/zh-CN/articles/360049067931-%E4%BD%BF%E7%94%A8%E5%A4%9A%E7%BB%B4%E8%A1%A8%E6%A0%BC%E8%A7%86%E5%9B%BE
- Lark: `Use views in Base` — https://www.larksuite.com/hc/en-US/articles/360048488184-use-views-in-base
- Feishu: `使用多维表格的甘特视图` — https://www.feishu.cn/hc/zh-CN/articles/558830919244-%E4%BD%BF%E7%94%A8%E5%A4%9A%E7%BB%B4%E8%A1%A8%E6%A0%BC%E7%9A%84%E7%94%98%E7%89%B9%E8%A7%86%E5%9B%BE
- Feishu Open Platform: `新增视图` — https://open.feishu.cn/document/server-docs/docs/bitable-v1/app-table-view/create?lang=zh-CN

## 入口和日常场景

入口固定在列表页标题旁,与飞书表格左上角视图切换一致。用户日常只打开 dropdown:

1. 日常浏览: 打开页面后默认命中个人默认视图;若没有个人默认,按 personal/team/global 顺序选择第一可见视图。
2. 快速切换: 点击标题旁 selector,在个人、团队共享、全员视图分组中切换。
3. 临时筛选: 右上角 `我的记录`、`今日新建`、`本周修改` 保留为 quick filter chips,它们不是 SavedView 管理能力。后续可作为 preset filter 合并,但当前不和 SavedView 入口混在一起。
4. 创建/复制: dropdown 底部有 `新建视图` 与 `管理视图`;创建高级视图进入配置引导,共享/复制/审计进入管理 panel。
5. 共享视图编辑: 团队/全员/插件预置视图的普通编辑先形成本地 draft;只有具备 manage/save action 的用户确认后才写回源视图,普通成员可复制为个人视图。

## 视图类型和能力约束

| View type | 最低字段能力 | 可创建策略 | 当前交互 |
| --- | --- | --- | --- |
| table | 无特殊要求 | 直接创建 | 默认渲染表格 |
| kanban | groupable 字段 + title 字段 | 缺失则 blocked;缺少更新命令时 degraded | 提示拖拽受限 |
| calendar | 至少 1 个 date/datetime 字段 | 缺失则 blocked | 自动建议 date/title 字段 |
| gallery | image/file/attachment/avatar/cover 字段 | 缺失则 blocked | 必选图片字段 |
| gantt | start/end date 字段 | 缺失则 blocked;只有 1 个日期时 degraded | 显示映射 step |
| tree | parent/path/level 字段 | 缺失则 blocked;无重排命令时 degraded | 显示层级映射 step |
| timeline | date/resource 字段优先 | 当前允许创建,未配置时显示 setup/empty state | P2 继续收紧 |
| form | 字段集合 | 直接创建 | 表单视图渲染 |

关键原则: UI 不能创建无法渲染的半成品高级视图;后端也要二次校验,避免绕过前端。

## 权限和共享模型

SavedView scope:

- `personal`: 仅 owner 可见和管理。
- `team`: owner 或当前用户所属 team 可见;管理动作由 `actions` 和 `effectivePermission` 控制。
- `global`: 全租户可见;写回需要 `view.manage`。
- 插件预置: `viewConfig.meta.managedBy=plugin` 或 `locked=true`,默认只允许 view/copy/audit,不允许直接 edit/delete/set default。

团队共享底层:

- `CurrentUserTeamResolverImpl` 从 `MetaContext.currentUserId/currentTenantId` 解析当前用户团队。
- `TeamMemberService.getTeamPidsByUserId()` 查询 `ab_team_member` 并联到 `ab_team`。
- `SavedViewService` 用 team pid 校验 `teamId` 是否属于当前用户团队。
- 前端 `savedViewService.getMyTeams()` 调 `/api/views/my-teams`,返回 team pid/name 给创建面板。

## API 契约

公共响应统一不暴露内部 Long id:

| Surface | Public identifier | Internal id status |
| --- | --- | --- |
| SavedView | `pid` | `SavedViewDTO.id/tenantId` 被 `@JsonIgnore` |
| SavedView audit | `entityPid` | `SavedViewAuditEventDTO` 不返回 audit `id/tenantId/entityId/actorId/hash` |
| Role | `pid`, `code` | `RoleResponse` 不返回 `id/tenantId/createdBy/updatedBy/deletedFlag` |
| Tenant member | `pid`, nested user `pid` | `MemberResponse` 不返回 member/user/tenant Long id |
| User-role assignment | `pid`, `memberPid`, `rolePid` | `UserRoleResponse` 不返回 `id/memberId/roleId/tenantId` |
| E2E role setup | `memberPid + roleCodes` | `/api/user-roles/assign-by-code` 避免前端测试拿 role id |

兼容策略:

- 旧 ID-based mutation endpoints 可暂时保留用于内部兼容,但不作为新 UI 和新文档推荐契约。
- 新增或改造 UI、E2E、fixture 一律使用 pid/code。
- 非 SavedView 的动态业务数据仍有历史 `id` 返回,不纳入本次 SavedView P0/P1;见 gap 文档 P2。

## UX Mockup

Mockup 文件: `docs/assets/mockups/saved-view-vnext-mockup.html`

Mockup 覆盖:

- 标题旁 selector 作为主入口。
- dropdown 分组展示个人、团队、全员、预置/需配置状态。
- 右侧 quick filter chips 保留为日常筛选,不放进 SavedView 管理 panel。
- 管理 panel 展示 scope、team selector、能力 blocked/degraded、复制到个人、审计。

## 验收矩阵

| Priority | 验收项 | 证据 |
| --- | --- | --- |
| P0 | SavedView API 不返回 internal id | `SavedViewDTOTest`; live `/api/views/accessible` 34 条 key audit 无 `id/tenantId/entityId` |
| P0 | SavedView audit API 不返回 internal audit id | `SavedViewControllerTest.auditEvents_returnsPidOnlyPublicContract`; live audit endpoint 当前 0 rows 但无内部 key 暴露 |
| P0 | Role/Member/UserRole 响应不返回 internal id | `RoleControllerTest`, `MemberResponseTest`, `UserRoleControllerTest`; live `/api/roles/all` 8 条、`/api/user-roles` 5 条 key audit 通过 |
| P0 | 共享视图普通成员只产生本地 draft,可复制个人视图 | `saved-view-shared-draft-actions.spec.ts` 2 passed |
| P0 | 管理员共享保存必须确认后写回 | `saved-view-shared-draft-actions.spec.ts` 2 passed |
| P1 | 团队共享依赖 `ab_team/ab_team_member` 且校验成员关系 | `SavedViewServiceImplTest.create_team_validatesMembership` |
| P1 | 高级视图能力不足时 blocked,不落半成品 SavedView | `savedViewCapability.test.ts`, gallery/tree E2E; `VES-001` 验证缺 calendar date mapping 时 create API 422 |
| P1 | View selector 对齐飞书 dropdown 入口 | `ViewSelector.test.tsx`; SavedView scoped E2E 114 passed / 5 skipped / 0 failed |
| P1 | 管理 panel 支持新建、scope/team、copy、audit、locked preset | `ViewManagePanel.test.tsx`, `savedViewService.test.ts` |
| P1 | SavedView E2E 矩阵 smoke-first 后 scoped 回归 | `@smoke` 14 passed; `tests/e2e/saved-view` scoped full 114 passed / 5 skipped / 0 failed |

## E2E 覆盖说明

SavedView E2E 不是只看 pass count。覆盖按 feature/action 划分:

- 日常入口: `ViewSelector` 打开/选择/创建/管理。
- 表格状态: 列显示、排序、筛选、宽度、密度、系统字段、quick filters。
- 视图类型: table/kanban/calendar/gallery/gantt/tree/form/timeline。
- 共享和权限: global/team/personal, locked preset, shared draft, copy-to-personal, audit。
- API 契约: setup 使用 pid/code, response key audit 不含 internal id。

完成汇报必须区分单测、前端组件测试、API live audit、SavedView E2E scoped matrix。

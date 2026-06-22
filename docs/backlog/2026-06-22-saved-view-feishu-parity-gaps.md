---
type: backlog
status: active
created: 2026-06-22
relates_to:
  - docs/plans/2026-06/2026-06-22-saved-view-feishu-parity-requirements.md
  - docs/assets/mockups/saved-view-vnext-mockup.html
---

# SavedView Feishu Parity Gap Tracker

## Current Scope

本 gap 文档只跟踪 SavedView 飞书体验对齐和 pid-only 公共契约。平台全域 dynamic record 的历史 `id` 返回、其他非 SavedView audit endpoints、BPM/IM 等业务接口不纳入本轮 P0/P1。

## P0 Closure

| Gap | 状态 | 当前处理 |
| --- | --- | --- |
| SavedView response 暴露 `id/tenantId` | DONE | `SavedViewDTO` 对 `id/tenantId` 使用 `@JsonIgnore`;加 `SavedViewDTOTest` |
| SavedView audit response 暴露 audit internal fields | DONE | 新增 `SavedViewAuditEventDTO`;不返回 `id/tenantId/entityId/actorId/hash/snapshot` |
| Role response 暴露 internal id | DONE | `RoleController` 返回 `RoleResponse`;列表/详情/创建/更新统一 response DTO |
| Member response 暴露 member/user/tenant id | DONE | `MemberResponse` 对 internal Long id 使用 `@JsonIgnore`;保留 member/user pid |
| UserRole read response 暴露 `id/memberId/roleId/tenantId` | DONE | 新增 `UserRoleResponse`;公开读接口返回 `pid/memberPid/rolePid` |
| E2E setup 依赖 role/member id | DONE | 新增 `/api/user-roles/assign-by-code` 与 `/assign-by-pid`;setup 使用 `memberPid + roleCodes` |
| Shared/global 视图被普通用户直接写回 | DONE | 前端持久化策略区分 `personal-persist` 和 `shared-draft`;copy-to-personal 走独立接口 |
| 高级视图字段不足仍可创建半成品 | DONE | 前端 capability gate + 后端 `SavedViewService.validateViewTypeConfig` 双重校验 |

## P1 Closure

| Gap | 状态 | 当前处理 |
| --- | --- | --- |
| 入口像管理控件,不是飞书式日常切换 | DONE | `ViewSelector` 放在标题旁;dropdown 分组显示个人/团队/全员 |
| 不清楚右侧 quick filters 是否还需要 | DONE | 先保留;定位为日常 quick filters,不是 SavedView 主入口 |
| 团队共享依赖关系不明确 | DONE | 依赖 `ab_team` + `ab_team_member`;`SavedView.teamId` 保存 team pid |
| 插件预置/团队共享的编辑权限不清晰 | DONE | `actions/effectivePermission` 服务端下发;locked preset 只允许 view/copy/audit |
| 需要飞书式高级视图创建引导 | DONE | 新建 view type 后按字段能力进入 blocked/degraded/configured 三态 |
| 需要完整 mockup | DONE | `docs/assets/mockups/saved-view-vnext-mockup.html` |
| 需要系统性需求文档 | DONE | `docs/plans/2026-06/2026-06-22-saved-view-feishu-parity-requirements.md` |

## P2 / Deferred

| Gap | 为什么不是本轮 P0/P1 | 建议 |
| --- | --- | --- |
| Dynamic business record API 仍可能返回 `id` | 动态数据表历史契约广,列表/详情/子表/评论均可能依赖 `id` fallback;强行隐藏会破坏通用 renderer | 单独做 platform-wide public-id migration,先 inventory 再迁移 |
| 旧 ID-based UserRole mutation endpoints 仍保留 | 兼容已有脚本和后台调用;本轮重点是 public response 和新增 pid/code mutation path | 新 UI 禁用旧接口;发布后一个周期标 deprecated,再移除 |
| 其他 audit/governance endpoints 可能暴露 `AuditTrail.id` | 非 SavedView surface;不同页面可能需要审计链细节 | 建统一 `AuditTrailPublicDTO` 和 admin-only full DTO |
| View 数量上限未实现 | 飞书开放平台视图有数量限制;AuraBoot 当前没有 tenant/page-level cap | 增加 per page cap + warning + import guard |
| Team view 更细粒度 ACL | 当前 team scope = team 成员可见,manage 受全局权限/owner/action 控制;没有每个 view 的协作者 ACL | 增加 view collaborators 或 team role policy |
| Timeline capability gate 仍宽松 | 当前允许创建后在视图内显示 setup/empty state | 对 timeline 增加 date/resource 字段 required mapping |
| Quick filter preset 化 | 当前 chips 是轻量 daily filters,未沉到 SavedView preset region | 长期统一为 preset filter provider,并允许保存为个人视图 |

## E2E Truth Notes

2026-06-22 收口验证:

| 维度 | 命令/范围 | 结果 | 说明 |
| --- | --- | --- | --- |
| Backend focused contract | `./gradlew :test --tests ...UserRoleControllerTest ...RoleControllerTest ...SavedViewControllerTest ...SavedViewServiceImplTest ...SavedViewDTOTest ...MemberResponseTest` | BUILD SUCCESSFUL | 覆盖 pid-only Role/UserRole/Member/SavedView DTO, SavedView capability gate, audit DTO |
| Frontend component/unit | `pnpm vitest run app/framework/smart/components/view/__tests__/ViewSelector.test.tsx ... savedViewService.test.ts` | 6 files / 48 tests passed | 覆盖 Feishu-style selector、manage panel、capability/persistence/service |
| Auth/setup | `PW_ROLE_PROJECTS=1 npx playwright test -c playwright.oss.config.ts --project=auth --no-deps` | 4 passed | `admin/operator/viewer` storageState 均有 cookies |
| SavedView scoped E2E | `npx playwright test -c playwright.oss.config.ts --project=chromium --no-deps tests/e2e/saved-view --workers=1` | 114 passed / 5 skipped / 0 failed | P0/P1 视图切换、共享草稿、能力校验、smoke 矩阵通过;5 个 skip 来自历史 AI recommendation/fixture 条件测试 |
| Direct public-key audit | live BFF `/api/views/accessible`, `/api/views/{pid}/audit-events`, `/api/roles/all`, `/api/user-roles` | no internal key exposure found | accessible views 34 条、roles 8 条、userRoles 5 条;当前真栈 audit rows 为 0,非空 audit shape 由 controller/service tests 覆盖 |
| E2E truth audit | `tests/e2e/saved-view` grep matrix | pass count 不等于 100% UI coverage | 目录内仍有历史 GAP specs 偏 API 驱动;本轮 P0/P1 UI 主链路已用浏览器路径验证 |

报告时禁止只写 pass count;要说明哪些是 UI 路径、哪些是 API/setup、哪些属于 P2 deferred。当前可声明 P0/P1 scoped delivery 通过,不能声明 SavedView 全历史 GAP 100% UI 覆盖。

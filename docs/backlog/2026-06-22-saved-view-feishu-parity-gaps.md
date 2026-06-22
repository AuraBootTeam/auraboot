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

## P2 Progress

| Gap | 状态 | 当前处理 |
| --- | --- | --- |
| View 数量上限 | DONE in `codex/saved-view-count-limit` | 后端 `create` 路径限制手动视图数量:personal 每用户/模型/页面 10 个,team/global 每 scope/模型/页面 20 个;`duplicate`/`copyToPersonal` 继承限制,implicit autosave 不计入 |
| 旧 ID-based UserRole mutation endpoints 仍保留 | DONE in `codex/saved-view-p2-remaining` | 新增 `/remove-by-pid`、`/sync-by-pid`、`/batch-assign-by-pid`、`/batch-remove-by-pid`;旧 ID mutation endpoint 标 `@Deprecated`;E2E setup 改用 `memberPid + rolePids` |
| 其他 audit/governance endpoints 可能暴露 `AuditTrail.id` | DONE in `codex/saved-view-p2-remaining` | `/api/audit/trail`、`/by-actor`、`/by-command` 返回 `AuditTrailPublicDTO`,不暴露 `id/tenantId/entityId/actorId/actorIp/snapshot/hash`;SavedView audit DTO 增加 `sequenceNo` 作为 public key |
| Team view 更细粒度 ACL | DONE in `codex/saved-view-p2-remaining` | `viewConfig.meta.collaborators` 支持 per-view user ACL;`save` 可保存配置/设默认但不能 manage/delete/share;`manage` 才有完整管理动作 |
| Timeline capability gate 仍宽松 | DONE in `codex/saved-view-p2-remaining` | 后端 create/update/capability-check 要求 `timelineStartField + timelineResourceField`;前端 capability/ViewManagePanel/TimelineView 同步为 start/resource 必填,end 可选 |
| Quick filter preset 化 | DONE in `codex/saved-view-p2-remaining` | quick filter 统一为 preset provider;active preset 可显式保存为 personal SavedView,保存 `originPresetKey` 并切到新个人视图 |

## P2 / Deferred

| Gap | 为什么不是本轮 P0/P1 | 建议 |
| --- | --- | --- |
| Dynamic business record API 仍可能返回 `id` | 动态数据表历史契约广,列表/详情/子表/评论均可能依赖 `id` fallback;强行隐藏会破坏通用 renderer;已交由其他窗口处理 | 见 `/Users/ghj/work/auraboot/.worktrees/oss-saved-view-feishu-p1/docs/backlog/2026-06-22-platform-public-record-pid-only-migration.md`,单独做 platform-wide public-id migration,先 inventory 再迁移 |

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

2026-06-22 P2 remaining 收口验证:

| 维度 | 命令/范围 | 结果 | 说明 |
| --- | --- | --- | --- |
| Backend focused contract | `./gradlew :test --tests com.auraboot.framework.view.service.impl.SavedViewServiceImplTest --tests com.auraboot.framework.rbac.controller.UserRoleControllerTest --tests com.auraboot.framework.rbac.service.impl.UserRoleServiceImplTest --tests com.auraboot.framework.meta.controller.config.AuditTrailControllerTest --tests com.auraboot.framework.meta.service.impl.AuditTrailEventListenerTest` | BUILD SUCCESSFUL | 覆盖 UserRole PID mutation、AuditTrail public DTO、SavedView team collaborator ACL、timeline 后端 gate |
| Frontend typecheck | `pnpm typecheck` | PASS | React Router typegen + `tsc` 通过 |
| Frontend unit/component | `pnpm test:unit:run app/framework/meta/rendering/pages/__tests__/ListPageContent.test.ts app/framework/meta/rendering/pages/list/__tests__/quickFilterPresets.test.ts app/framework/meta/rendering/pages/list/__tests__/PresetViewBar.test.tsx app/framework/meta/rendering/pages/list/__tests__/dsl-list-i18n-resources.test.ts app/framework/smart/utils/__tests__/savedViewCapability.test.ts` | 5 files / 110 tests passed | 覆盖 quick preset request、save-as-personal 按钮、已有 preset personal view 幂等匹配、i18n、timeline capability、URL view restore |
| SavedView scoped E2E | `IMPORT_TEST_FIXTURES=true PW_PROFILE=oss PW_WORKERS=1 pnpm playwright test -c playwright.noweb.config.ts tests/api/setup/03-import-test-fixtures.spec.ts tests/api/setup/04-import-oss-plugins.spec.ts --project=setup --no-deps --reporter=line` + `PW_PROFILE=fast PW_WORKERS=1 pnpm playwright test -c playwright.noweb.config.ts tests/e2e/saved-view/saved-view-quick-filters.spec.ts tests/e2e/saved-view/saved-view-timeline.spec.ts --project=chromium --no-deps --reporter=line` | setup import 2/2 passed; target 13/13 passed | 隔离 runtime `saved-view-p2-e2e-79` 上验证。首轮 target 暴露 `e2et_order_list` 未导入导致页面不存在;显式导入 `test-fixtures` 后通过。后续复跑暴露 quick preset 重复保存同名失败、timeline 测试视图触达 personal 10 个上限;已改为 quick preset 已存在则切换个人视图、timeline 达上限时复用同配置历史视图,最终 13/13 passed |

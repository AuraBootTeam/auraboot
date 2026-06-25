---
type: plan-impl
status: active
created: 2026-06-25
relates_to:
  - docs/system-reference/account-organization-team-password-governance.md
  - docs/plans/2026-06/2026-06-25-account-organization-team-password-gap-plan.md
---

# 账号、组织、团队与密码治理测试矩阵

## 范围

本矩阵覆盖本轮客户交付的 Web 管理后台基础能力：

- 组织管理菜单：组织架构、职位、人员、团队、账号、角色、权限/授权关系。
- 系统管理菜单：模型服务配置入口、账号安全策略只读入口。
- 账号安全默认行为：默认管理员托管、默认关闭公开注册、未登录找回密码不按租户切策略。
- 人员、账号、租户成员、团队的生命周期边界。
- 管理员重置密码、登出服务端失效、资料编辑和头像上传。

不在本轮强承诺范围：

- Android/iOS 原生端测试。当前功能交付面是 Web 管理后台，移动端列为 N/A。
- 邀请注册、审批注册、SSO 同步、MFA、重置后强制改密完整引导。
- 完整租户级账号安全策略配置页；当前只承诺只读展示。
- 账号页“一站式新增人员 + 创建/复用用户 + 分配角色 + 开通成员”的完整向导；本轮只闭环账号页从已有人员开通成员。

## 自动化矩阵

| ID | 功能点 | 用户路径 | 数据准备 | 后端/配置测试 | Web E2E | Android E2E | iOS E2E | 证据 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| ACC-01 | 登出服务端失效 | Header 用户菜单 -> 退出登录 | 管理员登录 session | `SessionManagementServiceImplTest`, `SessionController` 单元覆盖 | `logout.spec.ts` 通过；运行态 API 证明旧 token 401 | N/A | N/A | `backend-targeted.log`, `playwright-logout-rerun2.log`, `api-02-logout-token-invalid.json` | 已验证 |
| ACC-02 | 资料编辑 | 个人资料 -> 编辑 -> 保存 | 管理员登录 | `profile.test.ts` 覆盖 API 路径和 payload | PRF E2E 通过；真浏览器截图覆盖查看、编辑、保存 | N/A | N/A | `frontend-unit.log`, `playwright-profile-ui.log`, `screenshots/ui-10-profile-view.png`, `screenshots/ui-10-profile-edit-form.png`, `screenshots/ui-10-profile-edit-saved.png` | 已验证 |
| ACC-03 | 头像上传路径 | 个人资料 -> 上传头像 | 小图片文件 | `profile.test.ts` 覆盖 `/api/user/avatar/upload` | 编辑模式上传头像返回 200，并出现成功提示 | N/A | N/A | `frontend-unit.log`, `screenshots/ui-11-avatar-upload.png`, `screenshots/ui-11-avatar-upload-run.json` | 已验证 |
| SEC-01 | 公开注册默认关闭 | 未登录访问 `/login` 和 `/signup` | 默认 `VITE_PUBLIC_REGISTRATION_ENABLED` 未开启 | `AuthControllerSelfServicePasswordTest.register_returnsErrorWhenPublicRegistrationDisabled` | `auth-recovery-and-signup.spec.ts` 通过；截图和 API 已保存 | N/A | N/A | `playwright-auth-recovery.log`, `screenshots/ui-01-login-no-register.png`, `screenshots/ui-02-signup-disabled-redirect.png`, `api-01-register-disabled.json` | 已验证 |
| SEC-02 | 自助密码默认关闭 | 未登录找回密码 / token reset | 默认 `security.password.self-service-enabled=false` | `AuthControllerSelfServicePasswordTest` 自助密码相关用例 | `auth-recovery-and-signup.spec.ts` 验证入口/策略 | N/A | N/A | `backend-targeted.log`, `playwright-auth-recovery.log` | 已验证 |
| SEC-03 | 管理员直接重置用户密码校验复杂度 | 用户管理 -> 重置密码 | 管理员权限 | `AdminUserControllerPasswordResetTest`, `PasswordManagementServiceImplTest` 通过 | 非本轮主 UI 截图点，由后端测试支撑 | N/A | N/A | `backend-targeted.log` | 已验证 |
| SEC-04 | 管理员重置租户成员密码 | 组织管理 -> 账号 -> 更多 -> 重置密码 | 至少一个租户成员 | `TenantMemberCommandHandlerTest`, `TenantMemberApplicationServiceImplTest` 通过；覆盖临时密码不满足策略时重试生成 | UI 点击更多菜单重置密码，确认后展示一次性临时密码；E2E 通过并刷新截图 | N/A | N/A | `backend-targeted.log`, `backend-tenant-member-after-temp-password-fix.log`, `api-04-member-reset-password.json`, `screenshots/ui-07a-member-reset-confirm.png`, `screenshots/ui-07-member-reset-temp-password.png`, `playwright-final-nonlogout.log`, `playwright-member-reset-final.log` | 已验证 |
| SEC-05 | 账号安全策略只读展示 | 系统管理 -> 账号安全策略 | 管理员登录，插件配置已导入 | `AccountSecurityPolicyControllerTest` 通过 | `account-policy-and-employee-open-account.spec.ts POLICY-001` 通过；截图覆盖只读策略页 | N/A | N/A | `api-05-account-security-policy.json`, `screenshots/ui-14-account-security-policy.png`, `playwright-policy-open-account.log` | 已验证 |
| ORG-01 | 组织管理菜单完整 | Sidebar -> 组织管理 | 插件配置已导入 | `plugins/org-management/tests/menus-config.test.mjs` 通过 | 侧边栏截图包含组织管理及人员/团队/账号等入口 | N/A | N/A | `org-menu-config.log`, `screenshots/ui-03-org-employee-menu.png` | 已验证 |
| ORG-02 | 系统管理模型服务入口 | Sidebar -> 系统管理 -> 模型服务 | platform-admin 配置已导入 | `plugins/platform-admin/tests/menus-config.test.mjs` 通过 | 截图显示系统管理下模型服务入口和 LLM Providers 页面 | N/A | N/A | `platform-admin-menu-config.log`, `screenshots/ui-04-system-llm-provider.png` | 已验证 |
| ORG-03 | 新增人员可不绑定登录账号 | 组织管理 -> 人员 -> 新增 -> 保存 | 部门、职位已存在 | `menus-config.test.mjs` 检查 `org_emp_user_id.required=false` | `org-employee.spec.ts` 不选择 `org_emp_user_id` 仍保存成功；表单截图显示系统用户非必填 | N/A | N/A | `playwright-org-employee-rerun2.log`, `screenshots/ui-05-employee-create-user-optional.png` | 已验证 |
| ORG-04 | 人员编辑 | 组织管理 -> 人员 -> 编辑 | E2E 创建人员 | `org:update_employee` API 断言 | `org-employee.spec.ts ORG-012` 通过 | N/A | N/A | `playwright-org-employee-rerun2.log` | 已验证 |
| ORG-05 | 人员状态流转 | 组织管理 -> 人员 -> 状态变更 | E2E 创建人员 | `org:update_employee` API 断言 | `org-employee.spec.ts ORG-013` 通过 | N/A | N/A | `playwright-org-employee-rerun2.log` | 已验证 |
| ORG-06 | 人员页开通账号 | 组织管理 -> 人员 -> 行操作开通账号 | 未绑定账号的人员 | `OrgEmployeeServiceImplTest`, `OrgEmployeeCommandHandlerTest`, `menus-config.test.mjs` 通过 | `account-policy-and-employee-open-account.spec.ts ORG-OPEN-001` 通过；确认弹窗和临时密码弹窗截图已保存 | N/A | N/A | `screenshots/ui-15-employee-open-account-confirm.png`, `screenshots/ui-15-employee-open-account-temp-password.png`, `playwright-policy-open-account.log` | 已验证 |
| MEM-01 | 账号/租户成员列表入口 | 组织管理 -> 账号 | 至少一个租户成员 | `TenantMemberCommandHandlerTest` | 成员列表运行态截图可达 | N/A | N/A | `screenshots/ui-06-tenant-member-list.png` | 已验证 |
| MEM-02 | 成员详情状态和动作 | 账号列表 -> 成员操作 | 至少一个租户成员 | `TenantMemberApplicationServiceImplTest.updateMemberStatusDispatches` | 列表动作可见暂停、更多菜单、离职、重置密码、删除；重置密码 E2E 通过 | N/A | N/A | `backend-targeted.log`, `screenshots/ui-06-tenant-member-list.png`, `screenshots/ui-07-member-reset-temp-password.png`, `screenshots/ui-08-member-leave-confirm.png` | 已验证 |
| MEM-03 | 成员离职/停用不禁用全局用户 | 成员列表 -> 更多 -> 离职 | 测试成员 | `TenantMemberApplicationServiceImplTest` 断言人员离职标记、session 撤销、不禁用全局用户 | UI 离职确认弹窗已截图；API 触发离职后查询成员 inactive、人员 resigned、全局用户 enabled；列表截图可见离职结果 | N/A | N/A | `backend-targeted.log`, `api-03-member-offboarding.json`, `screenshots/ui-08-member-leave-confirm.png`, `screenshots/ui-03-org-employee-menu.png`, `screenshots/ui-06-tenant-member-list.png` | 已验证 |
| MEM-04 | 账号页从已有人员开通成员 | 组织管理 -> 账号 -> 从人员开通账号 | 已有未绑定账号的人员 | `TenantMemberCommandHandlerTest` 覆盖 `admin:provision_member_from_employee`；`plugins/platform-admin/tests/menus-config.test.mjs` 覆盖 command、toolbar inputFields 和 handler bindingRule | `account-policy-and-employee-open-account.spec.ts MEM-04` 通过；人员选择弹窗和临时密码弹窗截图已保存 | N/A | N/A | `backend-targeted.log`, `platform-admin-menu-config.log`, `api-07-account-page-provision-from-employee.json`, `screenshots/ui-16-account-provision-from-employee-form.png`, `screenshots/ui-16-account-provision-from-employee-temp-password.png`, `playwright-policy-open-account.log`, `playwright-final-nonlogout.log` | 已验证 |
| TEAM-01 | 团队列表和创建 | 组织管理 -> 团队 -> 新建 | 管理员登录 | org team API 测试由 E2E 支撑 | `team-management.spec.ts TM-001/TM-002` 通过；运行态团队页可达 | N/A | N/A | `playwright-team-rerun.log`, `screenshots/ui-09-team-management.png` | 已验证 |
| TEAM-02 | 团队成员添加/移除 | 团队详情 -> 添加成员 -> 移除成员 | 至少一个成员 | org team API 由 E2E 创建/查询 | `team-management.spec.ts TM-006`, `team-management-deep.spec.ts TM-010/TM-011` 通过；运行态团队详情截图包含成员 | N/A | N/A | `playwright-team-rerun.log`, `screenshots/ui-09-team-members.png`, `screenshots/ui-09-team-members-run.json` | 已验证 |
| PERM-01 | 角色和权限入口 | 组织管理 -> 角色 / 权限授权关系 | 菜单配置已导入 | 菜单配置测试 | 侧边栏截图可见角色、权限/授权关系；目标权限中心页面可达 | N/A | N/A | `org-menu-config.log`, `screenshots/ui-03-org-employee-menu.png`, `screenshots/ui-13-role-permission-entry.png` | 已验证 |

## 命令矩阵

| Gate | 命令 | 目标 | 证据文件 |
| --- | --- | --- | --- |
| 后端 targeted | `cd platform && ./gradlew :test --tests ...` | 覆盖账号安全、密码、session、租户成员状态 | `docs/plans/2026-06/evidence/account-org-password/latest/backend-targeted.log` |
| 插件配置 | `node --test plugins/org-management/tests/menus-config.test.mjs` / `node --test plugins/platform-admin/tests/menus-config.test.mjs` | 组织菜单、人员账号解耦、人员开通账号命令、账号页从人员开户 command/action/bindingRule | `docs/plans/2026-06/evidence/account-org-password/latest/org-menu-config.log`, `docs/plans/2026-06/evidence/account-org-password/latest/platform-admin-menu-config.log` |
| JSON 配置 | `node -e "...JSON.parse..."` | 平台/组织插件 JSON 可解析 | `docs/plans/2026-06/evidence/account-org-password/latest/json-parse.log` |
| 前端单测 | `cd web-admin && pnpm vitest ...` | session/profile/action handler/i18n 行为 | `docs/plans/2026-06/evidence/account-org-password/latest/frontend-unit.log` |
| 前端构建/类型 | `cd web-admin && pnpm typecheck` 或项目等价脚本 | 验证 `import.meta.env` 和前端改动可编译 | `docs/plans/2026-06/evidence/account-org-password/latest/frontend-typecheck.log` |
| OSS 环境 | `./scripts/oss-golden-stack.sh up account-org-settings --slot <free> --plugin-profile demo` | 启动隔离 host-first OSS 栈并导入配置 | `docs/plans/2026-06/evidence/account-org-password/latest/oss-golden-stack-up.log` |
| Web E2E targeted | `cd web-admin && eval "$(../scripts/oss-golden-stack.sh env account-org-settings)" && npx playwright test -c playwright.gt5.config.ts --project=chromium ...` | 跑组织、团队、注册关闭、成员重置密码、账号安全策略、人员开通账号、账号页从人员开通成员；登出单独顺序跑，避免登出撤销共享 session 影响其他用例 | `playwright-final-nonlogout.log`, `playwright-final-logout.log`, `playwright-policy-open-account.log`, `playwright-policy-open-account-mem04.log` |
| 截图验证 | Playwright/browser 截图 | 证明菜单、注册关闭、人员表单、重置密码、登出等运行态可见 | `docs/plans/2026-06/evidence/account-org-password/latest/screenshots/` |

## 最终验证记录

2026-06-25 在重置后的 OSS 隔离环境 `account-org-settings` slot `94` 上刷新：

- `backend-targeted.log`：后端 targeted `BUILD SUCCESSFUL`，覆盖账号安全策略、密码策略、session、租户成员命令、人员开通账号、成员重置临时密码策略重试。
- `frontend-unit.log`：5 个前端单测文件、51 个测试通过。
- `frontend-typecheck.log`：`react-router typegen && tsc` 通过。
- `org-menu-config.log`：组织管理菜单与人员开通账号配置 3/3 通过。
- `platform-admin-menu-config.log`：模型服务、账号安全策略、账号页从人员开户 command/action/bindingRule 配置 4/4 通过。
- `json-parse.log`：58 个 org-management/platform-admin 配置 JSON 解析通过。
- `playwright-policy-open-account.log`：账号安全策略、人员页开通账号、账号页从人员开通成员 19/19 通过。
- `playwright-policy-open-account-mem04.log`：账号页从人员开通成员单用例 17/17 通过，并刷新 UI-16 截图。
- `playwright-final-nonlogout.log`：非登出 UI targeted 34/34 通过。
- `playwright-final-logout.log`：登出 UI targeted 20/20 通过。
- `playwright-member-reset-final.log`：成员重置单 spec 17/17 通过，并刷新 `ui-07a/ui-07` 截图。
- `oss-golden-stack-up-final.log`：destroy 后重新 up，后端、前端、插件导入、warm 均完成。

## 完成判定

只有同时满足以下条件，才能把 gap 文档状态从“待验证”改为“已验证”：

1. OSS 隔离环境启动成功，记录实际端口和 runtime 名称。
2. 后端 targeted、插件配置、JSON 解析、前端单测或等价编译检查有新鲜输出。
3. Web targeted E2E 至少覆盖组织菜单、人员新增不绑定账号、人员开通账号、账号页从人员开通成员、团队成员、成员详情动作、注册关闭、账号安全策略。
4. 截图证据覆盖登录页无注册入口、组织菜单、系统模型服务入口、账号安全策略页、人员表单系统用户非必填、人员开通账号确认/临时密码弹窗、账号页从人员开户选择弹窗/临时密码弹窗、成员列表、成员重置密码确认/临时密码弹窗、团队入口。
5. 所有未完成项在 gap 文档中保留为明确 P1/P2，不得用“已完成”描述。

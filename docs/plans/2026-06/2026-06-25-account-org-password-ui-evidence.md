---
type: test-evidence
status: active
created: 2026-06-25
relates_to:
  - docs/plans/2026-06/2026-06-25-account-org-password-test-matrix.md
---

# 账号、组织、团队与密码治理 UI 证据记录

## 运行环境

| 项 | 值 |
| --- | --- |
| Worktree | `/Users/ghj/work/auraboot-core-account-org-settings` |
| Branch | `codex/account-org-settings-closure` |
| Runtime | `account-org-settings` |
| Slot | `94` |
| Backend URL | `http://127.0.0.1:6494` |
| BFF URL | `http://127.0.0.1:6194` |
| Vite URL | `http://127.0.0.1:5194` |
| Evidence root | `docs/plans/2026-06/evidence/account-org-password/latest` |

## 真浏览器 UI 用例

| ID | 用例 | 步骤 | 预期 | 截图/证据 | 状态 |
| --- | --- | --- | --- | --- | --- |
| UI-01 | 登录页默认无公开注册入口 | 访问 `/login` | 页面不展示普通注册入口；仍可登录管理员 | `screenshots/ui-01-login-no-register.png` | 已执行 |
| UI-02 | `/signup` 不能绕过注册关闭 | 直接访问 `/signup` | 回到 `/login` 或展示禁用态；不能提交注册 | `screenshots/ui-02-signup-disabled-redirect.png` | 已执行 |
| UI-03 | 组织管理菜单完整 | 登录后展开侧边栏 `组织管理` | 显示组织架构、职位、人员、团队、账号、角色、权限/授权关系 | `screenshots/ui-03-org-employee-menu.png` | 已执行 |
| UI-04 | 系统管理包含模型服务 | 展开 `系统管理` | 显示模型服务/LLM provider 配置入口 | `screenshots/ui-04-system-llm-provider.png` | 已执行 |
| UI-05 | 人员表单系统用户非必填 | 进入 `人员` 新增页 | `系统用户/登录账号` 不显示必填态；不选择系统用户也可保存人员 | `screenshots/ui-05-employee-create-user-optional.png` | 已执行 |
| UI-06 | 账号/成员入口可达 | 进入 `组织管理 -> 账号` | 成员列表加载，有成员行或空态，操作区可见 | `screenshots/ui-06-tenant-member-list.png` | 已执行 |
| UI-07 | 管理员重置成员密码闭环 | 成员行操作更多菜单 -> 重置密码 -> 确认 | 弹窗显示一次性临时密码，提示只显示一次；临时密码生成满足部署级复杂度策略 | `screenshots/ui-07a-member-reset-confirm.png` + `screenshots/ui-07-member-reset-temp-password.png` + `screenshots/ui-07-member-reset-ui-run.json` + `api-04-member-reset-password.json` + `playwright-final-nonlogout.log` + `playwright-member-reset-final.log` | 已执行 |
| UI-08 | 成员离职/停用动作 | 成员列表 -> 更多 -> 离职 | 二次确认后成员状态变化；不禁用全局用户 | `screenshots/ui-08-member-leave-confirm.png` + `api-03-member-offboarding.json` + `screenshots/ui-03-org-employee-menu.png` + `screenshots/ui-06-tenant-member-list.png` | 已执行 |
| UI-09 | 团队成员维护 | 团队详情 -> 添加成员 -> 移除成员 | 成员列表新增后可移除，刷新后状态一致 | `screenshots/ui-09-team-management.png` + `screenshots/ui-09-team-members.png` + `playwright-team-rerun.log` | 已执行 |
| UI-10 | 资料编辑 | 个人资料 -> 编辑 -> 保存 | 修改资料刷新后保留 | `screenshots/ui-10-profile-view.png` + `screenshots/ui-10-profile-edit-form.png` + `screenshots/ui-10-profile-edit-saved.png` + `playwright-profile-ui.log` | 已执行 |
| UI-11 | 头像上传 | 个人资料 -> 上传头像 | 请求走 `/api/user/avatar/upload`，头像刷新显示 | `screenshots/ui-11-avatar-upload.png` + `screenshots/ui-11-avatar-upload-run.json` | 已执行 |
| UI-12 | 登出服务端失效 | 登录后记录受保护接口成功 -> 退出登录 -> 复用旧 token 调接口 | 退出后旧 token 返回未登录或 session 无效 | `api-02-logout-token-invalid.json` + `playwright-logout-rerun2.log` | 已执行 |
| UI-13 | 角色/权限入口可达 | 组织管理 -> 角色 / 权限授权关系 | 进入权限中心页面 | `screenshots/ui-13-role-permission-entry.png` + `screenshots/ui-13-role-permission-entry-run.json` | 已执行 |
| UI-14 | 账号安全策略只读页 | 系统管理 -> 账号安全策略 | 页面展示管理员托管、公开注册关闭、自助密码关闭、复杂度、历史密码、过期和锁定等策略事实 | `screenshots/ui-14-account-security-policy.png` + `api-05-account-security-policy.json` + `playwright-policy-open-account.log` | 已执行 |
| UI-15 | 人员页开通账号 | 组织管理 -> 人员 -> 未绑定人员行操作开通账号 -> 确认 | 确认后创建/复用用户和租户成员，绑定人员；新建用户时弹窗展示一次性临时密码 | `screenshots/ui-15-employee-open-account-confirm.png` + `screenshots/ui-15-employee-open-account-temp-password.png` + `playwright-policy-open-account.log` | 已执行 |
| UI-16 | 账号页从已有人员开通成员 | 组织管理 -> 账号 -> 从人员开通账号 -> 选择人员 -> 确认 | 确认后进入 `admin:provision_member_from_employee` handler，创建/复用用户和租户成员，绑定人员；新建用户时弹窗展示一次性临时密码 | `screenshots/ui-16-account-provision-from-employee-form.png` + `screenshots/ui-16-account-provision-from-employee-temp-password.png` + `api-07-account-page-provision-from-employee.json` + `playwright-policy-open-account-mem04.log` + `playwright-policy-open-account.log` | 已执行 |

## API/后端证据

| ID | 验证项 | 命令/接口 | 证据 | 状态 |
| --- | --- | --- | --- | --- |
| API-01 | 匿名注册 API 默认拒绝 | `POST /api/auth/register` | `api-01-register-disabled.json` | 已执行 |
| API-02 | 登出后旧 token 无效 | `DELETE /api/user/sessions/current` 后复用 bearer token | `api-02-logout-token-invalid.json` | 已执行 |
| API-03 | 成员状态变更不禁用全局用户 | 更新成员状态后查询成员、人员、用户 | `api-03-member-offboarding.json` | 已执行 |
| API-04 | 管理员重置成员密码返回临时密码 | 执行 `admin:reset_member_password` | `api-04-member-reset-password.json` | 已执行 |
| API-05 | 账号安全策略读取 | `GET /api/admin/account-security-policy` | `api-05-account-security-policy.json` | 已执行 |
| API-06 | 菜单包含账号安全策略和人员开通账号运行前置 | `GET /api/menu/user` | `api-06-menu-after-policy-open-account.json` | 已执行 |
| API-07 | 账号页 schema 包含从人员开户入口 | `GET /api/pages/key/tenant_member_list` | `api-07-account-page-provision-from-employee.json` | 已执行 |

## 执行记录

已保存：

- `oss-golden-stack-up.log`
- `backend-targeted.log`
- `org-menu-config.log`
- `platform-admin-menu-config.log`
- `json-parse.log`
- `frontend-unit.log`
- `frontend-typecheck.log`
- `playwright-auth-storage.log`
- `playwright-auth-recovery.log`
- `playwright-org-employee-rerun2.log`
- `playwright-logout-rerun2.log`
- `playwright-team-rerun.log`
- `playwright-member-reset-ui.log`
- `playwright-policy-open-account-mem04.log`
- `playwright-policy-open-account.log`
- `playwright-final-nonlogout.log`
- `playwright-final-logout.log`
- `playwright-member-reset-final.log`
- `playwright-profile-ui.log`
- `backend-tenant-member-after-temp-password-fix.log`
- `oss-golden-stack-up-final.log`
- `preflight-health.log`
- `api-01-register-disabled.json`
- `api-02-logout-token-invalid.json`
- `api-03-member-offboarding.json`
- `api-04-member-reset-password.json`
- `api-05-account-security-policy.json`
- `api-06-menu-after-policy-open-account.json`
- `api-07-account-page-provision-from-employee.json`
- `screenshots/*.png`

重要缺口：

- 账号页从已有人员开通成员已完成 UI-16 运行态验证。更大的“账号页一站式新增人员 + 创建/复用用户 + 分配角色 + 开通成员”向导仍是后续增强，不属于本轮已验证截图路径。
- 账号安全策略当前是只读展示；租户级行为开关可编辑化、邀请注册、SSO 同步、重置后强制改密仍属后续迭代。
- 离职 UI 已有确认弹窗与 API 落库证据；若客户现场要求完整 UI 执行到底，需要准备一次性演示成员，避免破坏复用测试账号。

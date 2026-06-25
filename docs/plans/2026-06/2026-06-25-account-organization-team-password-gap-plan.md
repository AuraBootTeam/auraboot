---
type: plan-impl
status: active
created: 2026-06-25
relates_to:
  - docs/system-reference/account-organization-team-password-governance.md
---

# 账号、组织、团队与密码治理 Gap 计划

## 背景与决策

本轮讨论确认的产品边界已经落入长期 SOT：`docs/system-reference/account-organization-team-password-governance.md`。

核心决策如下：

- 密码复杂度、历史密码、过期、锁定、reset token 有效期保持部署级规则，不按租户拆分，避免一个跨租户用户面对互相冲突的密码规则。
- 租户级只控制账号行为开关，例如登录方式、自助密码、管理员重置、重置后是否强制改密、MFA/SSO 等。
- 未登录找回密码不碰租户策略；登录后个人改密和管理员重置这类有明确租户上下文的动作，才读取租户行为策略。
- 公开注册默认关闭。企业 SaaS 默认不是让匿名用户自行注册进租户，而是由管理员通过人员档案、成员导入、人员开通账号、账号页从人员开通、邀请或 SSO 同步等受控入口开户。
- `人员` 是组织档案，不等于登录账号；`账号/租户成员` 是登录后在租户内的身份和权限载体；`团队` 是跨部门协作分组，和组织架构平行。
- 离职是当前租户内的人员和成员生命周期动作，不默认禁用或删除全局用户账号。

## 当前交付口径

本轮交付按“基础账号闭环”验收，当前可以对客户说明的能力是：

- 菜单能进入 `组织架构 / 职位 / 人员 / 团队 / 账号 / 角色 / 权限/授权关系`。
- 菜单能进入系统设置相关入口，包括 `模型服务` 和只读 `账号安全策略`。
- 公开注册默认关闭，登录页不展示普通注册入口，匿名注册 API 默认拒绝。
- 人员档案可以先创建，不强制绑定登录账号。
- 人员页支持从已有人员行开通账号：创建/复用全局用户、创建/复用当前租户成员、绑定人员与成员；新建用户时一次性展示临时密码。
- 账号页支持成员列表、搜索、导入、状态流转、移除和管理员重置密码。
- 账号页从已有人员开通成员已闭环，管理员不离开账号页也能从组织人员档案开户。
- 当前可交付开户路径是人员页开通账号和成员导入。
- 管理员重置成员密码会返回一次性临时密码，前端弹窗展示并尝试复制。
- 管理员重置成员密码的临时密码会重试生成直到满足部署级密码复杂度策略，避免随机串偶发不含数字/小写等导致重置失败。
- 登出会服务端撤销当前 session，再清理前端 session。
- 成员停用/离职会联动当前租户人员状态和 session，不禁用全局用户账号。
- 团队支持创建、添加成员、移除成员；团队不承担创建人员或创建账号的主入口。

仍不能对客户承诺为已完整上线的能力是：

- 账号页“一站式新增人员 + 创建/复用用户 + 分配角色 + 开通成员”的完整向导。
- 邀请注册、审批注册、企业邮箱域加入、SSO/IdP 同步开户。
- 账号安全策略的租户级可编辑配置页。
- 管理员重置后强制改密的完整登录引导。
- 租户维度 session 分区后的精确 session 撤销。
- 角色和权限/授权关系入口的 tab 化或去重。

## 本轮已闭环

| 领域 | 当前状态 | 主要文件 |
| --- | --- | --- |
| 组织管理菜单 | 恢复 `组织架构 / 职位 / 人员 / 团队 / 账号 / 角色 / 权限/授权关系` | `plugins/org-management/config/menus.json` |
| 系统管理菜单 | 新增 `模型服务` 和 `账号安全策略` 入口 | `plugins/platform-admin/config/menus.json` |
| 账号安全策略只读页 | 展示管理员托管、公开注册关闭、自助密码关闭、复杂度、历史密码、过期、锁定等当前事实 | `AccountSecurityPolicyController.java`, `account-security-policy.tsx` |
| 管理员重置成员密码 | 租户成员命令返回一次性临时密码，前端弹窗展示并复制 | `TenantMemberCommandHandler.java`, `TenantMemberApplicationServiceImpl.java`, `useActionHandler.ts` |
| 管理员直接重置用户密码 | 增加权限保护，并校验密码复杂度策略 | `AdminUserController.java`, `PasswordManagementServiceImpl.java` |
| 登出 | 前端登出调用服务端撤销当前 session，再清理 cookie | `SessionController.java`, `SessionManagementServiceImpl.java`, `session.ts` |
| 资料编辑 | 头像上传路径修正到后端实际接口 | `profile.ts` |
| 公开注册 | 默认关闭公开注册，登录页/注册页默认隐藏入口，匿名注册 API 默认拒绝 | `SystemModeServiceImpl.java`, `AuthController.java`, `Login.tsx`, `AuthHeader.tsx`, `SignUp.tsx` |
| 新增人员 | 人员档案不强制绑定登录账号，支持先建组织人员档案 | `org_employee.json`, `org_emp_user_id.json`, `org_employee_form.json` |
| 人员开通账号 | 人员行操作创建/复用用户和租户成员，绑定人员与成员，新建用户时返回临时密码 | `OrgEmployeeServiceImpl.java`, `OrgEmployeeCommandHandler.java`, `org_open_employee_account.json`, `org_employee_list.json` |
| 租户成员维护 | 成员列表具备搜索、导入、状态流转、移除、管理员重置密码基础能力 | `TenantMemberApplicationServiceImpl.java`, `plugins/platform-admin/config/pages.json` |
| 账号页从人员开户 | 账号页 toolbar 选择已有人员后执行 `admin:provision_member_from_employee`，进入 `tenantMemberCommandHandler`，复用人员开通账号逻辑并展示临时密码 | `TenantMemberCommandHandler.java`, `TenantMemberCommandHandlerTest.java`, `plugins/platform-admin/config/pages.json`, `plugins/platform-admin/config/bindingRules.json`, `useActionHandler.ts` |
| 成员离职/停用 | 成员 inactive 时标记已绑定人员离职；inactive/suspended 时撤销 session；不禁用全局用户 | `TenantMemberApplicationServiceImpl.java` |
| 团队成员 | 团队创建、成员添加、成员移除已有 E2E 和运行态截图证据 | `web-admin/tests/e2e/organization/team.spec.ts` |

## 生命周期矩阵

| 场景 | 入口 | 应有行为 | 当前判断 |
| --- | --- | --- | --- |
| 新增人员，不开账号 | `人员` | 创建 `org_employee`，维护部门、职位、汇报关系、联系方式，不创建登录身份 | 已闭环 |
| 人员绑定/开通账号 | `人员` 行操作 | 创建或复用 `ab_user`，创建或复用当前租户 `ab_tenant_member`，绑定人员与成员，必要时返回临时密码 | 已闭环 |
| 新增/维护成员 | `账号` | 查询成员、导入成员、状态流转、移除、管理员重置密码 | 基础维护已闭环 |
| 账号页从已有人员开户 | `账号` toolbar | 选择已有未绑定人员，复用人员开通账号逻辑，创建/复用用户和成员，绑定人员与成员，必要时返回临时密码 | 已闭环 |
| 账号页一站式新增并绑定人员 | `账号` 后续入口 | 新建或复用人员、创建/复用全局用户、创建成员、分配角色、生成临时密码或邀请 | 后续增强 |
| 成员离职 | `人员` 或 `账号` | 标记当前租户人员离职、成员停用，撤销当前会话，不禁用全局用户 | API 已闭环，UI 有确认弹窗；破坏性 UI 完整执行需演示数据 |
| 公开注册 | 登录页/API | 默认关闭，登录页不展示注册，匿名注册 API 拒绝 | 已闭环 |
| 受控加入 | 导入/人员开通/账号页从人员开户/邀请/SSO | 管理员导入、人员开通、账号页从已有人员开户、邀请注册、SSO 同步 | 导入、人员开通、账号页从人员开户已闭环；邀请/SSO 未闭环 |
| 团队协作 | `团队` | 创建团队、添加已有成员、移除成员 | 已闭环 |

## 当前验证记录

运行环境：

- OSS 隔离环境：`account-org-settings`
- slot：`94`
- backend：`http://127.0.0.1:6494`
- Vite：`http://127.0.0.1:5194`
- BFF：`http://127.0.0.1:6194`
- 证据目录：`docs/plans/2026-06/evidence/account-org-password/latest/`

已通过的关键验证：

- 后端 targeted Gradle 测试覆盖注册默认关闭、自助密码、管理员重置、session 撤销、租户成员命令、成员状态流转、人员开通账号、账号安全策略 API。
- `plugins/org-management/tests/menus-config.test.mjs` 通过，覆盖组织管理菜单、人员可先于账号创建、人员开通账号命令和 bindingRule。
- `plugins/platform-admin/tests/menus-config.test.mjs` 通过，覆盖系统管理下模型服务、账号安全策略入口、账号页从人员开户 command/action/bindingRule。
- 关键 JSON 配置递归解析通过。
- 前端 targeted vitest 通过，覆盖 session/profile/action handler/i18n 和 command inputFields。
- `pnpm typecheck` 通过。
- Playwright targeted 通过：auth storage、注册关闭、自助恢复默认关闭、组织人员、登出、团队、账号安全策略、人员开通账号、账号页从人员开通成员。
- 运行态 API/UI 证据通过：匿名注册默认拒绝、登出后旧 token 无效、管理员重置成员密码返回并展示临时密码、成员离职联动人员且保留全局用户、账号安全策略只读页、人员开通账号临时密码弹窗、账号页从人员开通成员临时密码弹窗。
- 复测中发现并修复管理员重置成员密码随机临时密码不稳定满足复杂度的问题；修复后后端 targeted 和运行态成员重置 UI 均通过。

新增证据：

- 账号安全策略 API：`docs/plans/2026-06/evidence/account-org-password/latest/api-05-account-security-policy.json`
- 菜单 API：`docs/plans/2026-06/evidence/account-org-password/latest/api-06-menu-after-policy-open-account.json`
- 账号安全策略 UI：`docs/plans/2026-06/evidence/account-org-password/latest/screenshots/ui-14-account-security-policy.png`
- 人员开通账号确认弹窗：`docs/plans/2026-06/evidence/account-org-password/latest/screenshots/ui-15-employee-open-account-confirm.png`
- 人员开通账号临时密码弹窗：`docs/plans/2026-06/evidence/account-org-password/latest/screenshots/ui-15-employee-open-account-temp-password.png`
- 账号页从人员开户页面 schema：`docs/plans/2026-06/evidence/account-org-password/latest/api-07-account-page-provision-from-employee.json`
- 账号页从人员开户选择弹窗：`docs/plans/2026-06/evidence/account-org-password/latest/screenshots/ui-16-account-provision-from-employee-form.png`
- 账号页从人员开户临时密码弹窗：`docs/plans/2026-06/evidence/account-org-password/latest/screenshots/ui-16-account-provision-from-employee-temp-password.png`
- Playwright 日志：`docs/plans/2026-06/evidence/account-org-password/latest/playwright-policy-open-account.log`
- MEM-04 单用例日志：`docs/plans/2026-06/evidence/account-org-password/latest/playwright-policy-open-account-mem04.log`
- 最终非登出 UI targeted：`docs/plans/2026-06/evidence/account-org-password/latest/playwright-final-nonlogout.log`
- 最终登出 UI targeted：`docs/plans/2026-06/evidence/account-org-password/latest/playwright-final-logout.log`
- 最终成员重置截图刷新：`docs/plans/2026-06/evidence/account-org-password/latest/playwright-member-reset-final.log`
- 临时密码策略修复后端验证：`docs/plans/2026-06/evidence/account-org-password/latest/backend-tenant-member-after-temp-password-fix.log`

已有核心证据：

- 登录页无公开注册入口：`screenshots/ui-01-login-no-register.png`
- `/signup` 默认回登录页：`screenshots/ui-02-signup-disabled-redirect.png`
- 匿名注册 API 默认拒绝：`api-01-register-disabled.json`
- 组织/人员菜单：`screenshots/ui-03-org-employee-menu.png`
- 系统模型服务入口：`screenshots/ui-04-system-llm-provider.png`
- 人员表单系统用户非必填：`screenshots/ui-05-employee-form-user-optional.png`
- 成员重置密码确认弹窗：`screenshots/ui-07a-member-reset-confirm.png`
- 成员重置密码临时密码弹窗：`screenshots/ui-07-member-reset-temp-password.png`
- 成员离职 API：`api-03-member-offboarding.json`
- 团队成员截图：`screenshots/ui-09-team-members.png`

## 已知 Gap

### P0：目标交付环境必须重新导入插件配置

本 worktree 已在 OSS 隔离栈 `account-org-settings` slot `94` 验证插件导入和菜单生效。客户目标环境如果没有重新导入插件配置，仍可能看到旧菜单、旧命令或旧页面。

交付动作：

- 目标环境重新导入 `plugins/org-management` 和 `plugins/platform-admin` 配置。
- 重新登录或刷新前端菜单。
- 验证 `系统管理 -> 账号安全策略`、`组织管理 -> 人员 -> 开通账号` 行操作是否可见。

### 已闭环：账号页从已有人员开通成员

关闭公开注册后，账号页直接开户是管理员最自然的日常入口之一。为避免一次性做成过大的“新增人员 + 创建账号 + 分配角色”向导，本迭代先收敛成更小的闭环：账号页选择已有未绑定人员，然后复用 `人员 -> 开通账号` 逻辑完成开户。

当前状态：

- 后端 `admin:provision_member_from_employee` 命令复用 `OrgEmployeeService.openAccount(employeePid)`。
- `plugins/platform-admin/config/bindingRules.json` 已把命令绑定到 `tenantMemberCommandHandler`。
- 账号页 toolbar/action 使用通用 command `inputFields`，人员选择从 `/api/org/employees?pageNum=1&pageSize=500` 拉取。
- `plugins/platform-admin/tests/menus-config.test.mjs` 覆盖 command、toolbar inputFields 和 handler bindingRule。
- 真浏览器 E2E `MEM-04` 覆盖 `组织管理 -> 账号 -> 从人员开通账号 -> 选择人员 -> 提交 -> 临时密码弹窗`。
- 截图证据已保存：`ui-16-account-provision-from-employee-form.png`、`ui-16-account-provision-from-employee-temp-password.png`。

仍不包含的增强项：

- 一站式创建或复用全局 `ab_user`。
- 账号页内新建人员档案并绑定部门、职位、团队。
- 可分配角色，并生成临时密码或走邀请。
- 冲突提示覆盖：已有全局用户但不属于当前租户、已有租户成员但未绑定人员、已有人员但无账号、人员已离职、邮箱/手机号重复。

### P1：关闭注册后的受控加入方式仍需路线化

当前已经明确关闭的是“匿名公开注册”，不是关闭所有开户能力。为了客户沟通不产生歧义，成员来源按如下路线推进：

| 入口 | 本轮状态 | 说明 |
| --- | --- | --- |
| 人员档案 | 已闭环 | 可先维护人员，不开通账号 |
| 人员页开通账号 | 已闭环 | 当前推荐演示路径 |
| 成员导入 | 已闭环 | 当前推荐批量路径 |
| 账号页从人员开通成员 | 已闭环 | 当前账号页日常开户路径，选择已有人员后返回临时密码 |
| 邀请注册 | 后续 | 受控注册，不是匿名注册 |
| 审批注册 | 后续 | 需要申请、审批、租户选择和风控 |
| SSO/IdP 同步 | 后续 | 企业身份源治理成员生命周期 |

交付表达必须避免两类误导：

- 不能说“注册关闭后不能新增成员”。正确说法是“普通匿名注册关闭，管理员受控开户保留”。
- 不能把邀请注册、审批注册、SSO 说成当前已上线。当前上线的是人员开通账号、账号页从人员开通成员和成员导入。

### P1：账号安全策略仍是只读页，不是租户级配置页

当前已提供只读页面，解决“客户看不到当前账号安全策略”的交付问题。但它还不是完整配置中心。

下一迭代需要补：

- 租户级行为开关：自助密码、管理员托管、重置后强制改密、允许的登录方式。
- 明确展示哪些项是部署级不可租户覆盖：复杂度、历史密码、过期天数、锁定阈值。
- 修改策略后的审计日志和权限控制。
- 前端对未登录恢复入口、登录后个人改密、管理员重置等行为的联动。

### P1：重置后强制改密未闭环

管理员重置密码当前默认不强制 `mustChangePassword`。如果客户要求“管理员发临时密码后用户必须改密”，需要新增：

- 租户级开关。
- 后端在管理员重置后标记下次登录必须改密。
- 登录响应和前端路由强制进入改密页。
- 改密后清除强制标记，并覆盖历史密码校验。

### P1：邀请注册、审批注册、SSO 同步未闭环

公开注册默认关闭后，受控加入能力后续应按优先级扩展：

1. 邀请注册：管理员邀请，用户补全资料后加入租户。
2. 审批注册：用户申请加入租户，管理员审批。
3. 企业邮箱域加入：按域名白名单进入指定租户或进入审批。
4. SSO/IdP 同步：由企业身份源主导账号和成员生命周期。

这些能力未实现前，客户交付应使用人员开通账号、成员导入和管理员重置密码作为默认开户路径。

### P1：离职 UI 完整执行需准备一次性演示数据

已验证 API 会把成员 inactive、人员 resigned、全局用户保持 enabled，且 UI 有离职确认弹窗截图。为了不破坏当前 active 测试成员，本轮没有对同一个可复用成员执行破坏性 UI 离职到底。

客户演示如果需要走完整 UI 离职，应先准备一次性演示成员，再执行：

- 人员或账号入口发起离职/停用。
- 确认成员状态变为 inactive。
- 确认人员状态变为 resigned。
- 确认该用户当前 session 失效。
- 确认全局用户未被禁用。

### P2：角色/权限入口语义重复

`角色` 和 `权限/授权关系` 当前共用路由。短期可以接受为同一权限中心的两个入口；后续应二选一：

- 保留一个 `权限管理` 入口，页面内用 tab 区分角色、权限、授权关系。
- 两个菜单入口带 query/hash，直接定位到对应 tab。

### P2：命令目标字段契约不一致

运行态验证暴露出命令执行字段存在多种表达：

- `admin:reset_member_password` 通过 `payload.pid` 成功。
- `admin:leave_member` 需要 `targetRecordPid + payload.pid` 才能同时通过状态流转校验和 handler fallback。
- 错误文案提示 `memberPid`，但 handler fallback 实际读 `pid`。

短期按当前契约调用；下一迭代应统一命令执行 API 的 `targetRecordId/targetRecordPid/payload.pid/memberPid` 语义，避免前端、脚本和 DSL 配置各用一套字段。

## 本迭代剩余执行项

1. 若要客户演示离职 UI 闭环，准备一次性演示成员后补一条 UI 离职执行证据。

已同步完成：

- 测试矩阵文档已加入账号安全策略、人员开通账号、账号页从人员开通成员：`docs/plans/2026-06/2026-06-25-account-org-password-test-matrix.md`。
- UI 证据文档已加入 `UI-14`、`UI-15`、`UI-16`：`docs/plans/2026-06/2026-06-25-account-org-password-ui-evidence.md`。
- 本轮 targeted 后端、前端、插件配置、JSON 解析、OSS 重置环境、非登出 UI、登出 UI、成员重置截图、账号页从人员开户截图均已刷新到 `docs/plans/2026-06/evidence/account-org-password/latest/`。

## 验收标准

- 客户管理员能从菜单找到组织架构、职位、人员、团队、账号、角色、权限/授权关系。
- 客户管理员能从系统管理找到模型服务和账号安全策略。
- 公开注册默认关闭，普通匿名用户不能自行注册进租户。
- 客户管理员能新增人员，且新增人员不强制开通登录账号。
- 客户管理员能从人员行开通账号，并在新建用户时拿到一次性临时密码。
- 客户管理员能进入账号列表维护租户成员，包括导入、状态流转、管理员重置密码。
- 客户管理员能从账号页直接完成“选择已有人员 -> 开通成员 -> 绑定人员”的 UI 流程，并在新建用户时拿到一次性临时密码。
- 客户管理员能对当前租户成员执行离职/停用，且不影响该用户在其他租户的全局账号。
- 客户管理员能在账号/成员场景完成重置密码，并拿到一次性临时密码。
- 用户登出后当前 session 在服务端失效。
- 用户能编辑个人资料并上传头像。
- 文档口径和系统行为一致：默认管理员托管，自助密码未开启时不承诺自助找回。
- 对未实现能力有明确交付口径和下一迭代计划。

## SOT 更新

- 长期系统参考：`docs/system-reference/account-organization-team-password-governance.md`。
- 本文作为 2026-06 本轮交付 gap 和迭代指导，不替代长期 SOT。

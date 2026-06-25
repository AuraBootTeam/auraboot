---
type: handover
status: active
created: 2026-06-25
---

# Session Handover - 2026-06-25 10:21 CST

## Session Summary

本会话收口账号、组织、团队与密码治理交付线：补齐 SOT/gap/测试矩阵/UI 证据文档，完成账号页从已有人员开通成员的产品闭环，并在重置后的 OSS host-first 环境 `account-org-settings` slot `94` 上完成后端、前端、配置、Playwright 和截图验证。

## Tasks Completed

- [x] 明确产品口径：默认关闭匿名公开注册，但管理员受控开户保留；人员档案、人员页开通账号、账号页从人员开户、成员导入分别有清晰边界。
- [x] 补齐系统设置入口：系统管理包含模型服务和只读账号安全策略页。
- [x] 补齐组织管理入口：组织架构、职位、人员、团队、账号、角色、权限/授权关系可见。
- [x] 完成账号页从已有人员开通成员：`组织管理 -> 账号 -> 从人员开通账号 -> 选择人员 -> 临时密码弹窗`。
- [x] 修复 `admin:provision_member_from_employee` 只返回命令成功但未进入业务 handler 的问题。
- [x] 设计并落盘长期 SOT、gap 计划、测试矩阵、UI 证据文档。
- [x] 重置 OSS 环境并导入最新插件配置。
- [x] 补充并运行后端 targeted、前端 targeted、配置测试、JSON 解析、Playwright E2E 和截图验证。
- [x] 清理本轮临时 Playwright storage 目录，保留 docs/evidence 下的正式证据。

## Tasks In Progress

- [ ] 无本轮必须继续的开发项。当前目标已通过完成审计并标记 complete。

## Key Decisions

| Decision | Chosen Approach | Rationale | Alternatives Considered |
| --- | --- | --- | --- |
| 密码规则范围 | 密码复杂度、历史密码、过期、锁定、reset token 保持部署级 | 一个全局用户可跨多个租户，租户级密码复杂度会产生冲突 | 每租户独立密码规则，已放弃 |
| 租户级策略范围 | 租户只控制账号行为开关，如自助密码、登录方式、管理员托管、强制改密 | 避免策略过细导致跨租户冲突，同时保留高价值差异化 | 所有密码策略租户化，已放弃 |
| 未登录找回密码 | 不按租户切策略，默认随部署级自助密码关闭 | 未登录流程缺可靠目标租户上下文 | 让用户先选租户再找回，暂不做 |
| 关闭注册含义 | 关闭匿名公开注册，不关闭管理员受控开户 | 企业 SaaS 默认不能让未知用户自行进租户，但管理员必须能维护成员 | 完全关闭开户，错误 |
| 账号页开户范围 | 本轮闭环“从已有人员开通成员”；一站式新增人员+分配角色后续做 | 控制当前切片复杂度，先满足客户基础日常开户 | 一次性做完整向导，风险大 |
| 登出 | 服务端撤销当前 session/token，前端清理 cookie/session | 业界常规方案，避免旧 token 继续可用 | 纯前端清理，已放弃 |

## Files Changed

### Backend

- `platform/src/main/java/com/auraboot/framework/auth/controller/AuthController.java` - 默认公开注册关闭时拒绝匿名注册。
- `platform/src/main/java/com/auraboot/framework/auth/controller/AccountSecurityPolicyController.java` - 新增只读账号安全策略 API。
- `platform/src/main/java/com/auraboot/framework/auth/service/SessionManagementService.java` - 增加当前 session 撤销能力。
- `platform/src/main/java/com/auraboot/framework/auth/service/impl/SessionManagementServiceImpl.java` - 实现 bearer/session 撤销与校验。
- `platform/src/main/java/com/auraboot/framework/auth/service/impl/PasswordManagementServiceImpl.java` - 管理员重置用户密码走复杂度策略校验。
- `platform/src/main/java/com/auraboot/framework/meta/handler/TenantMemberCommandHandler.java` - 增加 `admin:provision_member_from_employee`，复用人员开通账号逻辑。
- `platform/src/main/java/com/auraboot/framework/meta/handler/OrgEmployeeCommandHandler.java` - 新增人员行开通账号 command handler。
- `platform/src/main/java/com/auraboot/framework/organization/dto/EmployeeAccountProvisionResponse.java` - 新增人员开户响应 DTO。
- `platform/src/main/java/com/auraboot/framework/organization/service/OrgEmployeeService.java` - 增加 `openAccount` 服务契约。
- `platform/src/main/java/com/auraboot/framework/organization/service/impl/OrgEmployeeServiceImpl.java` - 实现人员开通账号、绑定用户/租户成员、返回临时密码。
- `platform/src/main/java/com/auraboot/framework/saas/config/service/impl/SystemModeServiceImpl.java` - 调整公开注册默认关闭逻辑。
- `platform/src/main/java/com/auraboot/framework/tenant/service/TenantMemberApplicationService.java` - 增加管理员重置成员密码契约。
- `platform/src/main/java/com/auraboot/framework/tenant/service/impl/TenantMemberApplicationServiceImpl.java` - 管理员重置成员密码、状态流转联动人员离职/session 撤销。
- `platform/src/main/java/com/auraboot/framework/user/controller/AdminUserController.java` - 管理员直接重置用户密码的权限和策略处理。
- `platform/src/main/java/com/auraboot/framework/user/controller/SessionController.java` - 登出时调用服务端撤销当前 session。
- `platform/src/main/java/com/auraboot/framework/user/service/UserProvisioningService.java` - 支持受控开户所需用户创建/复用。

### Backend Tests

- `platform/src/test/java/com/auraboot/framework/auth/controller/AccountSecurityPolicyControllerTest.java`
- `platform/src/test/java/com/auraboot/framework/auth/controller/AuthControllerSelfServicePasswordTest.java`
- `platform/src/test/java/com/auraboot/framework/auth/service/impl/PasswordManagementServiceImplTest.java`
- `platform/src/test/java/com/auraboot/framework/auth/service/impl/SessionManagementServiceImplTest.java`
- `platform/src/test/java/com/auraboot/framework/meta/handler/OrgEmployeeCommandHandlerTest.java`
- `platform/src/test/java/com/auraboot/framework/meta/handler/TenantMemberCommandHandlerTest.java`
- `platform/src/test/java/com/auraboot/framework/organization/service/impl/OrgEmployeeServiceImplTest.java`
- `platform/src/test/java/com/auraboot/framework/tenant/service/impl/TenantMemberApplicationServiceImplTest.java`
- `platform/src/test/java/com/auraboot/framework/user/controller/AdminUserControllerPasswordResetTest.java`

### Plugin Config

- `plugins/org-management/config/menus.json` - 恢复组织管理下组织架构、职位、人员、团队、账号、角色、权限/授权关系。
- `plugins/org-management/config/bindingRules.json` - 新增人员开通账号 handler binding。
- `plugins/org-management/config/commands/org_open_employee_account.json` - 新增人员页开通账号命令。
- `plugins/org-management/config/commands/org_create_employee.json` - 人员创建不强制绑定登录账号。
- `plugins/org-management/config/fields/org_emp_user_id.json` - `系统用户` 非必填。
- `plugins/org-management/config/bindings/org_employee.json` - 人员与账号绑定字段调整。
- `plugins/org-management/config/pages/org_employee_form.json` - 人员表单系统用户字段非必填。
- `plugins/org-management/config/pages/org_employee_list.json` - 人员列表新增开通账号行操作。
- `plugins/org-management/plugin.json` - 注册 bindingRules 资源目录。
- `plugins/org-management/tests/menus-config.test.mjs` - 覆盖组织菜单、人员账号解耦、人员开通账号 command/binding。
- `plugins/platform-admin/config/menus.json` - 系统管理新增模型服务和账号安全策略入口。
- `plugins/platform-admin/config/permissions.json` - 补入口权限。
- `plugins/platform-admin/config/commands.json` - 新增 `admin:provision_member_from_employee`，保留 handler-only input 在页面 action。
- `plugins/platform-admin/config/pages.json` - 账号页 toolbar 新增“从人员开通账号”按钮和 `employeePid` select inputFields。
- `plugins/platform-admin/config/bindingRules.json` - 关键修复：绑定 `admin:provision_member_from_employee` 到 `tenantMemberCommandHandler`。
- `plugins/platform-admin/tests/menus-config.test.mjs` - 覆盖模型服务、账号安全策略、账号页开户 command/action/bindingRule。

### Frontend

- `web-admin/app/auth/Login.tsx` - 登录页默认不展示公开注册入口。
- `web-admin/app/auth/SignUp.tsx` - 注册关闭时不能绕过。
- `web-admin/app/auth/AuthHeader.tsx` - 登出调用服务端撤销。
- `web-admin/app/shared/services/session.ts` - 登出先撤销后清理。
- `web-admin/app/shared/services/profile.ts` - 头像上传路径改为后端实际接口。
- `web-admin/app/plugins/core-settings/resources.ts` - 注册账号安全策略页面资源。
- `web-admin/app/plugins/core-settings/pages/settings/account-security-policy.tsx` - 新增只读账号安全策略页。
- `web-admin/app/framework/meta/hooks/useActionHandler.ts` - command action 支持 page action `inputFields`，提交前弹出表单并合并 payload。
- `web-admin/app/framework/meta/runtime/actions/ActionRegistry.ts` - input field API datasource 支持分页 `data.records` 并支持 `valueField/labelField`。
- `web-admin/app/framework/meta/schemas/types.ts` - 补充 inputFields schema 类型。
- `web-admin/app/framework/meta/utils/i18nResolver.ts` - 确认弹窗 fallback/i18n 调整。

### Frontend Tests / E2E

- `web-admin/app/framework/meta/hooks/__tests__/useActionHandler.async.test.ts`
- `web-admin/app/framework/meta/runtime/actions/__tests__/ActionRegistry.test.ts`
- `web-admin/app/framework/meta/utils/__tests__/i18nResolver.confirm.test.ts`
- `web-admin/app/shared/services/__tests__/profile.test.ts`
- `web-admin/app/shared/services/__tests__/session.test.ts`
- `web-admin/tests/e2e/auth/auth-complete.spec.ts`
- `web-admin/tests/e2e/auth/auth-recovery-and-signup.spec.ts`
- `web-admin/tests/e2e/auth/logout.spec.ts`
- `web-admin/tests/e2e/helpers/index.ts`
- `web-admin/tests/e2e/organization/account-policy-and-employee-open-account.spec.ts` - 新增 POLICY-001、ORG-OPEN-001、MEM-04 和 UI-14/UI-15/UI-16 截图。
- `web-admin/tests/e2e/organization/org-employee.spec.ts`
- `web-admin/tests/e2e/organization/tenant-member-password-reset.spec.ts`
- `web-admin/tests/pages/HeaderPage.ts`

### Docs / Evidence

- `docs/system-reference/account-organization-team-password-governance.md` - 长期 SOT。
- `docs/plans/2026-06/2026-06-25-account-organization-team-password-gap-plan.md` - 本轮 gap 和迭代指导。
- `docs/plans/2026-06/2026-06-25-account-org-password-test-matrix.md` - 测试矩阵。
- `docs/plans/2026-06/2026-06-25-account-org-password-ui-evidence.md` - UI/API 证据记录。
- `docs/plans/2026-06/evidence/account-org-password/latest/**` - 本轮日志、API JSON、截图证据。

## Commands Run

### Write Guard / Static Checks

```bash
node scripts/agent-write-guard.mjs --repo /Users/ghj/work/auraboot-core-account-org-settings
git diff --check
```

### Backend

```bash
cd platform
./gradlew :test \
  --tests 'com.auraboot.framework.meta.handler.TenantMemberCommandHandlerTest' \
  --tests 'com.auraboot.framework.organization.service.impl.OrgEmployeeServiceImplTest' \
  --tests 'com.auraboot.framework.auth.controller.AccountSecurityPolicyControllerTest' \
  --tests 'com.auraboot.framework.auth.controller.AuthControllerSelfServicePasswordTest' \
  --tests 'com.auraboot.framework.auth.service.impl.PasswordManagementServiceImplTest' \
  --tests 'com.auraboot.framework.auth.service.impl.SessionManagementServiceImplTest' \
  --tests 'com.auraboot.framework.tenant.service.impl.TenantMemberApplicationServiceImplTest' \
  --tests 'com.auraboot.framework.user.controller.AdminUserControllerPasswordResetTest' \
  2>&1 | tee ../docs/plans/2026-06/evidence/account-org-password/latest/backend-targeted.log
```

### Plugin / JSON

```bash
node --test plugins/platform-admin/tests/menus-config.test.mjs \
  2>&1 | tee docs/plans/2026-06/evidence/account-org-password/latest/platform-admin-menu-config.log

node --test plugins/org-management/tests/menus-config.test.mjs \
  2>&1 | tee docs/plans/2026-06/evidence/account-org-password/latest/org-menu-config.log

node - <<'NODE' 2>&1 | tee docs/plans/2026-06/evidence/account-org-password/latest/json-parse.log
const fs = require('fs');
const path = require('path');
const roots = ['plugins/org-management/config', 'plugins/platform-admin/config'];
let count = 0;
function walk(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full);
    else if (entry.isFile() && entry.name.endsWith('.json')) {
      JSON.parse(fs.readFileSync(full, 'utf8'));
      count += 1;
    }
  }
}
for (const root of roots) walk(root);
console.log(`${count} JSON files parsed`);
NODE
```

### Frontend

```bash
cd web-admin
pnpm vitest run \
  app/framework/meta/hooks/__tests__/useActionHandler.async.test.ts \
  app/framework/meta/runtime/actions/__tests__/ActionRegistry.test.ts \
  app/shared/services/__tests__/session.test.ts \
  app/shared/services/__tests__/profile.test.ts \
  app/framework/meta/utils/__tests__/i18nResolver.confirm.test.ts \
  2>&1 | tee ../docs/plans/2026-06/evidence/account-org-password/latest/frontend-unit.log

pnpm typecheck 2>&1 | tee ../docs/plans/2026-06/evidence/account-org-password/latest/frontend-typecheck.log
```

### OSS Runtime Reset / Health

```bash
./scripts/oss-golden-stack.sh destroy account-org-settings --yes \
  2>&1 | tee docs/plans/2026-06/evidence/account-org-password/latest/oss-golden-stack-destroy.log

./scripts/oss-golden-stack.sh up account-org-settings --slot 94 --plugin-profile demo \
  2>&1 | tee docs/plans/2026-06/evidence/account-org-password/latest/oss-golden-stack-up-final.log

curl --noproxy '*' -sS http://127.0.0.1:6494/actuator/health
curl --noproxy '*' -sS -I http://127.0.0.1:5194/login
curl --noproxy '*' -sS http://127.0.0.1:6194/health
```

### Playwright

```bash
cd web-admin
eval "$(../scripts/oss-golden-stack.sh env account-org-settings)"

npx playwright test -c playwright.gt5.config.ts --project=auth tests/auth.setup.ts \
  2>&1 | tee ../docs/plans/2026-06/evidence/account-org-password/latest/playwright-auth-storage.log

npx playwright test -c playwright.gt5.config.ts --project=chromium --workers=1 \
  tests/e2e/organization/account-policy-and-employee-open-account.spec.ts \
  --grep 'MEM-04' \
  --output=../docs/plans/2026-06/evidence/account-org-password/latest/pw-output-policy-open-account-mem04 \
  2>&1 | tee ../docs/plans/2026-06/evidence/account-org-password/latest/playwright-policy-open-account-mem04.log

npx playwright test -c playwright.gt5.config.ts --project=chromium --workers=1 \
  tests/e2e/organization/account-policy-and-employee-open-account.spec.ts \
  --output=../docs/plans/2026-06/evidence/account-org-password/latest/pw-output-policy-open-account \
  2>&1 | tee ../docs/plans/2026-06/evidence/account-org-password/latest/playwright-policy-open-account.log

npx playwright test -c playwright.gt5.config.ts --project=chromium --workers=2 \
  tests/e2e/auth/auth-recovery-and-signup.spec.ts \
  tests/e2e/organization/org-employee.spec.ts \
  tests/e2e/organization/team-management.spec.ts \
  tests/e2e/organization/tenant-member-password-reset.spec.ts \
  tests/e2e/organization/account-policy-and-employee-open-account.spec.ts \
  --output=../docs/plans/2026-06/evidence/account-org-password/latest/pw-output-final-nonlogout \
  2>&1 | tee ../docs/plans/2026-06/evidence/account-org-password/latest/playwright-final-nonlogout.log

npx playwright test -c playwright.gt5.config.ts --project=chromium --workers=1 \
  tests/e2e/auth/logout.spec.ts \
  --output=../docs/plans/2026-06/evidence/account-org-password/latest/pw-output-final-logout \
  2>&1 | tee ../docs/plans/2026-06/evidence/account-org-password/latest/playwright-final-logout.log
```

### API Evidence Refresh

```bash
node - <<'NODE'
const fs = require('fs');
const path = require('path');

(async () => {
  const base = 'http://127.0.0.1:6494';
  const outDir = 'docs/plans/2026-06/evidence/account-org-password/latest';
  async function jsonFetch(url, options = {}) {
    const response = await fetch(url, options);
    let body;
    try {
      body = await response.json();
    } catch {
      body = await response.text();
    }
    return { status: response.status, body };
  }
  function write(name, data) {
    fs.writeFileSync(path.join(outDir, name), JSON.stringify(data, null, 2));
  }
  const login = await jsonFetch(`${base}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: 'admin@auraboot.com', password: 'Test2026x' }),
  });
  const jwt = login.body?.data?.jwt;
  if (!jwt) throw new Error('login did not return jwt');
  const auth = { Authorization: `Bearer ${jwt}` };

  write('api-01-register-disabled.json', await jsonFetch(`${base}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: `blocked-${Date.now()}@example.test`,
      password: 'Test2026x',
      displayName: 'Blocked Registration',
    }),
  }));

  write('api-05-account-security-policy.json',
    await jsonFetch(`${base}/api/admin/account-security-policy`, { headers: auth }));

  const menu = await jsonFetch(`${base}/api/menu/user`, { headers: auth });
  const serializedMenu = JSON.stringify(menu.body);
  write('api-06-menu-after-policy-open-account.json', {
    status: menu.status,
    containsAccountSecurityPolicy: serializedMenu.includes('account_security_policy'),
    containsOrgEmployee: serializedMenu.includes('org_employee'),
    containsTenantMember: serializedMenu.includes('tenant_member'),
    body: menu.body,
  });

  const tenantMemberPage = await jsonFetch(`${base}/api/pages/key/tenant_member_list`, { headers: auth });
  const serializedPage = JSON.stringify(tenantMemberPage.body);
  write('api-07-account-page-provision-from-employee.json', {
    status: tenantMemberPage.status,
    containsProvisionFromEmployee: serializedPage.includes('provision_from_employee'),
    containsCommand: serializedPage.includes('admin:provision_member_from_employee'),
    containsEmployeeInput: serializedPage.includes('employeePid'),
    body: tenantMemberPage.body,
  });
})();
NODE
```

## Test Results

- `backend-targeted.log`: `BUILD SUCCESSFUL`, targeted backend tests passed.
- `frontend-unit.log`: 5 test files, 51 tests passed.
- `frontend-typecheck.log`: `react-router typegen && tsc` passed.
- `platform-admin-menu-config.log`: 4/4 passed.
- `org-menu-config.log`: 3/3 passed.
- `json-parse.log`: 58 JSON files parsed.
- `playwright-auth-storage.log`: 16 passed.
- `playwright-policy-open-account-mem04.log`: 17 passed.
- `playwright-policy-open-account.log`: 19 passed.
- `playwright-final-nonlogout.log`: 34 passed.
- `playwright-final-logout.log`: 20 passed.
- `git diff --check`: passed.
- Runtime health: backend `UP`, Vite `302`, BFF healthy.

## Evidence Paths

Base evidence directory:

```text
docs/plans/2026-06/evidence/account-org-password/latest/
```

Key logs:

- `backend-targeted.log`
- `frontend-unit.log`
- `frontend-typecheck.log`
- `org-menu-config.log`
- `platform-admin-menu-config.log`
- `json-parse.log`
- `oss-golden-stack-destroy.log`
- `oss-golden-stack-up-final.log`
- `oss-golden-stack-status-final.log`
- `preflight-health.log`
- `playwright-auth-storage.log`
- `playwright-policy-open-account-mem04.log`
- `playwright-policy-open-account.log`
- `playwright-final-nonlogout.log`
- `playwright-final-logout.log`

Key API JSON:

- `api-01-register-disabled.json` - anonymous registration rejected with code `403`.
- `api-05-account-security-policy.json` - read-only policy, public registration disabled.
- `api-06-menu-after-policy-open-account.json` - menu contains account security policy, org employee, tenant member.
- `api-07-account-page-provision-from-employee.json` - runtime page schema contains `provision_from_employee`, `admin:provision_member_from_employee`, and `employeePid`.

Key screenshots:

- `screenshots/ui-14-account-security-policy.png`
- `screenshots/ui-15-employee-open-account-confirm.png`
- `screenshots/ui-15-employee-open-account-temp-password.png`
- `screenshots/ui-16-account-provision-from-employee-form.png`
- `screenshots/ui-16-account-provision-from-employee-temp-password.png`
- Existing preserved evidence also includes `ui-01` through `ui-13` for login/register/menu/member/team/profile/logout flows.

## Pitfalls & Workarounds

1. **Problem**: `admin:provision_member_from_employee` returned command success but no business payload.
   - **Root Cause**: command existed, but `plugins/platform-admin/config/bindingRules.json` did not bind it to `tenantMemberCommandHandler`.
   - **Solution**: added bindingRule and extended `plugins/platform-admin/tests/menus-config.test.mjs` to assert command/action/inputFields/bindingRule.
   - **Prevention**: for custom command handlers, config tests must assert separate `bindingRules.json`, not only command presence.

2. **Problem**: first attempted backend targeted command failed because root `./gradlew` did not exist.
   - **Root Cause**: Gradle wrapper is under `platform/`.
   - **Solution**: reran from `platform/` with `./gradlew :test ...`.
   - **Prevention**: check wrapper location before recording evidence commands.

3. **Problem**: `./gradlew test --tests ...` triggered `platform-plugin-api:test` with no matching tests.
   - **Root Cause**: unqualified `test` task applied filters to subproject task.
   - **Solution**: reran `./gradlew :test --tests ...` to target the main platform module.
   - **Prevention**: use `:test` for main module targeted backend suites in this repo.

4. **Problem**: API evidence refresh first hit `displayName` validation instead of registration-disabled branch.
   - **Root Cause**: register payload used `name`, while `RegisterRequest` requires `displayName`.
   - **Solution**: refreshed `api-01-register-disabled.json` with `displayName`.
   - **Prevention**: API evidence scripts should mirror DTO field names, not UI labels.

5. **Problem**: menu API did not prove toolbar button existence.
   - **Root Cause**: `/api/menu/user` contains navigation entries, not page toolbar schema.
   - **Solution**: added `api-07-account-page-provision-from-employee.json` from `GET /api/pages/key/tenant_member_list`.
   - **Prevention**: prove menu presence with menu API, prove button/action presence with page schema API or UI E2E.

## Lessons Learned

- Command definition is not enough for runtime behavior; custom command execution depends on `bindingRules.json`.
- Command-level `inputFields` caused field permission validation against `tenant_member`; handler-only payload fields must stay on page action `inputFields`.
- Runtime evidence needs the right layer: navigation menu, page schema, command response, and screenshot each prove different things.
- For this feature class, static config tests are valuable only when they include the handler binding, not just page/button existence.

## 反思与经验固化 (Reflection & Codify)

### 本会话弯路 / 返工 / 翻车

1. **账号页开户命令未进入 handler** - 代价：一次 MEM-04 E2E 失败和一次运行态重置后复测 - 本可如何更早避免：配置测试一开始就断言 `bindingRules.json` - 根因：`[A 门禁质量 / D 验证纪律]`
2. **Backend targeted 命令路径两次调整** - 代价：两次无效命令输出 - 本可如何更早避免：先定位 Gradle wrapper 和主模块任务 - 根因：`[B 输入信息不足]`
3. **API 证据字段和证明层级不准确** - 代价：刷新 API 证据时返工 - 本可如何更早避免：先读 DTO / controller / page schema API - 根因：`[D 验证纪律]`

### 为什么会发生(根因归类小结)

主要问题集中在 A/D：早期门禁只证明“配置对象存在”，没有证明运行时 handler 绑定；API 证据第一次没有严格匹配 DTO 和证明层级。B 类问题体现在 Gradle wrapper/任务路径需要现场确认。

### 应该有哪些改进

- 平台插件涉及 custom command handler 时，配置测试必须同时覆盖 command、页面 action、handler bindingRule。
- 生成 API 证据前先读 DTO/controller，避免请求先被参数校验拦截。
- 对页面按钮/toolbar 的运行态证据优先使用 `/api/pages/key/{pageKey}` 或真浏览器截图，不用 `/api/menu/user` 代替。
- 后端 targeted 命令在本 worktree 使用 `cd platform && ./gradlew :test ...`。

### 已固化 / 待固化(更新文档)

- [x] 已写入 `docs/plans/2026-06/2026-06-25-account-organization-team-password-gap-plan.md`：账号页从已有人员开通成员已闭环，后续仅保留完整向导、策略可编辑、邀请/SSO 等增强项。
- [x] 已写入 `docs/system-reference/account-organization-team-password-governance.md`：关闭注册不是关闭开户，管理员受控入口包括人员页开户、账号页从人员开户、成员导入。
- [x] 已写入 `docs/plans/2026-06/2026-06-25-account-org-password-test-matrix.md`：MEM-04 已验证，证据包含 backend/config/API/UI/E2E。
- [x] 已写入 `docs/plans/2026-06/2026-06-25-account-org-password-ui-evidence.md`：新增 UI-16 和 API-07。
- [ ] 待考虑固化到 canonical gotcha：custom command handler 必须在 `bindingRules.json` 有独立 handler rule，不能只在 `commands.json` 或页面 action 定义命令。

## 运行态快照 (Operational State)

### 分支 / Worktree / PR

- **当前分支**: `codex/account-org-settings-closure`，base `origin/main`，ahead/behind `0/0`。
- **Worktree**: `/Users/ghj/work/auraboot-core-account-org-settings`
- **其它 worktree**:
  - `/Users/ghj/work/auraboot/auraboot` on `main`
  - `/Users/ghj/work/auraboot/.worktrees/quote-bom-final-verify/auraboot` on `codex/quote-bom-final-verify`
- **本会话关键 commit**: 无。本会话未 commit。
- **PR**: 未开 PR。
- **未提交改动**: 工作区有大量本任务相关修改和新文件；`git diff --stat` 摘要为 54 files changed, 1346 insertions, 156 deletions，另有新增 docs/evidence、controllers、handlers、tests、plugin configs、E2E specs 等 untracked 文件。

### Runtime / 端口(host-first slot 模型,零 docker)

- **Runtime**: `account-org-settings` · repo `auraboot` · slot `94`
- **Env**: `/Users/ghj/work/auraboot/.workspace/env/account-org-settings.env`
- **端口**: backend `6494` · web/Vite `5194` · BFF `6194`
- **命名空间**:
  - Postgres DB `auraboot_94`
  - Redis DB `15`
  - Redis prefix `aura:auraboot:94:`
  - Kafka prefix `auraboot.94.`
  - S3 bucket prefix `auraboot-94-`
  - ES index prefix `auraboot-94-`
- **依赖的常驻 broker**: Postgres `5432`, Redis `6379`, Kafka `9092`, S3/MinIO `9000`, ES `9200` as provided by shared host-first infra.
- **当前在跑的服务**:
  - `java` pid `32240` listening on `6494`
  - `node` pid `35236` listening on `5194`
  - `node` pid `35287` listening on `6194`
  - shared `postgres`, `redis-server`, Kafka process also listening.
- **接手者起栈命令**:

```bash
./scripts/oss-golden-stack.sh up account-org-settings --slot 94 --plugin-profile demo
eval "$(./scripts/oss-golden-stack.sh env account-org-settings)"
```

### Database / Seed 状态

- Runtime was destroyed and recreated in this session.
- Bootstrap completed: `admin@auraboot.com / Test2026x`.
- Plugin profile `demo` imported successfully.
- Warm setup/auth/routes completed.
- Latest status: backend `UP`, Vite `302`, BFF healthy.

## Unfinished Work / Known Future Scope

- 租户级账号安全策略仍是只读展示，后续可做可编辑配置页。
- 管理员重置后强制改密仍未闭环，需租户级开关、登录强制改密引导和清除标记。
- 邀请注册、审批注册、企业邮箱域加入、SSO/IdP 同步仍是后续受控加入能力。
- 账号页“一站式新增人员 + 创建/复用用户 + 分配角色 + 开通成员”的完整向导仍是后续增强。
- 离职 UI 已有确认弹窗和 API 落库证据；如客户现场要演示完整 UI 离职到底，需准备一次性演示成员，避免破坏复用测试账号。
- 角色和权限/授权关系入口当前仍有语义重复，后续可拆 tab 或合并入口。
- 命令执行 API 的 `targetRecordId/targetRecordPid/payload.pid/memberPid` 语义后续应统一。

## Next Steps

1. 如要提交 PR，先审计全部 untracked docs/evidence/test files，确认都属于本任务后再 stage。
2. 如要客户演示，优先演示：
   - 登录页无公开注册入口
   - 系统管理 -> 账号安全策略
   - 组织管理 -> 人员 -> 开通账号
   - 组织管理 -> 账号 -> 从人员开通账号
   - 组织管理 -> 账号 -> 重置密码
   - 团队成员添加/移除
3. 如要演示离职完整 UI，先创建一次性演示成员，再执行离职，避免污染复用账号。
4. 如继续后续增强，先从 gap 文档的 P1 项拆新任务：策略可编辑、重置后强制改密、邀请/SSO、一站式新增成员向导。

## Context for Next Session

Primary docs:

- `docs/system-reference/account-organization-team-password-governance.md`
- `docs/plans/2026-06/2026-06-25-account-organization-team-password-gap-plan.md`
- `docs/plans/2026-06/2026-06-25-account-org-password-test-matrix.md`
- `docs/plans/2026-06/2026-06-25-account-org-password-ui-evidence.md`

Primary E2E spec:

- `web-admin/tests/e2e/organization/account-policy-and-employee-open-account.spec.ts`

Primary runtime evidence:

- `docs/plans/2026-06/evidence/account-org-password/latest/`

Most important implementation detail:

- `plugins/platform-admin/config/bindingRules.json` must contain `admin:provision_member_from_employee -> tenantMemberCommandHandler`; without it, the command can return generic success without business payload.

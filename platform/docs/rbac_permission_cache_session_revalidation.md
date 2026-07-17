---
type: system-reference
status: active
---

# RBAC 权限缓存与会话重校验 SoT

本文是 OSS 平台运行时 RBAC 权限变更传播的 SoT。它补充 `platform/docs/jwt_auth.md` 的 JWT 认证流程，重点定义“角色/权限被授予或撤回后，后端缓存、接口授权、前端菜单/按钮状态如何收敛”。

相关原始文档与入口：

- 原 JWT 认证说明：`platform/docs/jwt_auth.md`
- RBAC 回归纪律：`auraboot-enterprise/docs/agent-rules/rbac-golden-and-cross-cutting-regression.md`
- 后端事件监听器：`platform/src/main/java/com/auraboot/framework/permission/listener/PermissionCacheEvictionListener.java`
- 后端权限计算：`platform/src/main/java/com/auraboot/framework/permission/service/impl/UserPermissionServiceImpl.java`
- 前端根 loader：`web-admin/app/root.tsx`
- 前端会话重校验：`web-admin/app/components/AuthSessionRevalidator.tsx`

## 目标契约

权限变更必须同时满足两层契约：

1. 后端授权契约：授予或撤回角色/权限提交成功后，`user-permissions:{userId}` 不得继续依赖旧缓存。下一次权限解析必须基于当前 `ab_user_role` / role-permission 数据重新计算。
2. 前端体验契约：用户停留在页面不导航时，菜单、按钮和 `/api/auth/me` 派生的 AuthContext 不能无限期保留旧权限快照。前端必须在 focus、tab 重新可见、周期轮询时重跑 root loader。

JWT token 本身只表示“会话身份仍有效”，不表示“权限快照永久有效”。撤权不强制注销会话；显式 session revoke / token 失效才走 401 或登录页。

## 后端传播链路

权限缓存使用 `user-permissions` cache name，key 维度是当前用户。缓存 TTL 只是兜底，不是权限变更传播机制。

角色权限变更：

1. `RolePermissionServiceImpl` 在角色权限绑定变更成功后发布 `RolePermissionChangedEvent`。
2. `PermissionCacheEvictionListener.onRolePermissionChanged` 在事务提交后处理事件。
3. 监听器按 role 查找受影响用户并逐个清理 `user-permissions:{userId}`。

用户角色变更：

1. `UserRoleServiceImpl` 所有 member-role 变更入口都必须发布 `UserRoleChangedEvent`。
2. 覆盖入口包括单个授予/撤回、批量授予、批量撤回、按 pid 撤回、activate/deactivate、member role transfer。
3. `RoleServiceImpl.assignRoleToMember` 必须委托 `UserRoleServiceImpl.assignRolesToMember`，不能绕过事件发布直接保存 `UserRole`。
4. `PermissionCacheEvictionListener.onUserRoleChanged` 在事务提交后清理目标用户的 `user-permissions:{userId}`。

事件发布必须在 DB mutation 成功之后发生；监听器使用 `@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)`，避免事务回滚时提前清缓存。

## 前端重校验链路

`web-admin/app/root.tsx` 的 root loader 是认证态前端的权限快照来源：

- `getUserInfo(request)` 获取用户、roles、permissionCodes、preferences。
- `getUserMenus(request)` 获取当前用户菜单。
- `AuthProvider` 从 loader data 生成 `hasPermission` / `hasRole` / `isAuthenticated`。

`AuthSessionRevalidator` 是无 UI 根级组件，只在 admin runtime 且已登录时启用：

- window focus 时尝试 `useRevalidator().revalidate()`。
- document 从 hidden 回到 visible 时尝试 revalidate。
- 默认每 60 秒周期 revalidate。
- 默认 15 秒最小间隔，且 React Router revalidator 非 idle 时不重入。

因此撤权后的用户体验边界是：

- 后端 API 授权：缓存清理后下一次请求立即按新权限判定。
- 前端菜单/按钮：最迟在下一次 focus / visible / 60 秒周期刷新时收敛。
- token/session：除显式 session revoke 或用户解析失败，不因单次权限变更自动登出。

## 不变量

- 任何新增 member-role mutation 入口都必须走 `UserRoleServiceImpl` 的事件发布路径，或显式发布 `UserRoleChangedEvent`。
- 任何新增 role-permission mutation 入口都必须发布 `RolePermissionChangedEvent`。
- 不能用 30 分钟 TTL 解释撤权生效；TTL 只是异常兜底。
- 前端业务页面不应自行缓存权限作为长期事实源；应从 root loader / AuthContext 派生。

## 验证记录

2026-07-05 验证：

- 后端 targeted JUnit：
  - `./gradlew :test --tests com.auraboot.framework.rbac.service.impl.UserRoleServiceImplEvictEventTest --tests com.auraboot.framework.rbac.service.impl.RoleServiceImplTest`
  - 46 tests passed, 0 failed。
- 前端 targeted Vitest：
  - `./node_modules/.bin/vitest run app/components/__tests__/AuthSessionRevalidator.test.tsx app/__tests__/root-loader-auth.test.ts`
  - 9 tests passed, 0 failed。
- 前端类型检查：
  - `./node_modules/.bin/react-router typegen`
  - `./node_modules/.bin/tsc`

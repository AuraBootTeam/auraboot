# 平台 `/api/admin/**` 统一 Role Guard 设计

## 1. 问题陈述

当前 `JwtAuthenticationFilter` 仅校验 JWT 有效性并注入 `MetaContext`(tenantId/userId/userPid/username);`MetaContext` **不含 role**,`CustomUserDetails.authorities` 只被 `LoginCompletionHelper` 填了硬编码 `role_user`。结果:任何认证用户都能 POST 到任意 `/api/admin/*`。

`grep "@RequestMapping\(\"/api/admin/"` 命中 **8 个控制器**(+1 个已加临时 guard 的 USP,合计 9 个):

- `ExchangeRateController` — `/api/admin/exchange-rates`
- `TenantTimezoneController` — `/api/admin/tenants/timezone` (renamed from `TimezoneMigrationController`/`/api/admin/timezone` in PR-B)
- `AdminUserController` — `/api/admin/users`
- `EnvironmentController` — `/api/admin/environments`
- `InfrastructureController` — `/api/admin/infrastructure`
- `I18nAdminController` — `/api/admin/i18n`
- `CloudConfigController` — `/api/admin/cloud-config`
- `LoginChannelManageController` — `/api/admin/login-channels`
- `UserSoulProfileAdminController` — `/api/admin/user-soul-profiles` (临时 `guardTenantAdmin()`)

Round-2 review 结论:Round-2 修复只在 USP 一处加 per-endpoint guard 是临时补丁,必须平台化。

## 2. 方案对比

### 方案 A:Spring Security 注解式 `@PreAuthorize("hasRole('tenant_admin')")`

- **改造范围**:① Security 配置加 `@EnableMethodSecurity`;② `JwtAuthenticationFilter` 把 `ab_user_role` 查询结果转为 `SimpleGrantedAuthority("ROLE_tenant_admin")` 填入 `UsernamePasswordAuthenticationToken`(现在只靠下游 `CustomUserDetails.authorities`,且该字段在 JWT 路径上从未被刷新);③ 9 个控制器每个方法或类加注解。
- **性能**:JWT 过滤器每次请求多一次 role SQL(可按 `userId+tenantId` 进程内 60s 缓存)。
- **错误码**:Spring 默认抛 `AccessDeniedException` → 403。与项目现有 `ApiResponse` 规范需用 `AccessDeniedHandler` 统一包装。
- **测试**:`@WithMockUser(roles="tenant_admin")` 或真实 JWT + 集成测试种子 role。
- **风险**:Spring Security 的 `hasRole` 自动加 `ROLE_` 前缀,容易踩坑;注解分散在 9 个类,**漏加无编译期保障**。

### 方案 B:自定义 `@RequireRole("tenant_admin")` + AOP 切面

- **改造范围**:新增注解 + `@Around` 切面,在切面内查 `ab_user_role`;9 个控制器加注解。
- **性能**:同 A。
- **错误码**:切面直接抛 `BusinessException(409, "admin role required")` 或返回 `ApiResponse.error`,与 USP 临时实现完全一致,无需改 `GlobalExceptionHandler`。
- **测试**:继承 `BaseIntegrationTest` 加 non-admin 用例断言 409/403。
- **风险**:与方案 A 同样**漏加无编译期保障**;AOP 在 final 方法/私有方法上静默失效。

### 方案 C:`HandlerInterceptor` 按 URL 前缀 `/api/admin/**` 统一查 role

- **改造范围**:① 新增 `AdminRoleInterceptor` 注册到 `WebMvcConfigurer`,`addPathPatterns("/api/admin/**")`;② 拦截器内复用 USP 的 SQL;③ 从所有 9 个控制器 **删除** per-endpoint guard。
- **性能**:同 A/B。拦截器先于 Controller 执行,有 `MetaContext`(JwtAuthenticationFilter 已填)。
- **错误码**:拦截器 `preHandle` 写 `ApiResponse.error(409, ...)` 直接 return false,路径与 USP 临时 guard 一致。
- **测试**:写 1 个 `AdminRoleInterceptorIntegrationTest` 覆盖所有 `/api/admin/*` 前缀,per-controller 再各补 1 个 non-admin → 409 用例。
- **风险**:URL 前缀硬依赖路径命名约定 — 未来若新增 `/api/admin-tools/xxx`(典型笔误) 会绕过;需在 CI 加 grep 守门。但**不会漏加**,这是相比 A/B 最大的优势。

## 3. 推荐方案:**C(拦截器)**

**Why**:
1. **零漏加风险** — 方案 A/B 需要 review 时人工确认每个新 admin 控制器都加了注解,历史已证明做不到(当前 8 个控制器全漏)。前缀拦截是"默认拒绝"。
2. `JwtAuthenticationFilter` 已在过滤链尾填好 `MetaContext`,拦截器正好能读,无需再改 filter 去填 GrantedAuthorities(方案 A 要改 filter,扩散面更大)。
3. 错误码 409 与 USP 临时实现一致,`GlobalExceptionHandler` 和前端无需变更。
4. 项目已有 `WhiteList` 路径前缀白名单惯例,拦截器沿用心智模型一致。

## 4. 迁移路径

**阶段 1:基础设施**
- 新增 `com.auraboot.framework.application.security.AdminRoleInterceptor`,内封装 USP 那段 SQL,入参 `tenantId/userId/roleCode`。
- 新增 `WebMvcConfig#addInterceptors` 注册到 `/api/admin/**`。
- 新增种子 SQL:integration test 的 `admin@auraboot.com` 用户在默认 tenant 下绑定 `tenant_admin` 角色;`schema.sql`/`data-test.sql` 同步。

**阶段 2:集成测试铺覆盖**
- 每个 `/api/admin/*` 控制器新增 `XxxAdminGuardIntegrationTest`:admin 200 + non-admin 409 两个用例。
- 复用 `BaseIntegrationTest` 的 JWT 工具生成两种身份 token。

**阶段 3:移除 USP 临时 guard**
- 删除 `UserSoulProfileAdminController.guardTenantAdmin()` 及 3 处调用。
- 保留 `TENANT_ADMIN_ROLE_CODE` 常量下沉到 `AdminRoleInterceptor` 或新 `RoleCodes` 常量类。
- 回归 USP 的现有 forget/list/stats 集成测试。

## 5. 兼容性

开发阶段允许破坏性。以下现有测试/脚本**会因种子缺失 admin 角色而 409**,需同步补:

- `BaseIntegrationTest` 默认登录用户必须在默认 tenant 下持有 `tenant_admin`(目前 seed 可能只插 `ab_tenant_member`,未插 `ab_user_role`)。
- 所有既存 `AdminUserController`/`I18nAdminController`/`CloudConfigController` 等的 integration test(若有)需检查种子。
- `reset-and-init.sh` 初始化 admin 账号时插 `ab_user_role(role=tenant_admin, status=active)`。
- E2E:`admin@auraboot.com / Test2026x` 已是平台 admin,但需确认 `ab_user_role` 行已落库 — 否则 web-admin 所有调用 `/api/admin/*` 的页面全部 409。

## 6. 测试策略

- **单元/拦截器级**:`AdminRoleInterceptorIntegrationTest` 覆盖: (a) admin → pass;(b) authenticated non-admin → 409;(c) 无 JWT → filter 层已拒(401)不进拦截器;(d) `MetaContext` 缺 tenantId/userId → 409;(e) role 存在但 `status!='active'` → 409;(f) role `deleted_flag=true` → 409。
- **每控制器**:9 个控制器必须各新增 **至少 1 个 non-admin → 409** 用例,断言 `ApiResponse.code=409` 且正文含 `admin role required`。
- **E2E**:web-admin 管理页冒烟必须有 1 条"以普通 tenant member 登录 → 访问管理菜单 → 看到 403/提示"。
- **CI grep 守门**:CI 脚本 grep `@RequestMapping\("/api/admin` 数量 = 拦截器 path pattern 匹配 = 预期 9(增量时需显式更新计数)。

## 7. 开放问题

1. **二级角色 `platform_admin` vs `tenant_admin`**:`InfrastructureController` / `EnvironmentController` / `CloudConfigController` 操作的是跨租户的平台级资源,用 `tenant_admin` 语义错配 — 任何租户的 admin 都能改平台配置。建议新增 `platform_admin`,拦截器配置项按 path 精细化:`/api/admin/infrastructure/**`、`/api/admin/environments/**`、`/api/admin/cloud-config/**` 要 `platform_admin`,其余要 `tenant_admin`。**决策点**:是先统一 `tenant_admin` 再增量收紧,还是一步到位双角色?倾向前者,降低阶段 2 风险。
2. ~~**`TimezoneMigrationController`** 是运维一次性工具,是否应下线或挪到 `/api/admin/ops/**` + `platform_admin`?~~ — DONE: 已重命名为 `TenantTimezoneController`，路径 `/api/admin/tenants/timezone`，保留 `tenant_admin` gate（见 PR-B）。
3. **`AdminUserController`** 是 tenant 内用户管理,`tenant_admin` 合适,但跨租户导入用户的接口(若有)需升格 `platform_admin`。
4. **缓存**:是否引入 `Caffeine` 对 `(userId,tenantId)→roles` 做 60s TTL?admin 端 QPS 低,暂不引入,保留选项。
5. **审计**:拦截器是否统一记录 admin 访问审计?USP 的 `ab_agent_user_soul_profile_admin_action` 是业务审计,跨控制器的通用 admin access log 是否要新表?建议阶段 4 另行设计。

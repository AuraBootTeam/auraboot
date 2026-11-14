> SOT 补充：本文只说明 JWT 身份认证流程。RBAC 权限缓存失效、撤权传播、前端停留页会话重校验的权威说明见 `platform/docs/rbac_permission_cache_session_revalidation.md`。

## JWT认证架构概览

项目采用了Spring Security + JWT的认证方案，主要涉及以下核心组件：

### 1. 核心组件关系

```
用户请求 → JwtAuthenticationFilter → SecurityConfig → CustomUserDetailsService → CustomUserDetails
    ↓
AuthController → AuthServiceImpl → JwtUtil
```

## 详细认证流程

### 🔐 登录认证流程

1. **用户登录请求**
   - 用户通过 `platform/src/main/java/com/auraboot/framework/controller/AuthController.java` 的 `/api/auth/login` 接口提交 `platform/src/main/java/com/auraboot/framework/auth/dto/AuthenticationRequest.java`（包含email和password）

2. **认证处理**
   - `platform/src/main/java/com/auraboot/framework/auth/service/AuthServiceImpl.java` 调用 `AuthenticationManager.authenticate()`
   - 创建 `UsernamePasswordAuthenticationToken(email, password)`
   - Spring Security的 `DaoAuthenticationProvider` 自动调用 `platform/src/main/java/com/auraboot/framework/auth/service/CustomUserDetailsService.java`

3. **用户信息加载**
   - `CustomUserDetailsService.loadUserByUsername()` 通过email查找用户
   - 返回 `platform/src/main/java/com/auraboot/framework/auth/dto/CustomUserDetails.java` 对象（包含userId、权限等）
   - Spring Security自动进行密码验证（使用PasswordEncoder）

4. **JWT生成**
   - 认证成功后，`platform/src/main/java/com/auraboot/framework/auth/util/JwtUtil.java` 生成JWT token
   - 将userId等信息编码到JWT的claims中
   - 返回 `platform/src/main/java/com/auraboot/framework/auth/dto/AuthenticationResponse.java`（包含jwt、userId、username）

### 🛡️ 请求验证流程

1. **请求拦截**
   - 每个API请求都会经过 `platform/src/main/java/com/auraboot/framework/controller/JwtAuthenticationFilter.java`
   - 检查白名单，跳过不需要认证的路径

2. **JWT解析**
   - 从请求头 `Authorization: Bearer <token>` 中提取JWT
   - 使用 `JwtUtil.extractEmail()` 解析用户名
   - 使用 `JwtUtil.extractUserId()` 解析用户ID

3. **用户验证**
   - 调用 `CustomUserDetailsService.loadUserByUsername()` 重新加载用户信息
   - 使用 `JwtUtil.validateToken()` 验证token有效性（签名、过期时间等）

4. **设置安全上下文**
   - 创建 `platform/src/main/java/com/auraboot/framework/auth/service/EmailPasswordAuthenticationToken.java`（自定义认证对象，包含userId）
   - 将认证信息设置到 `SecurityContextHolder`
   - 后续可通过 `platform/src/main/java/com/auraboot/framework/auth/util/CurrentUserUtil.java` 获取当前用户信息

## 关键配置

### Security配置
`platform/src/main/java/com/auraboot/framework/application/security/SecurityConfig.java` 配置了：
- 无状态会话（STATELESS）
- JWT过滤器在用户名密码过滤器之前执行
- 白名单路径免认证
- DaoAuthenticationProvider配置

### JWT配置
```yaml
security:
  jwt:
    secret: your-secret-code
    expiration: token-expiration-time
```

## 数据流转图

```
登录: email/password → AuthenticationManager → CustomUserDetailsService → JWT生成 → 返回token

请求: Bearer token → JwtAuthenticationFilter → JWT解析 → 用户验证 → SecurityContext设置 → 业务处理
```

## 关键特性

1. **自定义认证对象**: `EmailPasswordAuthenticationToken` 扩展了标准认证，包含userId等额外信息
2. **用户信息扩展**: `CustomUserDetails` 实现了Spring Security的UserDetails，添加了userId字段
3. **JWT增强**: JWT中包含userId等业务信息，便于后续业务处理
4. **安全过滤**: 白名单机制，灵活控制哪些接口需要认证
5. **异常处理**: 统一的认证失败响应格式

这套架构实现了完整的JWT认证流程，既保证了安全性，又提供了良好的扩展性。

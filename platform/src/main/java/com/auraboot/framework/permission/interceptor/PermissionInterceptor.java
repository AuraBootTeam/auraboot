package com.auraboot.framework.permission.interceptor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.permission.service.UserPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Interceptor for permission-based access control
 * 
 * <p>This interceptor checks if the current user has the required permission
 * before allowing access to protected resources. It works with the
 * {@link RequirePermission} annotation.
 * 
 * <p>Execution flow:
 * <ol>
 *   <li>Extract {@link RequirePermission} annotation from handler method or class</li>
 *   <li>Get current user from SecurityContext</li>
 *   <li>Check if user has the required permission via {@link UserPermissionService}</li>
 *   <li>Allow or deny access based on permission check result</li>
 * </ol>
 * 
 * <p>Priority:
 * <ul>
 *   <li>Method-level annotation takes precedence over class-level annotation</li>
 *   <li>If no annotation is found, access is allowed (no permission check)</li>
 * </ul>
 * 
 * <p>Optional permissions:
 * <ul>
 *   <li>If {@code optional=true}, missing permission will not throw exception</li>
 *   <li>Useful for degraded functionality or feature flags</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @version 1.0.0
 * @since 2025-01-08
 * @see RequirePermission
 * @see UserPermissionService
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionInterceptor implements HandlerInterceptor {
    
    private final UserPermissionService userPermissionService;
    private final MenuMapper menuMapper;
    private final AdminRoleChecker adminRoleChecker;

    /**
     * REG-5/6 (DDR-2026-06-30): role/permission ASSIGNMENT is restricted to tenant_admin.
     * These coarse *_MANAGE codes gate the assignment endpoints; holding one is necessary but NOT
     * sufficient — the caller must also be tenant_admin. This closes privilege escalation where a
     * delegated non-admin holding a *_MANAGE code could grant itself (or others) tenant_admin.
     */
    private static final Set<String> ASSIGNMENT_ADMIN_ONLY_CODES = Set.of(
            MetaPermission.USER_ROLE_MANAGE,   // org.user_role.update
            MetaPermission.ROLE_MANAGE,        // org.role.update
            MetaPermission.PERMISSION_MANAGE); // meta.permission.update

    /**
     * Authorization behavior for handlers that have NEITHER {@link RequirePermission} NOR
     * {@link AuthenticatedAccess} — the "un-annotated" surface. Staged default-deny migration:
     * <ul>
     *   <li>{@code allow}  — legacy fail-open: permit silently.</li>
     *   <li>{@code shadow} — permit, but log each unique reached endpoint once (default). This
     *       collects the real-traffic coverage needed to flip to {@code deny} safely.</li>
     *   <li>{@code deny}   — fail-closed / default-deny: reject un-annotated handlers.</li>
     * </ul>
     * Configured via {@code aura.security.authz.unannotated-mode} (default {@code shadow}).
     */
    @Value("${aura.security.authz.unannotated-mode:shadow}")
    private String unannotatedMode = "shadow";

    /** Bounded dedup for shadow logging — log each (method+uri+userId) once to avoid log spam. */
    private final Set<String> shadowSeen = ConcurrentHashMap.newKeySet();

    /** Test seam for the un-annotated mode. */
    void setUnannotatedMode(String mode) {
        this.unannotatedMode = mode;
    }

    /**
     * Pre-handle method to check permission before request processing
     * 
     * @param request HTTP request
     * @param response HTTP response
     * @param handler Handler method
     * @return true if access is allowed, false otherwise
     * @throws Exception if permission check fails
     */
    @Override
    public boolean preHandle(HttpServletRequest request, 
                            HttpServletResponse response, 
                            Object handler) throws Exception {
        
        // 1. Extract @RequirePermission annotation
        RequirePermission annotation = extractAnnotation(handler);
        if (annotation == null) {
            return handleUnannotated(request, handler);
        }

        String permissionTemplate = annotation.value();

        // ✅ 修复: 解析占位符 (支持 {pathVariable} 格式)
        String permissionCode = resolvePlaceholders(permissionTemplate, handler, request);

        log.debug("Permission check: template={}, resolved={}, endpoint={}",
            permissionTemplate, permissionCode, request.getRequestURI());
        
        // 2. Get current user from SecurityContext
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            log.error("User not authenticated: endpoint={}", request.getRequestURI());
            throw new AuthenticationException("User not authenticated") {};
        }
        
        Long userId = extractUserId(auth);
        log.debug("Extracted user ID: userId={}, endpoint={}", 
            userId, request.getRequestURI());
        
        // 2.5 REG-5/6: role/permission ASSIGNMENT requires tenant_admin (in addition to the *_MANAGE
        // code). A delegated non-admin holding a *_MANAGE code must NOT be able to assign roles /
        // permissions (would allow self-escalation to tenant_admin). Bootstrap/system flows call the
        // services directly and bypass this request-scoped interceptor, so they are unaffected.
        if (ASSIGNMENT_ADMIN_ONLY_CODES.contains(permissionCode)) {
            Long assignTenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
            if (assignTenantId == null
                    || !adminRoleChecker.hasRole(assignTenantId, userId, RoleCodes.TENANT_ADMIN)) {
                log.error("Assignment denied — tenant_admin required: userId={}, permission={}, endpoint={}",
                        userId, permissionCode, request.getRequestURI());
                throw new AccessDeniedException(
                        "tenant_admin required for role/permission assignment,permissionCode: " + permissionCode);
            }
        }

        // 3. Check user permission
        boolean hasPermission = userPermissionService.hasPermission(userId, permissionCode);

        // Fallback: some model codes end in page-type suffixes (e.g. sl_price_list ends in _list).
        // PageKeyConverter strips the suffix and checks dynamic.sl_price.read, but the permission
        // in DB is dynamic.sl_price_list.read. If the first check fails, try the raw pageKey form.
        if (!hasPermission && permissionTemplate.contains("{pageKey}")) {
            String rawPermissionCode = resolvePlaceholdersRaw(permissionTemplate, handler, request);
            if (!rawPermissionCode.equals(permissionCode)) {
                hasPermission = userPermissionService.hasPermission(userId, rawPermissionCode);
                if (hasPermission) {
                    log.debug("Permission check passed via raw pageKey fallback: userId={}, permission={}, endpoint={}",
                        userId, rawPermissionCode, request.getRequestURI());
                }
            }
        }

        if (!hasPermission && isReadOnlyModelPagePermission(permissionTemplate)) {
            String menuPermissionCode = resolveMenuPermissionByPageKey(request);
            if (menuPermissionCode != null && !menuPermissionCode.isBlank()) {
                hasPermission = userPermissionService.hasPermission(userId, menuPermissionCode);
                if (hasPermission) {
                    log.debug("Permission check passed via page menu fallback: userId={}, permission={}, endpoint={}",
                        userId, menuPermissionCode, request.getRequestURI());
                }
            }
        }

        if (!hasPermission && isPublishedPageSchemaReadPermission(permissionCode, request)) {
            hasPermission = hasRuntimePageSchemaReadPermission(userId, request);
        }

        if (!hasPermission) {
            if (annotation.optional()) {
                log.warn("Optional permission check failed (allowing access): userId={}, permission={}, endpoint={}",
                    userId, permissionCode, request.getRequestURI());
                return true; // Allow access for optional permission
            }

            log.error("Permission check failed (access denied): userId={}, permission={}, endpoint={}",
                userId, permissionCode, request.getRequestURI());
            throw new AccessDeniedException(annotation.message() + ",permissionCode: "+ permissionCode);
        }
        
        log.debug("Permission check passed: userId={}, permission={}, endpoint={}", 
            userId, permissionCode, request.getRequestURI());
        return true;
    }
    
    /**
     * Decide what to do with a handler that has no {@link RequirePermission}, per the configured
     * un-annotated mode. {@link AuthenticatedAccess}-marked handlers are always allowed (and never
     * shadow-logged) — they are an acknowledged authenticated-only surface.
     */
    private boolean handleUnannotated(HttpServletRequest request, Object handler) {
        if (hasAuthenticatedAccess(handler)) {
            return true;
        }
        String mode = unannotatedMode == null ? "shadow" : unannotatedMode;
        switch (mode) {
            case "deny":
                log.warn("[authz-deny] denied un-annotated handler under default-deny: {} {}",
                    request.getMethod(), request.getRequestURI());
                throw new AccessDeniedException(
                    "Access denied: endpoint declares no @RequirePermission/@AuthenticatedAccess "
                    + "and the authorization policy is default-deny");
            case "allow":
                return true;
            case "shadow":
            default:
                shadowLog(request, handler);
                return true;
        }
    }

    /**
     * Log each unique un-annotated endpoint reached, once, so the real-traffic coverage needed to
     * flip to default-deny can be built from logs (grep {@code [authz-shadow]}). Never affects the
     * request outcome.
     */
    private void shadowLog(HttpServletRequest request, Object handler) {
        try {
            Long userId = null;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof CustomUserDetails details) {
                userId = details.getUserId();
            }
            String handlerSig = handler instanceof HandlerMethod hm
                ? hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName()
                : String.valueOf(handler);
            String key = request.getMethod() + " " + request.getRequestURI() + " u=" + userId;
            if (shadowSeen.size() < 20000 && shadowSeen.add(key)) {
                log.info("[authz-shadow] un-annotated handler reached (would be DENIED under "
                        + "default-deny): method={} uri={} handler={} userId={} tenantId={}",
                    request.getMethod(), request.getRequestURI(), handlerSig,
                    userId, MetaContext.getCurrentTenantId());
            }
        } catch (Exception e) {
            // Shadow logging must never affect the request.
            log.debug("authz-shadow logging failed: {}", e.getMessage());
        }
    }

    /**
     * Whether the handler is marked {@link AuthenticatedAccess} (method-level takes precedence over
     * class-level), i.e. an acknowledged authenticated-only endpoint.
     */
    private boolean hasAuthenticatedAccess(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return false;
        }
        if (handlerMethod.getMethodAnnotation(AuthenticatedAccess.class) != null) {
            return true;
        }
        return handlerMethod.getBeanType().getAnnotation(AuthenticatedAccess.class) != null;
    }

    /**
     * Extract @RequirePermission annotation from handler
     * 
     * <p>Priority:
     * <ol>
     *   <li>Method-level annotation</li>
     *   <li>Class-level annotation</li>
     * </ol>
     * 
     * @param handler Handler object
     * @return RequirePermission annotation or null if not found
     */
    private RequirePermission extractAnnotation(Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return null;
        }
        
        HandlerMethod handlerMethod = (HandlerMethod) handler;
        
        // Check method-level annotation first (higher priority)
        RequirePermission methodAnnotation = 
            handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (methodAnnotation != null) {
            log.trace("Found method-level @RequirePermission: method={}, permission={}", 
                handlerMethod.getMethod().getName(), methodAnnotation.value());
            return methodAnnotation;
        }
        
        // Check class-level annotation
        RequirePermission classAnnotation = 
            handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
        if (classAnnotation != null) {
            log.trace("Found class-level @RequirePermission: class={}, permission={}", 
                handlerMethod.getBeanType().getSimpleName(), classAnnotation.value());
        }
        
        return classAnnotation;
    }

    /**
     * 解析占位符 (支持 {pathVariable} 格式)
     *
     * ✅ 新增: 支持动态资源的细粒度权限控制
     *
     * @param template 占位符模板 (例如: "dynamic.{pageKey}.read")
     * @param handler Handler method
     * @param request HTTP request
     * @return 解析后的权限编码 (例如: "dynamic.user_table.read")
     */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
        java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]+$");

    private String resolvePlaceholders(String template, Object handler, HttpServletRequest request) {
        if (!template.contains("{")) {
            return template;  // 无占位符,直接返回
        }

        if (!(handler instanceof HandlerMethod)) {
            return template;
        }

        // 获取路径变量映射
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> pathVariables =
            (java.util.Map<String, String>) request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE
            );

        if (pathVariables == null || pathVariables.isEmpty()) {
            log.error("No path variables found for permission template: {} — denying access", template);
            throw new AccessDeniedException("Permission evaluation failed: missing path variables for " + template);
        }

        // 替换占位符
        String resolved = template;
        for (java.util.Map.Entry<String, String> entry : pathVariables.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";

            // 转换 pageKey (user-table -> user_table)
            String value = entry.getValue();
            if ("pageKey".equals(entry.getKey())) {
                value = com.auraboot.framework.meta.util.PageKeyConverter.toModelCode(value);
            }

            // Validate path variable value — only safe identifiers allowed
            if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
                log.error("Unsafe path variable value for permission resolution: key={}, value={}",
                    entry.getKey(), value);
                throw new AccessDeniedException("Permission evaluation failed: invalid path variable value");
            }

            resolved = resolved.replace(placeholder, value);
        }

        // Unresolved placeholders = deny access (fail-secure)
        if (resolved.contains("{")) {
            log.error("Unresolved placeholders in permission: {} — denying access", resolved);
            throw new AccessDeniedException("Permission evaluation failed: unresolved placeholders in " + resolved);
        }

        return resolved;
    }

    /**
     * Resolve permission placeholders using RAW path variables (no PageKeyConverter).
     * Used as a fallback when the converted form fails — handles model codes whose names
     * end in page-type suffixes (e.g. sl_price_list whose _list is part of the model name).
     */
    private String resolvePlaceholdersRaw(String template, Object handler, HttpServletRequest request) {
        if (!template.contains("{")) {
            return template;
        }
        if (!(handler instanceof HandlerMethod)) {
            return template;
        }

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> pathVariables =
            (java.util.Map<String, String>) request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE
            );

        if (pathVariables == null || pathVariables.isEmpty()) {
            return template;
        }

        String resolved = template;
        for (java.util.Map.Entry<String, String> entry : pathVariables.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue();
            // Normalize hyphens to underscores but do NOT strip page-type suffixes
            if ("pageKey".equals(entry.getKey())) {
                value = value.replace("-", "_").toLowerCase();
            }
            if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
                return template; // unsafe — return original template unchanged
            }
            resolved = resolved.replace(placeholder, value);
        }

        return resolved.contains("{") ? template : resolved;
    }

    private boolean isReadOnlyModelPagePermission(String permissionTemplate) {
        return "model.{pageKey}.read".equals(permissionTemplate);
    }

    private boolean isPublishedPageSchemaReadPermission(String permissionCode, HttpServletRequest request) {
        return MetaPermission.PAGE_SCHEMA_READ.equals(permissionCode) && rawPageKey(request) != null;
    }

    private boolean hasRuntimePageSchemaReadPermission(Long userId, HttpServletRequest request) {
        String menuPermissionCode = resolveMenuPermissionByPageKey(request);
        if (menuPermissionCode != null
                && !menuPermissionCode.isBlank()
                && userPermissionService.hasPermission(userId, menuPermissionCode)) {
            log.debug("Page schema read passed via menu permission fallback: userId={}, permission={}, endpoint={}",
                    userId, menuPermissionCode, request.getRequestURI());
            return true;
        }

        String pageKey = rawPageKey(request);
        if (pageKey == null) {
            return false;
        }

        String modelCode = com.auraboot.framework.meta.util.PageKeyConverter.toModelCode(pageKey);
        if (modelCode != null && SAFE_IDENTIFIER.matcher(modelCode).matches()) {
            String modelReadPermission = "model." + modelCode + ".read";
            if (userPermissionService.hasPermission(userId, modelReadPermission)) {
                log.debug("Page schema read passed via model permission fallback: userId={}, permission={}, endpoint={}",
                        userId, modelReadPermission, request.getRequestURI());
                return true;
            }
        }

        if (!pageKey.equals(modelCode)) {
            String rawModelReadPermission = "model." + pageKey + ".read";
            if (userPermissionService.hasPermission(userId, rawModelReadPermission)) {
                log.debug("Page schema read passed via raw pageKey model permission fallback: userId={}, permission={}, endpoint={}",
                        userId, rawModelReadPermission, request.getRequestURI());
                return true;
            }
        }

        return false;
    }

    private String resolveMenuPermissionByPageKey(HttpServletRequest request) {
        if (!MetaContext.exists()) {
            return null;
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return null;
        }

        String pageKey = rawPageKey(request);
        if (pageKey == null) {
            return null;
        }

        return menuMapper.findPermissionCodeByPageKey(tenantId, pageKey);
    }

    private String rawPageKey(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> pathVariables =
            (java.util.Map<String, String>) request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE
            );
        if (pathVariables == null || pathVariables.isEmpty()) {
            return null;
        }

        String value = pathVariables.get("pageKey");
        if (value == null) {
            return null;
        }

        value = value.replace("-", "_").toLowerCase();
        return SAFE_IDENTIFIER.matcher(value).matches() ? value : null;
    }

    /**
     * Extract user ID from Authentication object
     *
     * @param auth Authentication object
     * @return User ID
     * @throws AuthenticationException if user ID cannot be extracted
     */
    private Long extractUserId(Authentication auth) {
        Object principal = auth.getPrincipal();
        
        if (principal instanceof CustomUserDetails) {
            CustomUserDetails userDetails = (CustomUserDetails) principal;
            Long userId = userDetails.getUserId();
            
            if (userId == null) {
                log.error("User ID is null in CustomUserDetails: username={}", 
                    userDetails.getUsername());
                throw new AuthenticationException("User ID is null") {};
            }
            
            return userId;
        }
        
        log.error("Invalid authentication principal type: type={}", 
            principal != null ? principal.getClass().getName() : "null");
        throw new AuthenticationException("Invalid authentication principal") {};
    }
}

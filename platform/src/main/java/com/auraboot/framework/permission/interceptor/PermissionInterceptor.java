package com.auraboot.framework.permission.interceptor;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

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
            log.trace("No @RequirePermission annotation found, allowing access: {}",
                request.getRequestURI());
            return true; // No permission check required
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

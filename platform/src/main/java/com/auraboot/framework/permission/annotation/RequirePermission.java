package com.auraboot.framework.permission.annotation;

import java.lang.annotation.*;

/**
 * Annotation to mark methods or classes that require specific permission
 * 
 * <p>This annotation is used to declare that a method or class requires
 * a specific permission to be accessed. The permission check is performed
 * by {@link com.auraboot.framework.permission.interceptor.PermissionInterceptor}.
 * 
 * <p>Usage examples:
 * <pre>
 * // Method-level annotation
 * {@code @RequirePermission("model.model.manage")}
 * public ModelDTO create(ModelCreateRequest request) {
 *     // ...
 * }
 * 
 * // Class-level annotation (applies to all methods)
 * {@code @RequirePermission("page.page.read")}
 * public class PageController {
 *     // All methods require page.page.read permission
 * }
 * 
 * // Optional permission (won't throw exception if missing)
 * {@code @RequirePermission(value = "component.component.admin", optional = true)}
 * public void adminOperation() {
 *     // ...
 * }
 * </pre>
 * 
 * <p>Permission code format: {@code {resource_type}.{resource_code}.{action}[.{scope}]}
 * <p>All segments must be lowercase. Examples: model.model.manage, page.page.read
 * <ul>
 *   <li>resource_type: model, page, component, dict, etc.</li>
 *   <li>resource_code: model, publish, component, etc.</li>
 *   <li>action: manage, read, write, admin, etc.</li>
 *   <li>scope (optional): tenant, global, personal, etc.</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @version 1.0.0
 * @since 2025-01-08
 * @see com.auraboot.framework.permission.interceptor.PermissionInterceptor
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {
    
    /**
     * Permission code required to access this resource
     * 
     * <p>Format: {@code {RESOURCE_TYPE}.{resource_code}.{action}[.{scope}]}
     * 
     * <p>Examples:
     * <ul>
     *   <li>{@code model.model.manage} - Model management permission</li>
     *   <li>{@code page.publish.read} - Publish read permission</li>
     *   <li>{@code component.component.admin} - Component admin permission</li>
     * </ul>
     * 
     * @return permission code (e.g., "model.model.manage")
     */
    String value();
    
    /**
     * Whether this permission check is optional
     * 
     * <p>If true, missing permission will not throw exception.
     * This is useful for degraded functionality where the feature
     * should still be accessible but with limited permissions.
     * 
     * <p>Default is false (permission is required).
     * 
     * @return true if optional, false otherwise
     */
    boolean optional() default false;
    
    /**
     * Custom error message when permission check fails
     * 
     * <p>This message will be included in the AccessDeniedException
     * when the user does not have the required permission.
     * 
     * <p>Default message: "Access denied: required permission not found"
     * 
     * @return custom error message
     */
    String message() default "Access denied: required permission not found";
}

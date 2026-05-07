package com.auraboot.framework.application.config;

import com.auraboot.framework.application.security.AdminRoleInterceptor;
import com.auraboot.framework.environment.web.EnvironmentResolverInterceptor;
import com.auraboot.framework.permission.interceptor.PermissionInterceptor;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.core.convert.converter.Converter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC Configuration
 * 
 * <p>Configures Spring MVC interceptors, including the permission-based
 * access control interceptor.
 * 
 * <p>Registered Interceptors:
 * <ul>
 *   <li>{@link PermissionInterceptor} - Permission-based access control</li>
 * </ul>
 * 
 * <p>Interceptor Paths:
 * <ul>
 *   <li>Included: /api/**</li>
 *   <li>Excluded: /api/auth/**, /api/public/**, /actuator/**</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @version 1.0.0
 * @since 2025-01-08
 * @see PermissionInterceptor
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    //todo confirm add doc WebMvcConfigurer
    private final PermissionInterceptor permissionInterceptor;
    private final AdminRoleInterceptor adminRoleInterceptor;
    private final EnvironmentResolverInterceptor environmentResolverInterceptor;
    
    /**
     * Add interceptors to the registry
     * 
     * <p>Permission Interceptor Configuration:
     * <ul>
     *   <li>Path Pattern: /api/**</li>
     *   <li>Exclude Patterns:
     *     <ul>
     *       <li>/api/auth/** - Authentication endpoints</li>
     *       <li>/api/public/** - Public endpoints</li>
     *       <li>/actuator/** - Actuator endpoints</li>
     *     </ul>
     *   </li>
     * </ul>
     * 
     * @param registry Interceptor registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Admin role guard (design doc 2026-04-19). MUST be registered BEFORE
        // PermissionInterceptor so the coarse "must hold tenant_admin" gate
        // short-circuits before the fine-grained @RequirePermission check —
        // otherwise a non-admin caller would get a confusing 1/AccessDenied
        // from the permission evaluator instead of the canonical 409 envelope.
        registry.addInterceptor(adminRoleInterceptor)
            .addPathPatterns("/api/admin/**");
        log.info("AdminRoleInterceptor registered for /api/admin/**");

        log.info("Registering PermissionInterceptor");
        registry.addInterceptor(permissionInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/auth/**",      // Authentication endpoints (login, signup, etc.)
                "/api/public/**",    // Public endpoints (no authentication required)
                "/actuator/**"       // Actuator endpoints (health, metrics, etc.)
            );
        log.info("PermissionInterceptor registered successfully");

        // env-layering: resolve env from ?env / X-Environment header AFTER permission check (so
        // tenantId is set + caller is authorized) but BEFORE controller handlers.
        registry.addInterceptor(environmentResolverInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns(
                "/api/auth/**",
                "/api/public/**",
                "/actuator/**"
            );
        log.info("EnvironmentResolverInterceptor registered for /api/**");
    }

    /**
     * Register case-insensitive converters for enum types used as @RequestParam.
     * Fixes: "overwrite" (lowercase from CLI) → OVERWRITE (enum constant).
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new Converter<String, ImportRequest.ConflictStrategy>() {
            @Override
            public ImportRequest.ConflictStrategy convert(String source) {
                if (source == null || source.isBlank()) {
                    return ImportRequest.ConflictStrategy.OVERWRITE_SAFE;
                }
                return ImportRequest.ConflictStrategy.valueOf(source.toUpperCase());
            }
        });
    }
}

package com.auraboot.framework.environment.web;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the request's target environment from {@code ?env=<code>} query parameter or the
 * {@code X-Environment} HTTP header, looks it up by (tenantId, code) and stamps it onto
 * {@link MetaContext#setEnvironmentId(Long)}.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code ?env=<code>} query param (highest priority — explicit per-call override)</li>
 *   <li>{@code X-Environment: <code>} request header (e.g. set by frontend env switcher)</li>
 *   <li>Tenant's default environment (auto-create if missing) — falls back via service</li>
 * </ol>
 *
 * <p>Runs after authentication so {@code MetaContext.getCurrentTenantId()} is populated, and is
 * registered <b>before</b> {@code PermissionInterceptor} so any @RequirePermission rule whose
 * SQL hits @EnvScoped tables sees the resolved env_id (otherwise the env-scope WHERE clause
 * sees null and silently evaluates cross-env -- slice 1 review P1-3, 2026-05-07).
 *
 * <p>Cleanup is handled by {@code TenantInterceptor.afterCompletion} which calls
 * {@link MetaContext#clear()} (clears both tenant and env ThreadLocals).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnvironmentResolverInterceptor implements HandlerInterceptor {

    public static final String QUERY_PARAM = "env";
    public static final String HEADER_NAME = "X-Environment";

    private final EnvironmentMapper environmentMapper;
    private final EnvironmentService environmentService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!MetaContext.exists() || MetaContext.getCurrentTenantId() == null) {
            // No tenant context yet (e.g. /api/auth/**) → skip env resolution; downstream code
            // either doesn't touch env-scoped tables or runs in a tenant-less mode.
            return true;
        }

        String code = request.getParameter(QUERY_PARAM);
        if (code == null || code.isBlank()) {
            code = request.getHeader(HEADER_NAME);
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long resolvedEnvId;
        if (code != null && !code.isBlank()) {
            Environment env = environmentMapper.findByTenantAndCode(tenantId, code);
            if (env == null) {
                // Explicit env code that doesn't exist for this tenant — fail fast rather than
                // silently fall back, which would mask configuration mistakes. Returns 404 with
                // canonical ApiResponse envelope (env-layering #18 reviewer fix).
                writeNotFoundResponse(response, code, tenantId);
                return false;
            }
            resolvedEnvId = env.getId();
        } else {
            // No env hint → use tenant's default (auto-create if missing).
            resolvedEnvId = environmentService.findOrCreateDefaultId(tenantId);
        }

        MetaContext.setEnvironmentId(resolvedEnvId);
        log.debug("Resolved environment {} for tenant {}", resolvedEnvId, tenantId);
        return true;
    }

    private void writeNotFoundResponse(HttpServletResponse response, String code, Long tenantId) {
        log.warn("Environment '{}' not found for tenant {}", code, tenantId);
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Void> body = ApiResponse.error(
                ResponseCode.PluginNotFound,  // closest existing not-found code
                java.util.Map.of("envCode", code, "tenantId", tenantId));
        try {
            response.getOutputStream().write(
                    objectMapper.writeValueAsBytes(body));
            response.getOutputStream().flush();
        } catch (Exception writeFail) {
            log.warn("Failed to write 404 envelope for env '{}': {}", code, writeFail.getMessage());
        }
    }
}

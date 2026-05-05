package com.auraboot.framework.application.security;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * URL-prefix admin guard for {@code /api/admin/**}.
 *
 * <p>Enforces that the authenticated caller holds the
 * {@link com.auraboot.framework.permission.enums.RoleCodes#TENANT_ADMIN}
 * role in the current tenant. Replaces the per-controller
 * {@code guardTenantAdmin()} pattern (originally introduced as a Round-2
 * temporary fix on {@code UserSoulProfileAdminController}) with a single
 * default-deny choke point. See design doc
 * {@code docs/plans/2026-04/2026-04-19-platform-admin-guard-design.md} — Plan C.
 *
 * <p><b>Audit:</b> as of 2026-04-29 the platform ships exactly <b>11</b> admin
 * controllers (8 framework + USP + Memory tier + Agent run replay):
 * <pre>
 * /api/admin/agent-runs            AgentRunController
 * /api/admin/cloud-config          CloudConfigController
 * /api/admin/environments          EnvironmentController
 * /api/admin/exchange-rates        ExchangeRateController
 * /api/admin/i18n                  I18nAdminController
 * /api/admin/infrastructure        InfrastructureController
 * /api/admin/login-channels        LoginChannelManageController
 * /api/admin/memory                MemoryTierAdminController
 * /api/admin/timezone              TimezoneMigrationController
 * /api/admin/user-soul-profiles    UserSoulProfileAdminController
 * /api/admin/users                 AdminUserController
 * </pre>
 * Grep <code>@RequestMapping(&quot;/api/admin</code> under
 * {@code platform/src/main/java} should return exactly 11 matches; CI / reviewers
 * must update this JavaDoc count whenever a new admin controller is added.
 *
 * <p><b>Error contract:</b> returns HTTP 200 body with
 * {@code ApiResponse{code:"409", message:"admin role required"}} — matching the
 * project's uniform response envelope and the retired USP guard behaviour so
 * {@code GlobalExceptionHandler} and frontend code need no changes.
 *
 * <p><b>Ordering:</b> runs after {@code JwtAuthenticationFilter} (which fills
 * {@link MetaContext}) and before Spring MVC dispatch. If {@code MetaContext}
 * has no {@code tenantId} or {@code userId} we return 409 (not 401 — the filter
 * chain has already accepted the caller as authenticated).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminRoleInterceptor implements HandlerInterceptor {

    public static final String DENY_MESSAGE = "admin role required";
    public static final int DENY_CODE = 409;

    private final AdminRoleChecker adminRoleChecker;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        Long tenantId;
        Long userId;
        try {
            tenantId = MetaContext.getCurrentTenantId();
            userId = MetaContext.getCurrentUserId();
        } catch (IllegalStateException notInitialized) {
            // MetaContext throws when no context has been set for the current
            // thread (e.g. the JwtAuthenticationFilter did not populate it —
            // should be unreachable once upstream filters reject unauthenticated
            // traffic, but we keep the branch so we surface 409 instead of 500).
            log.warn("AdminRoleInterceptor: no MetaContext on thread for {}: {}",
                    request.getRequestURI(), notInitialized.getMessage());
            writeDenied(response);
            return false;
        }
        if (tenantId == null || userId == null) {
            log.warn("AdminRoleInterceptor: incomplete MetaContext for {} (tenantId={}, userId={})",
                    request.getRequestURI(), tenantId, userId);
            writeDenied(response);
            return false;
        }
        if (!adminRoleChecker.isTenantAdmin(tenantId, userId)) {
            log.warn("AdminRoleInterceptor: rejected non-admin for {} (tenantId={}, userId={})",
                    request.getRequestURI(), tenantId, userId);
            writeDenied(response);
            return false;
        }
        return true;
    }

    private void writeDenied(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        byte[] body = objectMapper.writeValueAsBytes(
                ApiResponse.error(DENY_CODE, DENY_MESSAGE));
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }
}

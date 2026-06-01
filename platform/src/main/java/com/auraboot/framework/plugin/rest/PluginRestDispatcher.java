package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.AuthPolicy;
import com.auraboot.framework.plugin.pf4j.RestEndpointRegistry;
import com.auraboot.framework.saas.config.service.SystemModeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Single platform-owned dispatcher for plugin-contributed REST endpoints, mounted at
 * {@code /api/ext/{namespace}/**}. Mirrors the CommandController "one controller, many
 * extensions" model so every plugin HTTP request flows through the governed pipeline:
 * JWT auth + tenant context come free from the security filter chain; permission is
 * enforced here; transaction + audit land in gamma-2.
 *
 * <p>Plugins never register Spring controllers. This static mapping never changes; only the
 * {@link RestEndpointRegistry} index changes as plugins load/unload.
 *
 * <p>Mounted under {@code /api/ext} (NOT {@code /api/plugins}) to avoid colliding with the
 * plugin-management controllers (PluginController / PluginImportController / ...).
 */
@Slf4j
@RestController
@RequestMapping("/api/ext")
@RequiredArgsConstructor
public class PluginRestDispatcher {

    /** Per-IP requests/minute budget for PUBLIC plugin endpoints (mandatory abuse guard). */
    private static final int PUBLIC_RATE_LIMIT_PER_MIN = 60;
    private static final int SC_TOO_MANY_REQUESTS = 429; // not present as a constant in HttpServletResponse

    private final RestEndpointRegistry registry;
    private final UserPermissionService userPermissionService;
    private final PluginRequestContextFactory contextFactory;
    private final RestEndpointPipeline pipeline;
    private final ApiRateLimiter rateLimiter;
    private final SystemModeService systemModeService;

    @RequestMapping("/{namespace}/**")
    public void dispatch(@PathVariable String namespace,
                         HttpServletRequest httpReq,
                         HttpServletResponse httpRes) throws Exception {
        String subPath = subPath(httpReq, namespace);
        Optional<RestEndpointRegistry.Match> matched =
                registry.match(namespace, httpReq.getMethod(), subPath);
        if (matched.isEmpty()) {
            httpRes.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        RestEndpointRegistry.Match m = matched.get();

        if (m.route().authPolicy() == AuthPolicy.PUBLIC) {
            dispatchPublic(namespace, m, httpReq, httpRes);
            return;
        }
        dispatchAuthenticated(m, httpReq, httpRes);
    }

    /**
     * AUTHENTICATED path (gamma-1/2): the JWT filter already set MetaContext + cleared it; we only
     * enforce the declared permission, then run the governed pipeline with a non-public context.
     */
    private void dispatchAuthenticated(RestEndpointRegistry.Match m,
                                       HttpServletRequest httpReq,
                                       HttpServletResponse httpRes) throws Exception {
        Long userId = MetaContext.getCurrentUserId();
        String permission = m.route().permissionCode();
        if (permission == null || permission.isBlank()
                || userId == null
                || !userPermissionService.hasPermission(userId, permission)) {
            throw new AccessDeniedException("Missing permission for plugin route: " + permission);
        }
        runAndFlush(m, httpReq, httpRes, false);
    }

    /**
     * PUBLIC path (gamma-3): the security WhiteList exposed {@code /api/ext/*&#47;public/**} so the
     * JWT filter was skipped — meaning MetaContext is empty. We rate-limit by client IP, bind a
     * default-tenant public context (userId 0) for the duration of the request so the governed
     * pipeline's CRUD + audit are still tenant-scoped, run the pipeline (audit is mandatory and
     * happens inside it), then clear the context. Declare-and-serve: no approval gate (DDR D6).
     */
    private void dispatchPublic(String namespace,
                                RestEndpointRegistry.Match m,
                                HttpServletRequest httpReq,
                                HttpServletResponse httpRes) throws Exception {
        String ip = clientIp(httpReq);
        if (!rateLimiter.isAllowed("ext-public:" + namespace + ":" + ip, PUBLIC_RATE_LIMIT_PER_MIN)) {
            writeError(httpRes, SC_TOO_MANY_REQUESTS, "Too many requests");
            return;
        }
        Long defaultTenant = systemModeService.getDefaultTenantId();
        MetaContext.setContext(defaultTenant, 0L, "public", "public");
        try {
            runAndFlush(m, httpReq, httpRes, true);
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * Run the handler through the governed pipeline (tx + audit + idempotency + schema). The handler
     * writes into an in-memory buffer; we flush it to the servlet only after the pipeline (and its
     * transaction) succeeds, so a rollback never leaks a partial response and we can still emit a
     * clean error status.
     */
    private void runAndFlush(RestEndpointRegistry.Match m,
                             HttpServletRequest httpReq,
                             HttpServletResponse httpRes,
                             boolean isPublic) throws IOException {
        ServletPluginHttpRequest req = new ServletPluginHttpRequest(httpReq, m.pathVars());
        try {
            BufferingPluginHttpResponse buffered = pipeline.execute(m, req, contextFactory.current(isPublic));
            buffered.flushTo(httpRes);
        } catch (ValidationException e) {
            writeError(httpRes, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (BusinessException e) {
            writeError(httpRes, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Plugin REST endpoint {} {} failed", httpReq.getMethod(), httpReq.getRequestURI(), e);
            writeError(httpRes, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private void writeError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        String safe = message == null ? "" : message.replace("\\", "\\\\").replace("\"", "\\\"");
        res.getOutputStream().write(("{\"error\":\"" + safe + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private String subPath(HttpServletRequest req, String namespace) {
        String uri = req.getRequestURI();
        String prefix = "/api/ext/" + namespace;
        return uri.length() > prefix.length() ? uri.substring(prefix.length()) : "/";
    }
}

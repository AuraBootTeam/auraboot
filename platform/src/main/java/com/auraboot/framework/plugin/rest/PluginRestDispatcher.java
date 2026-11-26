package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.plugin.extension.PluginCommandRejectionException;
import com.auraboot.framework.plugin.extension.DataAccessorException;
import com.auraboot.framework.plugin.extension.DataAccessErrorCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.plugin.extension.AuthPolicy;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.RestPublicationPublisher;
import com.auraboot.framework.plugin.extension.RestPublicationCommand;
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
import java.util.Map;
import java.util.Optional;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

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
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
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
    private final RestPublicationStore publications;
    private final RestPublicationCommandExecutor publicationCommands;

    PluginRestDispatcher(RestEndpointRegistry registry, UserPermissionService userPermissionService,
                         PluginRequestContextFactory contextFactory, RestEndpointPipeline pipeline,
                         ApiRateLimiter rateLimiter, SystemModeService systemModeService) {
        this(registry, userPermissionService, contextFactory, pipeline, rateLimiter, systemModeService, null, null);
    }

    @AuthenticatedAccess("Dispatch delegates authorization to the registered route policy and permission guard")
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
        Long userId = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
        String permission = m.route().permissionCode();
        if (permission == null || permission.isBlank()
                || userId == null
                || !userPermissionService.hasPermission(userId, permission)) {
            throw new AccessDeniedException("Missing permission for plugin route: " + permission);
        }
        runAndFlush(m, httpReq, httpRes, false, null, null);
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
        var command = m.extension().publicationCommand(m.route());
        if (command != null) {
            dispatchPublishedCommand(namespace, m, httpReq, httpRes, command);
            return;
        }
        RestPublicationStore.Published publication = null;
        var declaration = m.extension().publicationRead(m.route());
        if (declaration != null) {
            if (publications == null || !"GET".equals(m.route().method()) || !m.route().readOnlyTx()) {
                throw new AccessDeniedException("Invalid public publication route");
            }
            publication = publications.find(namespace, declaration.kind(), m.pathVars().get(declaration.pathVariable()))
                    .orElse(null);
            if (publication == null) {
                writeError(httpRes, HttpServletResponse.SC_NOT_FOUND, "Publication unavailable");
                return;
            }
        }
        Long tenant = publication == null ? systemModeService.getDefaultTenantId() : publication.tenantId();
        MetaContext.setContext(tenant, 0L, "public", "public");
        try {
            runAndFlush(m, httpReq, httpRes, true, publication, null);
        } finally {
            MetaContext.clear();
        }
    }

    private record PublishedInvocation(Map<String, Object> input, Function<Map<String, Object>, String> execute) {}

    private void dispatchPublishedCommand(String namespace, RestEndpointRegistry.Match m,
                                          HttpServletRequest request, HttpServletResponse response,
                                          RestPublicationCommand command) throws IOException {
        if (publications == null || publicationCommands == null || !"POST".equals(m.route().method())
                || m.route().readOnlyTx() || m.route().idempotent()
                || !command.publication().equals(m.extension().publicationCors(m.route()))) {
            throw new AccessDeniedException("Invalid publication command route");
        }
        var declaration = command.publication();
        String id = m.pathVars().get(declaration.pathVariable());
        String origin = request.getHeader("Origin");
        var grant = publications.commandGrant(namespace, declaration.kind(), id, origin).orElse(null);
        if (grant == null || !command.code().equals(grant.command())) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "Publication unavailable");
            return;
        }
        var executed = new AtomicBoolean();
        var invocation = new PublishedInvocation(grant.input(), payload -> {
            var current = publications.commandGrant(namespace, declaration.kind(), id, origin).orElse(null);
            if (!executed.compareAndSet(false, true) || !grant.equals(current)) {
                throw new AccessDeniedException("Publication command unavailable");
            }
            return publicationCommands.execute(current, command, payload);
        });
        MetaContext.setContext(grant.tenantId(), 0L, "public", "public");
        try {
            runAndFlush(m, request, response, true, null, invocation);
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
                             boolean isPublic, RestPublicationStore.Published publication,
                             PublishedInvocation invocation) throws IOException {
        ServletPluginHttpRequest req = new ServletPluginHttpRequest(httpReq, m.pathVars());
        try {
            var models=m.extension().tenantProjectionReadModels(m.route());
            if(models==null)throw new AccessDeniedException("Invalid route projection declaration");
            var context=models.isEmpty()?contextFactory.current(isPublic):contextFactory.current(isPublic,m.route(),models);
            if (invocation != null) {
                context = new PublicationContext(context, null, Map.of(), invocation);
            } else if (publication != null) {
                context = new PublicationContext(context, null, publication.document(), null);
            } else if (!isPublic && publications != null && !m.route().readOnlyTx()) {
                context = new PublicationContext(context,
                        publications.publisher(m.extension().namespace(), context.tenantId(), context.userId()), Map.of(), null);
            }
            BufferingPluginHttpResponse buffered = pipeline.execute(m, req, context);
            buffered.flushTo(httpRes);
        } catch (ValidationException e) {
            writeError(httpRes, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (ConflictException e) {
            writeError(httpRes, HttpServletResponse.SC_CONFLICT, e.getMessage());
        } catch (PluginCommandRejectionException e) {
            ResponseCode responseCode = switch (e.code()) {
                case INVALID_ARGUMENT, BUSINESS_RULE_BLOCKED -> ResponseCode.BadParam;
            };
            // Match the command SPI boundary; GlobalExceptionHandler owns locale resolution.
            throw new BusinessException(responseCode, "$i18n:" + e.messageKey(), e, e.messageArgs());
        } catch (BusinessException e) {
            throw e;
        } catch (SecurityException | AccessDeniedException e) {
            // Preserve authorization classification without exposing internal record identities.
            throw new AccessDeniedException("Access denied", e);
        } catch (RuntimeException e) {
            if (e instanceof DataAccessorException dataAccess
                    && dataAccess.code() == DataAccessErrorCode.PERMISSION_DENIED) {
                // Match the command boundary: preserve only the typed authorization verdict.
                throw new AccessDeniedException("Access denied", dataAccess);
            }
            log.error("Plugin REST endpoint {} {} failed", httpReq.getMethod(), httpReq.getRequestURI(), e);
            writeError(httpRes, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
        }
    }

    private record PublicationContext(PluginRequestContext delegate, RestPublicationPublisher publisher,
                                      Map<String, Object> publishedDocument, PublishedInvocation invocation) implements PluginRequestContext {
        @Override public Long tenantId() { return delegate.tenantId(); }
        @Override public Long userId() { return delegate.userId(); }
        @Override public ZoneId zoneId() { return delegate.zoneId(); }
        @Override public String generateId() { return delegate.generateId(); }
        @Override public boolean isPublic() { return delegate.isPublic(); }
        @Override public DataAccessor dataAccessor() {
            if (isPublic()) throw new AccessDeniedException("Published documents do not grant business data access");
            return delegate.dataAccessor();
        }
        @Override public IdentityDirectoryAccessor.UserIdentity findActiveTenantUser(String userPid) {
            return delegate.findActiveTenantUser(userPid);
        }
        @Override public Map<String, Object> publishedCommandInput() {
            return invocation == null ? Map.of() : invocation.input();
        }
        @Override public String executePublishedCommand(Map<String, Object> payload) {
            if (invocation == null) return PluginRequestContext.super.executePublishedCommand(payload);
            return invocation.execute().apply(payload);
        }
        @Override public RestPublicationPublisher publicationPublisher() {
            return publisher == null ? PluginRequestContext.super.publicationPublisher() : publisher;
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

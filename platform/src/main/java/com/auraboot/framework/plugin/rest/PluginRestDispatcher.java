package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.pf4j.RestEndpointRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private final RestEndpointRegistry registry;
    private final UserPermissionService userPermissionService;
    private final PluginRequestContextFactory contextFactory;

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

        // gamma-1: AUTHENTICATED routes only — enforce the declared permission code.
        // (PUBLIC declare-and-serve handling arrives in gamma-3.)
        Long userId = MetaContext.getCurrentUserId();
        String permission = m.route().permissionCode();
        if (permission == null || permission.isBlank()
                || userId == null
                || !userPermissionService.hasPermission(userId, permission)) {
            throw new AccessDeniedException("Missing permission for plugin route: " + permission);
        }

        ServletPluginHttpRequest req = new ServletPluginHttpRequest(httpReq, m.pathVars());
        ServletPluginHttpResponse res = new ServletPluginHttpResponse(httpRes);
        m.extension().handle(req, res, contextFactory.current(false));
    }

    private String subPath(HttpServletRequest req, String namespace) {
        String uri = req.getRequestURI();
        String prefix = "/api/ext/" + namespace;
        return uri.length() > prefix.length() ? uri.substring(prefix.length()) : "/";
    }
}

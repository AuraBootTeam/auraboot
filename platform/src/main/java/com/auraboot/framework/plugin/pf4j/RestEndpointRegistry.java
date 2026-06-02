package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.AuthPolicy;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import com.auraboot.framework.plugin.rest.RestRouteMatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Registry + dispatch index for {@link RestEndpointExtension}s. Mirrors {@link ExtensionRegistry}
 * but recomputes the extension list on each lookup, so runtime plugin enable / disable / reload
 * (PF4J hot-load/unload) is reflected immediately without a separate cache-invalidation hook.
 *
 * <p>gamma-1 favours correctness over micro-perf here; a cached + lifecycle-event-invalidated
 * variant (like ExtensionRegistry) is a perf follow-up.
 */
@Slf4j
@Service
public class RestEndpointRegistry {

    private final AuraPluginManager pluginManager;
    private final ObjectProvider<RestEndpointExtension> coreProvider;

    public RestEndpointRegistry(AuraPluginManager pluginManager,
                                ObjectProvider<RestEndpointExtension> coreProvider) {
        this.pluginManager = pluginManager;
        this.coreProvider = coreProvider;
    }

    /** A matched route: the owning extension, the matched route, and resolved path variables. */
    public record Match(RestEndpointExtension extension, RestRoute route, Map<String, String> pathVars) {
    }

    /** All REST endpoint extensions (plugin + core), recomputed each call (hot-reload safe). */
    public List<RestEndpointExtension> getAll() {
        List<RestEndpointExtension> plugin = pluginManager.getExtensionsOfType(RestEndpointExtension.class);
        List<RestEndpointExtension> core = coreProvider.stream().toList();
        return Stream.concat(plugin.stream(), core.stream()).toList();
    }

    /**
     * Resolve the highest-priority extension+route matching {@code (namespace, method, subPath)}.
     * {@code subPath} is the request path with the {@code /api/ext/{namespace}} prefix removed.
     */
    public Optional<Match> match(String namespace, String method, String subPath) {
        Match best = null;
        int bestPriority = Integer.MIN_VALUE;
        for (RestEndpointExtension ext : getAll()) {
            if (namespace == null || !namespace.equals(ext.namespace())) {
                continue;
            }
            for (RestRoute route : ext.routes()) {
                // Fail-closed: refuse to match a misconfigured route (effectively 404) and log
                // loudly so the plugin author is caught at the first request rather than silently
                // shadow-banning the route.
                String reject = misconfigurationReason(route);
                if (reject != null) {
                    log.error("Refusing plugin REST route {} {} from namespace '{}': {}",
                            route.method(), route.pathPattern(), ext.namespace(), reject);
                    continue;
                }
                Optional<Map<String, String>> vars = RestRouteMatcher.match(route, method, subPath);
                if (vars.isPresent() && ext.getPriority() > bestPriority) {
                    best = new Match(ext, route, vars.get());
                    bestPriority = ext.getPriority();
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Public routes are exposed unauthenticated only under this subpath (matches the security WhiteList). */
    public static final String PUBLIC_SUBPATH_PREFIX = "/public/";

    /** @return a human-readable reason this route must not be served, or {@code null} if it is well-formed. */
    private static String misconfigurationReason(RestRoute route) {
        if (route.authPolicy() == AuthPolicy.AUTHENTICATED
                && (route.permissionCode() == null || route.permissionCode().isBlank())) {
            return "AUTHENTICATED routes require a non-blank permissionCode (fail-closed)";
        }
        if (route.authPolicy() == AuthPolicy.PUBLIC
                && !route.pathPattern().startsWith(PUBLIC_SUBPATH_PREFIX)) {
            return "PUBLIC routes must live under the " + PUBLIC_SUBPATH_PREFIX
                    + " subpath so the security WhiteList exposes them (fail-closed)";
        }
        return null;
    }
}

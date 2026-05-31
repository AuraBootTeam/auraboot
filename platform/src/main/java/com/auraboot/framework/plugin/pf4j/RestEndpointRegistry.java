package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import com.auraboot.framework.plugin.rest.RestRouteMatcher;
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
                Optional<Map<String, String>> vars = RestRouteMatcher.match(route, method, subPath);
                if (vars.isPresent() && ext.getPriority() > bestPriority) {
                    best = new Match(ext, route, vars.get());
                    bestPriority = ext.getPriority();
                }
            }
        }
        return Optional.ofNullable(best);
    }
}

package com.auraboot.framework.plugin.pf4j;

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
 * Registry + dispatch index for {@link RestEndpointExtension}s. Mirrors {@link ExtensionRegistry}:
 * merges PF4J plugin extensions with core Spring-bean extensions, caches lazily, and clears the
 * cache on plugin lifecycle changes via {@link #refresh()}.
 */
@Slf4j
@Service
public class RestEndpointRegistry {

    private final AuraPluginManager pluginManager;
    private final ObjectProvider<RestEndpointExtension> coreProvider;

    private volatile List<RestEndpointExtension> all;

    public RestEndpointRegistry(AuraPluginManager pluginManager,
                                ObjectProvider<RestEndpointExtension> coreProvider) {
        this.pluginManager = pluginManager;
        this.coreProvider = coreProvider;
    }

    /** A matched route: the owning extension, the matched route, and resolved path variables. */
    public record Match(RestEndpointExtension extension, RestRoute route, Map<String, String> pathVars) {
    }

    /** All REST endpoint extensions (plugin + core), cached after first call. */
    public List<RestEndpointExtension> getAll() {
        if (all == null) {
            List<RestEndpointExtension> plugin = pluginManager.getExtensionsOfType(RestEndpointExtension.class);
            List<RestEndpointExtension> core = coreProvider.stream().toList();
            all = Stream.concat(plugin.stream(), core.stream()).toList();
        }
        return all;
    }

    /**
     * Resolve the highest-priority extension+route matching {@code (namespace, method, subPath)}.
     * {@code subPath} is the request path with the {@code /api/plugins/{namespace}} prefix removed.
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

    /** Invalidate the cache; call from plugin install/enable/disable/reload hooks. */
    public void refresh() {
        all = null;
        log.info("RestEndpointRegistry cache cleared; routes reload on demand");
    }
}

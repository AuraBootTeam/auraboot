package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;

/**
 * G-CYCLE: Detects circular dependencies in the plugin dependency graph.
 * <p>
 * Builds a graph from all installed plugins plus the plugin being imported,
 * then performs DFS to detect cycles.
 */
@Component
public class CircularDependencyValidator implements PluginValidator {

    @Override
    public String category() {
        return "governance";
    }

    @Override
    public boolean requiresReferenceValidation() {
        return true;
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        PluginManifestExtended manifest = ctx.getManifest();

        // Build dependency graph: pluginId → set of dependency pluginIds
        Map<String, Set<String>> graph = new HashMap<>();

        // Add installed plugins
        if (ctx.getInstalledPluginDependencies() != null) {
            for (Map.Entry<String, List<String>> entry : ctx.getInstalledPluginDependencies().entrySet()) {
                graph.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
        }

        // Add the current plugin being imported
        Set<String> currentDeps = new HashSet<>();
        for (PluginManifest.PluginDependencySpec spec : manifest.getEffectiveDependencySpecs()) {
            currentDeps.add(spec.getPluginId());
        }
        graph.put(ctx.getPluginId(), currentDeps);

        // Detect cycle using DFS from the current plugin
        List<String> cycle = detectCycle(graph, ctx.getPluginId());
        if (cycle != null) {
            return List.of(error("G-CYCLE", category(),
                    "Circular dependency detected: " + String.join(" → ", cycle)));
        }
        return List.of();
    }

    /**
     * Detect a cycle in the dependency graph starting from the given node.
     * Returns the cycle path if found, null otherwise.
     */
    static List<String> detectCycle(Map<String, Set<String>> graph, String startNode) {
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        List<String> path = new ArrayList<>();

        if (dfs(graph, startNode, visited, inStack, path)) {
            // Find the cycle start in path
            String cycleStart = path.get(path.size() - 1);
            int idx = path.indexOf(cycleStart);
            List<String> cycle = new ArrayList<>(path.subList(idx, path.size()));
            cycle.add(cycleStart); // Close the cycle
            return cycle;
        }
        return null;
    }

    private static boolean dfs(Map<String, Set<String>> graph, String node,
                               Set<String> visited, Set<String> inStack, List<String> path) {
        if (inStack.contains(node)) {
            path.add(node); // Mark the cycle start
            return true;
        }
        if (visited.contains(node)) {
            return false;
        }

        visited.add(node);
        inStack.add(node);
        path.add(node);

        Set<String> deps = graph.getOrDefault(node, Set.of());
        for (String dep : deps) {
            if (dfs(graph, dep, visited, inStack, path)) {
                return true;
            }
        }

        inStack.remove(node);
        path.remove(path.size() - 1);
        return false;
    }
}

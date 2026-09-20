package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.DecisionUsageSourceContributor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contributors registered by product application modules so usage-index rebuilds
 * can pull product-owned sources ({@link DecisionUsageSourceContributor} beans
 * live in plugin child contexts the host cannot otherwise reach).
 */
@Slf4j
@Service
public class DecisionUsageSourceRegistry {

    private final Map<String, List<DecisionUsageSourceContributor>> contributorsByPlugin =
            new LinkedHashMap<>();

    public synchronized void register(String pluginId, DecisionUsageSourceContributor contributor) {
        if (contributor == null) {
            return;
        }
        contributorsByPlugin.computeIfAbsent(pluginId, key -> new ArrayList<>()).add(contributor);
    }

    public synchronized void unregister(String pluginId) {
        contributorsByPlugin.remove(pluginId);
    }

    /** Pull a fresh projection from every registered product; failures never block the rebuild. */
    public int republishAll() {
        int published = 0;
        for (Map.Entry<String, List<DecisionUsageSourceContributor>> entry :
                contributorsByPlugin.entrySet()) {
            for (DecisionUsageSourceContributor contributor : entry.getValue()) {
                try {
                    published += contributor.republishSources();
                } catch (RuntimeException error) {
                    log.warn("Usage-source republish failed for plugin {}: {}",
                            entry.getKey(), error.getMessage(), error);
                }
            }
        }
        return published;
    }
}

package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.WorkflowCapability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Single-provider, fail-closed workflow capability registry. */
@Service
public class WorkflowCapabilityRegistry {
    private final Map<String, WorkflowCapability> providers = new LinkedHashMap<>();
    private final ObjectProvider<WorkflowCapability> localProviders;

    public WorkflowCapabilityRegistry(ObjectProvider<WorkflowCapability> localProviders) {
        this.localProviders = localProviders;
    }

    public synchronized void register(String pluginId, WorkflowCapability provider) {
        if (provider == null) return;
        if (!providers.isEmpty() && !providers.containsKey(pluginId)) {
            throw new IllegalStateException("workflow capability already owned by " + providers.keySet().iterator().next());
        }
        providers.put(pluginId, provider);
    }

    public synchronized void unregister(String pluginId) { providers.remove(pluginId); }

    public WorkflowCapability.WorkflowResult execute(String operation, WorkflowCapability.WorkflowRequest request) {
        WorkflowCapability provider;
        synchronized (this) {
            provider = providers.values().stream().findFirst()
                    .or(() -> localProviders.orderedStream().findFirst()).orElseThrow(
                    () -> new IllegalStateException("workflow capability unavailable: " + operation));
        }
        if (!provider.operations().contains(operation)) {
            throw new IllegalStateException("workflow operation unavailable: " + operation);
        }
        return provider.execute(operation, request);
    }

    public synchronized boolean available(String operation) {
        return providers.values().stream().anyMatch(provider -> provider.operations().contains(operation))
                || localProviders.orderedStream().anyMatch(provider -> provider.operations().contains(operation));
    }
}

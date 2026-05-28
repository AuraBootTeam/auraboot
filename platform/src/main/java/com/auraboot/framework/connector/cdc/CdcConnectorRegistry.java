package com.auraboot.framework.connector.cdc;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-process registry of live {@link CdcEngine} instances, keyed by
 * {@link CdcEngine#getEngineId() engineId}.
 *
 * <p>Future: persistence and worker-node lease coordination live in
 * {@code ab_connector_cdc_engine} (see {@code AbConnectorCdcEngine} entity);
 * this registry only holds in-memory handles on the current JVM.
 *
 * @since 5.3.0
 */
@Component
public class CdcConnectorRegistry {

    private final Map<String, CdcEngine> engines = new ConcurrentHashMap<>();

    /**
     * Register a fresh engine. Throws if {@code engineId} already exists — callers
     * should {@link #unregister(String)} before re-registering.
     */
    public CdcEngine register(CdcEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        CdcEngine prior = engines.putIfAbsent(engine.getEngineId(), engine);
        if (prior != null) {
            throw new IllegalStateException("Engine already registered: " + engine.getEngineId());
        }
        return engine;
    }

    public Optional<CdcEngine> lookup(String engineId) {
        if (engineId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(engines.get(engineId));
    }

    /**
     * Remove an engine from the registry. Caller is responsible for first
     * invoking {@link CdcEngine#stop()}.
     */
    public void unregister(String engineId) {
        if (engineId != null) {
            engines.remove(engineId);
        }
    }

    public Collection<CdcEngine> list() {
        return Collections.unmodifiableCollection(engines.values());
    }

    public int size() {
        return engines.size();
    }
}

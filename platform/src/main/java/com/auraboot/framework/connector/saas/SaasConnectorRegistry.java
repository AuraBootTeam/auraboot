package com.auraboot.framework.connector.saas;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Registry of {@link AbstractSaasConnectorAdapter} beans, keyed by vendor
 * (mirrors the {@code CdcConnectorRegistry} pattern, PRD 18 §B.3.2).
 *
 * <p>Spring auto-injects every {@link AbstractSaasConnectorAdapter} bean at startup;
 * tests can also register adapters programmatically via {@link #register}.
 *
 * @since 5.3.0
 */
@Component
public final class SaasConnectorRegistry {

    private final Map<String, AbstractSaasConnectorAdapter> adapters = new ConcurrentHashMap<>();

    public SaasConnectorRegistry() {
    }

    @Autowired(required = false)
    public SaasConnectorRegistry(List<AbstractSaasConnectorAdapter> beans) {
        if (beans != null) {
            for (AbstractSaasConnectorAdapter adapter : beans) {
                register(adapter);
            }
        }
    }

    public AbstractSaasConnectorAdapter register(AbstractSaasConnectorAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        String vendor = adapter.descriptor().protocolType();
        AbstractSaasConnectorAdapter prior = adapters.putIfAbsent(vendor, adapter);
        if (prior != null && prior != adapter) {
            throw new IllegalStateException("SaaS adapter already registered: " + vendor);
        }
        return adapter;
    }

    public Optional<AbstractSaasConnectorAdapter> lookupByVendor(String vendor) {
        if (vendor == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(adapters.get(vendor));
    }

    public Collection<AbstractSaasConnectorAdapter> listAll() {
        return Collections.unmodifiableCollection(adapters.values());
    }

    public int size() {
        return adapters.size();
    }
}

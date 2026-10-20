package com.auraboot.framework.plugin.extension.integration;

import java.util.Map;

/** Host-injected immutable installation identity; never deserialize this from a request. */
public record InstalledIntegrationBinding(long tenantId, String applicationPid, String installationPid,
        String sourceCode, String externalEventId, String durableEventId, long bindingRevision,
        Map<String, Object> parameters) {
    public static final String SETTINGS_KEY = "__installedIntegration";
    public InstalledIntegrationBinding {
        if (tenantId <= 0 || bindingRevision <= 0) throw new IllegalArgumentException("Invalid binding identity");
        // Reuse the integration envelope's bounded, deeply immutable JSON representation.
        parameters = new IntegrationEventEnvelope("1.0", durableEventId, sourceCode, sourceCode,
                externalEventId, java.time.Instant.EPOCH, tenantId, durableEventId, null,
                durableEventId, 0, parameters, Map.of()).payload();
    }
}

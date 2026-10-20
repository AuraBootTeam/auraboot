package com.auraboot.framework.plugin.extension.integration;

import java.time.Instant;
import java.util.Objects;

/** Verified external envelope facts injected by the host, separate from business request data. */
public record InstalledIntegrationEvent(String eventType, long sequence, Instant occurredAt,
        String subjectType, String subjectPid) {
    public static final String SETTINGS_KEY = "__installedIntegrationEvent";
    public InstalledIntegrationEvent {
        if(eventType==null || !eventType.matches("external\\.[a-z0-9.-]+\\.v[1-9][0-9]*") || sequence<0
                || subjectType==null || subjectType.isBlank() || subjectPid==null || subjectPid.isBlank())
            throw new IllegalArgumentException("Invalid installed event identity");
        Objects.requireNonNull(occurredAt);
    }
}

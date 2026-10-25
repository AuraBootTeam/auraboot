package com.auraboot.framework.plugin.extension.integration;

import java.util.List;
import java.util.Set;

/**
 * Bounded delivery of a registered source/type set within the authenticated current tenant.
 * Business callers authorize their own operation before invoking this port. It never polls another
 * tenant or legacy unversioned envelopes, and must run outside a business transaction.
 */
public interface ReliableIntegrationDispatcher {
    DispatchResult dispatchCurrentTenant(String source, Set<String> eventTypes, int limit);

    /** Transport outcomes only: no business payload, internal row ID, or caller-controlled lease. */
    record DispatchResult(int claimed, int delivered, int failed, int leaseLost,
                          List<String> deadLetterEventIds) {
        public DispatchResult {
            deadLetterEventIds = List.copyOf(deadLetterEventIds);
        }
    }
}

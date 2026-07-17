package com.auraboot.framework.eventpolicy.executor;

import java.util.List;

/**
 * Runtime dependency advertised by an action handler for design-time availability checks.
 */
public record ActionProviderDependency(
        String providerType,
        List<String> providerCodes,
        String label,
        boolean required,
        boolean available,
        String availabilityStatus,
        String availabilityReason) {

    public static ActionProviderDependency of(
            String providerType,
            List<String> providerCodes,
            String label,
            boolean required,
            boolean available,
            String availabilityReason) {
        return new ActionProviderDependency(
                providerType,
                providerCodes == null ? List.of() : List.copyOf(providerCodes),
                label,
                required,
                available,
                available ? "AVAILABLE" : "UNAVAILABLE",
                available ? null : availabilityReason);
    }
}

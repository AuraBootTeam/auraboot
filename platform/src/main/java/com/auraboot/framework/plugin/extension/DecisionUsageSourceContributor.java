package com.auraboot.framework.plugin.extension;

/**
 * Read-back port for usage-index rebuilds: the platform owns the projection table
 * and host-side source scans, but product-owned sources ({@code SLA_RULE},
 * {@code WORKFLOW_PROCESS}, ...) live behind plugin boundaries the host cannot
 * traverse. Products implement this port so {@code usage-index/rebuild} can pull
 * a fresh projection instead of dropping every product-owned reference.
 */
public interface DecisionUsageSourceContributor {

    /**
     * Re-publish every usage source this product owns for the current tenant
     * through {@link DecisionUsageAccessor#replaceSource(Source, List)}.
     *
     * @return the number of sources republished
     */
    int republishSources();
}

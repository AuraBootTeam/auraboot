package com.auraboot.framework.meta.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Execution policy for a Named Query.
 * Stored as JSONB in ab_named_query.policy column.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NamedQueryPolicy {

    /** Max rows returned per query (default 5000) */
    private Integer maxRows = 5000;

    /** Query timeout in milliseconds (default 30000) */
    private Integer timeoutMs = 30000;

    /** Max executions per minute per tenant (default 60, 0 = unlimited) */
    private Integer rateLimitPerMinute = 60;

    /** Result cache TTL in seconds (default 0 = no cache) */
    private Integer cacheTtlSeconds = 0;

    /** Max rows for export (default 50000) */
    private Integer exportMaxRows = 50000;

    /** Max rows when in DRAFT/sandbox mode (default 100) */
    private Integer sandboxMaxRows = 100;

    /**
     * Get effective max rows based on query status.
     */
    public int getEffectiveMaxRows(NamedQueryStatus status) {
        if (status != null && status.isSandbox()) {
            return sandboxMaxRows != null ? sandboxMaxRows : 100;
        }
        return maxRows != null ? maxRows : 5000;
    }

    /**
     * Get effective timeout.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public int getEffectiveTimeoutMs() {
        return timeoutMs != null && timeoutMs > 0 ? timeoutMs : 30000;
    }

    /**
     * Whether rate limiting is enabled.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isRateLimitEnabled() {
        return rateLimitPerMinute != null && rateLimitPerMinute > 0;
    }
}

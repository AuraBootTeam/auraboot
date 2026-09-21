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

    /**
     * rowScope value: legacy default. When resource_code/action_code are absent
     * the query runs tenant-wide, explicitly accepted by the author.
     */
    public static final String ROW_SCOPE_TENANT = "tenant";

    /**
     * rowScope value: the query MUST yield a non-empty evaluated row filter.
     * Execution fails closed when resource_code/action_code are missing or the
     * scope evaluates empty (AMOS cockpit gap G06, fallback hole C — a
     * controlled query must never silently run unscoped).
     */
    public static final String ROW_SCOPE_REQUIRE_ROW_FILTER = "require_row_filter";

    /**
     * Row-scope enforcement intent: {@code tenant} (default/legacy) or
     * {@code require_row_filter} (fail-closed; also validated at save time to
     * require resource_code + action_code).
     */
    private String rowScope;

    /** Optional aggregate-root authorization required before reading a section query. */
    private RootAccess rootAccess;

    /** Max rows returned per query (default 5000) */
    private Integer maxRows = 5000;

    /** Query timeout in milliseconds (default 30000) */
    private Integer timeoutMs = 30000;

    /**
     * Declares that every fromSql source is consumed only through rows the CURRENT user
     * created. When set, a caller without model-level read on a joined source is not
     * denied: the source instead gets a forced {@code created_by = <current user>} scope,
     * which is narrower than any model-level grant (same principle as the record-scoped
     * collaborator admission). Sources without a created_by column fail at SQL time, so
     * the flag fails closed. Intended for self-contribution analytics (home trend and
     * workload charts) that must stay executable for members without any product role.
     */
    private Boolean selfAnchoredSources = false;

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

    @Data
    public static class RootAccess {
        /** Business aggregate model, e.g. {@code qo_quote_common}. */
        private String modelCode;

        /** Caller parameter containing the stable public record PID of the aggregate root. */
        private String pidParam;

        /** Root action to evaluate; defaults to {@code read}. */
        private String actionCode = "read";

        /** Allow an active update-level record share to satisfy this query's surface permission. */
        private Boolean allowCollaborator = false;
    }

    /**
     * Whether rate limiting is enabled.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isRateLimitEnabled() {
        return rateLimitPerMinute != null && rateLimitPerMinute > 0;
    }
}

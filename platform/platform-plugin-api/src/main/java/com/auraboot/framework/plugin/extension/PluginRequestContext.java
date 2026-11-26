package com.auraboot.framework.plugin.extension;

import java.time.ZoneId;
import java.util.Map;

/**
 * Per-request context the host injects into a {@link RestEndpointExtension} handler.
 * Replaces direct plugin dependency on host internals (TenantClock / MetaContext /
 * UniqueIdGenerator) — the platform resolves these from the authenticated request.
 */
public interface PluginRequestContext {

    /** Current tenant id (from JWT / bound public context). */
    Long tenantId();

    /** Current user id (0 for the public operator). */
    Long userId();

    /** Tenant's business timezone (replaces TenantClock for plugins). */
    ZoneId zoneId();

    /** Generate a platform-unique id / pid (replaces UniqueIdGenerator). */
    String generateId();

    /** True when this request was served via a PUBLIC route with a bound public context. */
    boolean isPublic();

    /**
     * Governed, tenant-scoped data access — the same surface command handlers get via
     * {@code CommandContext.dataAccessor()}. Lets a plugin REST endpoint do model-driven CRUD
     * without touching host internals. Backed by the platform DynamicDataService.
     */
    DataAccessor dataAccessor();

    /** Resolve display identity only within this authenticated request's active tenant membership. */
    default IdentityDirectoryAccessor.UserIdentity findActiveTenantUser(String userPid) { return null; }
    /** Scoped to the registered namespace and tenant of the authenticated write. */
    default RestPublicationPublisher publicationPublisher() {
        throw new UnsupportedOperationException("REST publishing is unavailable");
    }

    /** Document resolved for this public GET, independent of business data access. */
    default Map<String, Object> publishedDocument() { return Map.of(); }
}

package com.auraboot.framework.plugin.extension;

import java.time.ZoneId;

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
}

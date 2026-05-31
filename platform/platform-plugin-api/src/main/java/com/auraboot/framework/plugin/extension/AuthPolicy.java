package com.auraboot.framework.plugin.extension;

/** Auth policy for a plugin REST route declared via {@link RestRoute}. */
public enum AuthPolicy {
    /** Default: requires an authenticated user (JWT) + permission. */
    AUTHENTICATED,
    /** Public: no authentication. Must sit under /api/plugins/{ns}/public/**. (gamma-3) */
    PUBLIC
}

package com.auraboot.framework.agent.authorization;

/**
 * Lifetime of an authorized effect grant. See contract for scope key semantics.
 *
 * <p>{@code PER_SESSION} requires the 4-tuple scope key
 * {@code (tenant_id, user_id, profile_id, channel_session_id)}; when
 * channelSessionId is null, callers should treat it as {@link #PER_TURN}.
 */
public enum EffectLifetime {
    PER_INVOCATION,
    PER_BUNDLE,
    PER_TURN,
    PER_SESSION
}

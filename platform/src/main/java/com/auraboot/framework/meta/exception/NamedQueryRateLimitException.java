package com.auraboot.framework.meta.exception;

/** Transport-neutral signal that a named query's execution rate budget was exhausted. */
public final class NamedQueryRateLimitException extends MetaServiceException {
    public NamedQueryRateLimitException(String queryCode, int maxPerMinute) {
        super("Rate limit exceeded for query: " + queryCode + " (max " + maxPerMinute + " per minute)");
    }
}

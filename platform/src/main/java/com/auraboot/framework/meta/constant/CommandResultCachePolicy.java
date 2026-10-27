package com.auraboot.framework.meta.constant;

import java.util.Map;

/** Trusted command result-reuse policy; this does not grant access or make a transaction read-only. */
public final class CommandResultCachePolicy {
    private CommandResultCachePolicy() {}

    public static boolean enabled(Map<String, Object> configuration) {
        if (configuration == null || !configuration.containsKey("cacheResult")) return true;
        Object value = configuration.get("cacheResult");
        if (!(value instanceof Boolean enabled))
            throw new IllegalArgumentException("cacheResult must be a boolean");
        if (enabled) return true;
        if (!"query".equals(configuration.get("type")))
            throw new IllegalArgumentException("cacheResult=false requires command type query");
        if (Boolean.TRUE.equals(configuration.get("requireIdempotencyKey")))
            throw new IllegalArgumentException("cacheResult=false conflicts with requireIdempotencyKey=true");
        Object parameters = configuration.get("handlerParams");
        if (parameters instanceof Map<?, ?> params && params.containsKey("targetRecordAction")
                && !"read".equals(params.get("targetRecordAction")))
            throw new IllegalArgumentException("An uncached query cannot declare a target write action");
        return false;
    }
}

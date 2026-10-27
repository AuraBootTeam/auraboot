package com.auraboot.framework.agent.memory;

import java.util.Objects;

/** Shared SQL policy for prompt/recall readers. User ownership is tenant-local. */
public final class MemoryReadPolicy {
    private MemoryReadPolicy() {}

    public static final String LIVE = "(deleted_flag IS NULL OR deleted_flag = FALSE) "
            + "AND (valid_until IS NULL OR valid_until > NOW())";
    public static final String VISIBLE = LIVE + " AND (scope = 'global' "
            + "OR (tenant_id = ? AND (scope = 'tenant' OR (scope = 'user' AND scope_key = ?))))";
    public static final String SHARED = LIVE + " AND scope IN ('tenant', 'global')";

    public static String userKey(Long tenantId, String userId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return userId == null || userId.isBlank() ? null : userId;
    }
}

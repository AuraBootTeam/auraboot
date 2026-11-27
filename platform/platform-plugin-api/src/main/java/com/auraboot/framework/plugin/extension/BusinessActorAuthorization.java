package com.auraboot.framework.plugin.extension;

import java.util.Map;
import java.util.Set;

/** Additional identity/relationship gate; never a replacement for complete record and field grants. */
public interface BusinessActorAuthorization {
    /**
     * Finite internal identity fields: at most 16 models and 125 fields per model.
     * The host adds PID, tenant and physical version. Reads consume an invocation-wide
     * 100-record budget; queries are restricted to the first page, declared filters/sorts
     * and no keyword. The capability expires after authorize returns and rejects other threads.
     */
    Map<String, Set<String>> sources();

    /** Runs without caller payload. False denies; unavailable identity facts must never grant access. */
    boolean authorize(Context context);

    record Context(CommandHandlerExtension.TargetAuthorizationContext target, Set<String> grantedPermissions) {
        public Context { grantedPermissions = Set.copyOf(grantedPermissions); }
    }
}

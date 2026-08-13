package com.auraboot.framework.plugin.extension;

import java.util.Collection;

/**
 * Host-owned bridge for synchronising record-level read grants from user PIDs.
 *
 * <p>The host resolves user PIDs into tenant-member identities and replaces only
 * direct member grants for the named record. Plugins never receive internal
 * member IDs and cannot create cross-tenant shares.</p>
 */
public interface RecordShareAccessor {

    String SETTINGS_KEY = "__recordShareAccessor";

    void replaceReadSharesForUsers(
            long tenantId,
            String resourceCode,
            String recordPid,
            Collection<String> userPids);

    /** Replace direct member collaboration grants while retaining legacy-host compatibility. */
    default void replaceReadUpdateSharesForUsers(
            long tenantId,
            String resourceCode,
            String recordPid,
            Collection<String> userPids) {
        replaceReadSharesForUsers(tenantId, resourceCode, recordPid, userPids);
    }
}

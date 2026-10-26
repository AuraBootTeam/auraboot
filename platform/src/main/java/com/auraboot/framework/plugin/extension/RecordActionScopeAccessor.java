package com.auraboot.framework.plugin.extension;

/**
 * Host-owned current-actor record scope check for an already authorized business command.
 * Returns no row data and grants no function, generic model operation, read or write permit.
 * Model/action must be selected by trusted handler code; a payload PID is only an assertion.
 * Available during the invocation-authorizer callback and the original writable command
 * transaction. Never cache a capability or use a previous boolean as a later authorization.
 */
@FunctionalInterface
public interface RecordActionScopeAccessor {
    String SETTINGS_KEY = "__recordActionScopeAccessor";

    /** Exact current-tenant public PID, readability and read/update/delete action scope. */
    boolean permits(String modelCode, String recordPid, String action);
}

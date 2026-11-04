package com.auraboot.framework.plugin.extension;

import java.util.List;

/** Transaction-bound locks for already readable records, never a mutation or execution grant. */
public interface CommandRecordReadLockAccessor {
    /**
     * Declare one complete set of at most 32 distinct physical PID roots before qualification.
     * The host checks ordinary human read authorization before and after acquiring ordered locks.
     * No values, row versions or audit fields are changed. Locks last until the caller's writable
     * Core transaction ends. A subsequent call may revalidate a subset, but cannot grow the set.
     * Calls cannot re-enter during acquisition or subset revalidation.
     * Failure invalidates this transaction's lock scope, even when a handler catches the error.
     * The caller must still read and validate its business predicates under these locks.
     * Only default-PID, row-versioned dynamic physical models are supported; unsupported hosts
     * must reject rather than substitute writes or a separate connection.
     */
    void requireReadableRecords(List<RecordRef> records);

    record RecordRef(String modelCode, String recordPid) {
        public RecordRef {
            if (modelCode == null || !modelCode.matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                    || recordPid == null || !recordPid.matches("[0-7][0-9A-HJKMNP-TV-Z]{25}"))
                throw new IllegalArgumentException("An exact model code and public record PID are required");
        }
    }
}

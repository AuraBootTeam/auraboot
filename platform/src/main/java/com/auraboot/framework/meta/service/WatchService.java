package com.auraboot.framework.meta.service;

import java.util.List;

/**
 * Watch / Follow / Subscribe mechanism.
 * Allows users to subscribe to record-level changes and receive notifications.
 *
 * @since 6.1.0
 */
public interface WatchService {

    /**
     * Toggle watch state for the current user on a record.
     * If already watching, unwatch; if not watching, start watching.
     *
     * @param modelCode the model code (e.g. "crm_opportunity")
     * @param recordId  the record ID
     * @return true if the user is now watching, false if unwatched
     */
    boolean toggleWatch(String modelCode, Long recordId);

    /**
     * Toggle watch state for the current user on a public record PID.
     * Allows pid-only records that do not expose or require the legacy numeric ID.
     *
     * @param modelCode the model code (e.g. "crm_opportunity")
     * @param recordPid the public record PID
     * @return true if the user is now watching, false if unwatched
     */
    boolean toggleWatchByRecordPid(String modelCode, String recordPid);

    /**
     * Check if the current user is watching a specific record.
     *
     * @param modelCode the model code
     * @param recordId  the record ID
     * @return true if currently watching
     */
    boolean isWatching(String modelCode, Long recordId);

    /**
     * Check if the current user is watching a specific public record PID.
     *
     * @param modelCode the model code
     * @param recordPid the public record PID
     * @return true if currently watching
     */
    boolean isWatchingByRecordPid(String modelCode, String recordPid);

    /**
     * Get all watcher user IDs for a record (used by notification routing).
     *
     * @param modelCode the model code
     * @param recordId  the record ID
     * @return list of user IDs watching this record
     */
    List<Long> getWatchers(String modelCode, Long recordId);

    /**
     * Get all watcher user IDs for a public record PID (used by notification routing).
     *
     * @param modelCode the model code
     * @param recordPid the public record PID
     * @return list of user IDs watching this record
     */
    List<Long> getWatchersByRecordPid(String modelCode, String recordPid);

    /**
     * Get all record IDs that a user is watching for a given model.
     *
     * @param modelCode the model code
     * @param userId    the user ID
     * @return list of record IDs being watched
     */
    List<Long> getWatchedRecordIds(String modelCode, Long userId);

    /**
     * Get all public record PIDs that a user is watching for a given model.
     *
     * @param modelCode the model code
     * @param userId    the user ID
     * @return list of public record PIDs being watched
     */
    List<String> getWatchedRecordPids(String modelCode, Long userId);
}

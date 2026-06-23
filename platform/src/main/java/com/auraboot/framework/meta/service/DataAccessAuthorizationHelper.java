package com.auraboot.framework.meta.service;

import java.util.Map;
import java.util.function.Function;

/**
 * Small facade for custom controllers and PF4J handlers that need the same
 * row-level DataScope semantics as Dynamic data APIs.
 */
public interface DataAccessAuthorizationHelper {

    /**
     * Authorize a list/query operation and return the row filter to append.
     */
    DataAccessAuthorizationContext authorizeList(String resourceCode, String actionCode);

    /**
     * Authorize an already-loaded record. Returns true on allow and throws on deny.
     */
    boolean authorizeRecord(String resourceCode, String actionCode, Map<String, Object> record);

    /**
     * Authorize a record by ID using the caller's trusted loader. Returns true on
     * allow and throws on missing/deny/failure.
     */
    boolean authorizeRecordId(String resourceCode, String actionCode, String recordId,
                              Function<String, Map<String, Object>> recordLoader);
}

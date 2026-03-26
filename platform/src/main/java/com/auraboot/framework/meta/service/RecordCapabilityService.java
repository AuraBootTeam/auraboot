package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.RecordCapabilities;

/**
 * ARCH-001: Record-context Capability API.
 * <p>
 * Resolves the available actions and tabs for a specific record by combining:
 * <ol>
 *   <li>All commands defined for the record's model</li>
 *   <li>Current user's permissions</li>
 *   <li>Record state vs. command preconditions (fromStates)</li>
 *   <li>Platform filter (web / mobile)</li>
 *   <li>Context filter (detail / list / inbox)</li>
 * </ol>
 *
 * @author AuraBoot Team
 * @since 3.1.0
 * @see <a href="docs/system-reference/subsystems/50-Capability动作能力接口.md">Capability API spec</a>
 */
public interface RecordCapabilityService {

    /**
     * Get available capabilities for a record.
     *
     * @param modelCode resolved model code
     * @param recordId  the record's primary key
     * @param platform  "web" or "mobile" (controls action bar visibility defaults)
     * @param context   usage context: "detail" (default), "list", "inbox"
     * @param userId    current authenticated user ID
     * @return filtered, sorted capabilities with tabs and ETag
     */
    RecordCapabilities getRecordCapabilities(String modelCode, String recordId,
                                             String platform, String context,
                                             Long userId);
}

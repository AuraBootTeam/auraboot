package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Joint sub-table save request DTO
 *
 * Supports saving master record along with multiple sub-tables in a single transaction.
 * This is used for master-detail forms where the main record and related child records
 * need to be saved atomically.
 *
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JointSubTableSaveRequest {

    /**
     * Master table data
     * Contains the main record fields (e.g., order header)
     */
    private Map<String, Object> masterData;

    /**
     * Sub-tables data map
     * Key: relation name (e.g., "items", "payments")
     * Value: list of child records
     */
    private Map<String, List<Map<String, Object>>> tables;

    /**
     * Optional relation mappings to override auto-detected relations
     * Key: sub-table key from 'tables'
     * Value: relation name defined in model
     */
    private Map<String, String> relationMappings;

    /**
     * Whether to delete existing child records before inserting new ones
     * Default: true (replace mode)
     * If false, new records will be merged with existing ones
     */
    @Builder.Default
    private Boolean replaceExisting = true;

    /**
     * Whether to validate all data before saving
     * Default: true
     */
    @Builder.Default
    private Boolean validateBeforeSave = true;
}

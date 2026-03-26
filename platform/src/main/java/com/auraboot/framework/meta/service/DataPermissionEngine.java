package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.FieldMaskRule;

import java.util.List;
import java.util.Map;

/**
 * Data Permission Engine.
 * Provides row-level filtering and column-level masking capabilities.
 *
 * <p>Supported row-level scope types:
 * <ul>
 *   <li>ALL - no filtering, user sees all records</li>
 *   <li>SELF - filter by created_by = userId</li>
 *   <li>DEPARTMENT - filter by records belonging to user's department</li>
 *   <li>DEPARTMENT_TREE - filter by records in user's department and all sub-departments</li>
 *   <li>PROJECT - filter by project binding</li>
 *   <li>CUSTOM - filter by custom SQL expression with variable substitution</li>
 * </ul>
 *
 * <p>When multiple ROW policies apply, they are combined with OR logic
 * (most permissive wins). If any policy grants ALL access, no filter is applied.
 *
 * @since 5.1.0
 */
public interface DataPermissionEngine {

    /**
     * Build SQL WHERE clause fragment for row-level filtering.
     * Returns empty string if user has ALL access or no row policies defined.
     *
     * <p>When multiple ROW policies exist for the same user/model, they are
     * combined with OR logic. For example, if a user has both SELF and
     * DEPARTMENT policies, the result would be:
     * {@code AND (created_by = 123 OR department_id IN (...))}
     *
     * @param tenantId  tenant ID
     * @param modelCode model code
     * @param userId    current user ID
     * @return SQL fragment (e.g. "AND (created_by = 123 OR ...)") or empty string
     */
    String buildRowFilter(Long tenantId, String modelCode, Long userId);

    /**
     * Filter a list of records post-query based on data permission policies.
     * Used when SQL-level filtering is not possible (e.g., cross-model joins).
     *
     * @param tenantId  tenant ID
     * @param modelCode model code
     * @param userId    current user ID
     * @param records   list of records to filter
     * @return filtered list containing only accessible records
     */
    List<Map<String, Object>> filterRecords(Long tenantId, String modelCode, Long userId,
                                            List<Map<String, Object>> records);

    /**
     * Check if a single record is accessible to the given user.
     *
     * @param tenantId  tenant ID
     * @param modelCode model code
     * @param userId    current user ID
     * @param record    the record to check
     * @return true if the user can access the record
     */
    boolean canAccessRecord(Long tenantId, String modelCode, Long userId,
                            Map<String, Object> record);

    /**
     * Get field mask rules applicable for the current user on the given model.
     *
     * @param tenantId  tenant ID
     * @param modelCode model code
     * @param userId    current user ID
     * @return list of masking rules (empty if no masking needed)
     */
    List<FieldMaskRule> getFieldMaskRules(Long tenantId, String modelCode, Long userId);

    /**
     * Apply field masking to query results.
     *
     * @param records list of records (each record is a field-value map)
     * @param rules   masking rules to apply
     * @return records with masked field values
     */
    List<Map<String, Object>> applyFieldMasking(
            List<Map<String, Object>> records, List<FieldMaskRule> rules);
}

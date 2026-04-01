package com.auraboot.framework.permission.engine.model;

import java.util.Collections;
import java.util.List;

/**
 * Data scope condition used to filter records in SQL queries.
 *
 * @param scopeType       scope type (e.g. "all", "none", "self", "dept", "dept_and_sub")
 * @param ownerField      field name for owner filtering (e.g. "created_by")
 * @param ownerValue      owner user/member ID
 * @param deptField       field name for department filtering on target model
 * @param deptPids        list of department PIDs within scope (string-based, from dynamic tables)
 * @param sharedRecordIds record IDs explicitly shared via ReBAC
 */
public record DataScopeCondition(
        String scopeType,
        String ownerField,
        Long ownerValue,
        String deptField,
        List<String> deptPids,
        List<Long> sharedRecordIds
) {

    /**
     * Full access — no filtering applied.
     */
    public static DataScopeCondition all() {
        return new DataScopeCondition("all", null, null, null, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * No access — all records filtered out.
     */
    public static DataScopeCondition none() {
        return new DataScopeCondition("none", null, null, null, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Data scope not configured for this resource — the model does not participate in data scope.
     *
     * <p>Semantically distinct from {@link #all()}: "all" means an explicit grant of full access,
     * while "not_configured" means the data scope layer is not applicable for this resource.
     * The evaluator returns NOT_APPLICABLE for this condition, preserving deny-by-default semantics
     * in the permission pipeline. SQL generation still produces no filter (equivalent to all rows),
     * because no data scope restriction was configured for the model.
     */
    public static DataScopeCondition notConfigured() {
        return new DataScopeCondition("not_configured", null, null, null, Collections.emptyList(), Collections.emptyList());
    }
}

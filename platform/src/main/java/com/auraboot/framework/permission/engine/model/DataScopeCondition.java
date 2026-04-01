package com.auraboot.framework.permission.engine.model;

import java.util.Collections;
import java.util.List;

/**
 * Data scope condition used to filter records in SQL queries.
 *
 * @param scopeType       scope type (e.g. "all", "none", "self", "dept", "dept_and_below")
 * @param ownerField      field name for owner filtering (e.g. "created_by")
 * @param ownerValue      owner user/member ID
 * @param deptField       field name for department filtering
 * @param deptIds         list of department IDs within scope
 * @param sharedRecordIds record IDs explicitly shared via ReBAC
 */
public record DataScopeCondition(
        String scopeType,
        String ownerField,
        Long ownerValue,
        String deptField,
        List<Long> deptIds,
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
}

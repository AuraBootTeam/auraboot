package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.engine.model.PermissionResult;

import java.util.Map;

/**
 * Unified permission facade — single entry point for all permission capabilities.
 *
 * <p>Wraps the following services into one cohesive API:
 * <ul>
 *   <li>{@code PermissionEvaluator} — action check, data scope, record-level evaluation</li>
 *   <li>{@code PermissionPolicyService} — parameterized policy values</li>
 *   <li>{@code FieldPermissionService} — field-level visibility/editability</li>
 * </ul>
 *
 * <p>Named {@code UnifiedPermissionService} to avoid conflict with the existing
 * {@code PermissionService} which is CRUD-focused on permission entity management.
 */
public interface UnifiedPermissionService {

    /**
     * Action-level check — does the member have the action permission via RBAC?
     *
     * @param memberId member (user) ID
     * @param resource resource identifier (e.g. model code)
     * @param action   action identifier (e.g. "view", "create", "edit", "delete")
     * @return true if RBAC grants the permission
     */
    boolean canAction(Long memberId, String resource, String action);

    /**
     * List-level data scope — get SQL filter condition for list queries.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @return data scope condition for SQL filtering
     */
    DataScopeCondition getDataScopeCondition(Long memberId, String resource, String action);

    /**
     * Record-level check — full 5-step pipeline evaluation for a single record.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @param record   the target record (nullable for non-record operations)
     * @return detailed permission result with all evaluation steps
     */
    PermissionResult canOperate(Long memberId, String resource, String action, Object record);

    /**
     * Get effective policy parameters for a member on a specific permission.
     *
     * @param memberId       member (user) ID
     * @param permissionCode permission code (e.g. "model.order.approve")
     * @return merged policy map, or null if no policy configured
     */
    Map<String, Object> getEffectivePolicy(Long memberId, String permissionCode);

    /**
     * Get field-level permissions for a member on a specific model.
     *
     * @param memberId  member (user) ID
     * @param modelCode model code
     * @return field permission set with viewable, editable, and hidden fields
     */
    FieldPermissionSet getFieldPermissions(Long memberId, String modelCode);

    /**
     * Audit/compliance — explain WHY a permission decision was made.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @param recordId target record ID (nullable for non-record operations)
     * @return full explanation with all evaluation steps
     */
    PermissionExplanation explain(Long memberId, String resource, String action, Long recordId);
}

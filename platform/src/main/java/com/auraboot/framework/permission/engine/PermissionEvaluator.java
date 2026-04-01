package com.auraboot.framework.permission.engine;

import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.engine.model.PermissionResult;

/**
 * Unified permission evaluation engine interface.
 *
 * <p>Provides three evaluation modes:
 * <ul>
 *   <li>{@link #canAction} — fast boolean check (RBAC only)</li>
 *   <li>{@link #getDataScopeCondition} — SQL-level data scope filtering</li>
 *   <li>{@link #canOperate} — full 5-step pipeline for single-record authorization</li>
 * </ul>
 *
 * <p>Pipeline order:
 * <ol>
 *   <li>RBAC (RolePermission)</li>
 *   <li>ReBAC (RecordShare) — share overrides data scope</li>
 *   <li>DataScope</li>
 *   <li>Policy (parameter limits)</li>
 *   <li>FieldPermission (field-level visibility)</li>
 * </ol>
 */
public interface PermissionEvaluator {

    /**
     * Fast boolean check — does the member have the action permission via RBAC?
     *
     * @param memberId member (user) ID
     * @param resource resource identifier (e.g. model code)
     * @param action   action identifier (e.g. "view", "create", "edit", "delete")
     * @return true if RBAC grants the permission
     */
    boolean canAction(Long memberId, String resource, String action);

    /**
     * Get the data scope condition for list queries.
     *
     * <p>If RBAC denies, returns {@link DataScopeCondition#none()}.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @return data scope condition for SQL filtering
     */
    DataScopeCondition getDataScopeCondition(Long memberId, String resource, String action);

    /**
     * Full 5-step pipeline evaluation for a single record.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @param record   the target record (nullable for non-record operations)
     * @return detailed permission result with all evaluation steps
     */
    PermissionResult canOperate(Long memberId, String resource, String action, Object record);

    /**
     * Audit/compliance — explain WHY a permission decision was made.
     *
     * <p>Runs the full evaluation pipeline and wraps the result in a
     * {@link PermissionExplanation} with all evaluation steps traced.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @param recordId target record ID (nullable for non-record operations)
     * @return full explanation with member, resource, action, result, and steps
     */
    PermissionExplanation explain(Long memberId, String resource, String action, Long recordId);
}

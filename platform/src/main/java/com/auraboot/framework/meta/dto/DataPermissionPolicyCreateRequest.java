package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request DTO for creating/updating a data permission policy.
 *
 * @since 5.1.0
 */
@Data
public class DataPermissionPolicyCreateRequest {

    @NotBlank
    private String name;

    private String description;

    @NotBlank
    private String modelCode;

    /**
     * ROW or COLUMN.
     */
    @NotBlank
    private String policyType;

    /**
     * Row scope type: ALL / SELF / DEPARTMENT / DEPARTMENT_TREE / PROJECT / CUSTOM.
     * Required when policyType = ROW.
     *
     * <p>For DEPARTMENT/DEPARTMENT_TREE, use scopeExpression to configure:
     * "targetFieldCode:deptModelCode:deptParentFieldCode"
     * e.g. "org_emp_dept_id:org_department:org_dept_parent_id"
     *
     * <p>For CUSTOM, scopeExpression is a SQL fragment with variables:
     * #userId, #user.id, #tenantId
     */
    private String scopeType;

    /**
     * Expression for scope configuration.
     * For DEPARTMENT/DEPARTMENT_TREE: "targetField:deptModelCode:parentField"
     * For PROJECT: field name containing project reference (default: "project_pid")
     * For CUSTOM: SQL WHERE fragment with #userId/#tenantId variables
     */
    private String scopeExpression;

    /**
     * Target field code. Required when policyType = COLUMN.
     */
    private String fieldCode;

    /**
     * Mask type: HIDE / PARTIAL / HASH / CUSTOM.
     * Required when policyType = COLUMN.
     */
    private String maskType;

    /**
     * Custom mask expression.
     */
    private String maskExpression;

    private Integer priority = 0;

    private boolean enabled = true;
}

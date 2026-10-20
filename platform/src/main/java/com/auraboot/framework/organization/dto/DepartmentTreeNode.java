package com.auraboot.framework.organization.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Department tree node for hierarchical org chart rendering.
 */
public record DepartmentTreeNode(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String pid,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String parentPid,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int employeeCount,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DepartmentTreeNode> children
) {}

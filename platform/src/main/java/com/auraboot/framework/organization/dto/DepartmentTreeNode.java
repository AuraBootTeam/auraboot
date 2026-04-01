package com.auraboot.framework.organization.dto;

import java.util.List;

/**
 * Department tree node for hierarchical org chart rendering.
 */
public record DepartmentTreeNode(
    String pid,
    String name,
    String parentPid,
    int employeeCount,
    List<DepartmentTreeNode> children
) {}

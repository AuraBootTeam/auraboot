package com.auraboot.framework.rbac.dto;

import java.util.List;

/**
 * Department-user tree for role member management: the org department tree
 * with each node's employees annotated by their role assignment status,
 * plus employees not attached to any department.
 */
public record RoleMemberTreeResponse(
    List<DeptUserTreeNode> departments,
    List<DeptUserNodeUser> ungrouped
) {

    public record DeptUserTreeNode(
        String pid,
        String name,
        String parentPid,
        List<DeptUserTreeNode> children,
        List<DeptUserNodeUser> users
    ) {}

    public record DeptUserNodeUser(
        String userPid,
        String memberPid,
        String name,
        boolean assigned
    ) {}
}

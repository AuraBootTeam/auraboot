package com.auraboot.framework.organization.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.service.PermissionFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Resolves the department tree available to a resource-scoped aggregate filter. */
@Service
@RequiredArgsConstructor
public class PermittedDepartmentTreeService {

    private final OrganizationService organizationService;
    private final PermissionFacade permissionFacade;

    public List<DepartmentTreeNode> getPermittedTree(String resource, String action) {
        Long memberId = MetaContext.exists() ? MetaContext.getCurrentMemberId() : null;
        if (memberId == null || !permissionFacade.canAction(memberId, resource, action)) {
            return List.of();
        }

        DataScopeCondition scope = permissionFacade.getDataScopeCondition(memberId, resource, action);
        if (scope == null || "none".equals(scope.scopeType())) {
            return List.of();
        }

        List<DepartmentTreeNode> fullTree = organizationService.getDepartmentTree(
                MetaContext.getCurrentTenantId());
        if ("all".equals(scope.scopeType()) || "not_configured".equals(scope.scopeType())) {
            return fullTree;
        }

        Set<String> permittedPids = new HashSet<>(scope.deptPids());
        if ("self".equals(scope.scopeType()) && permittedPids.isEmpty()) {
            String currentDepartmentPid = organizationService.getCurrentDepartmentPid();
            if (currentDepartmentPid != null && !currentDepartmentPid.isBlank()) {
                permittedPids.add(currentDepartmentPid);
            }
        }
        if (permittedPids.isEmpty()) {
            return List.of();
        }

        return filterForest(fullTree, permittedPids, null);
    }

    private List<DepartmentTreeNode> filterForest(
            List<DepartmentTreeNode> nodes,
            Set<String> permittedPids,
            String visibleParentPid) {
        List<DepartmentTreeNode> result = new ArrayList<>();
        for (DepartmentTreeNode node : nodes) {
            if (permittedPids.contains(node.pid())) {
                List<DepartmentTreeNode> children = filterForest(
                        node.children(), permittedPids, node.pid());
                result.add(new DepartmentTreeNode(
                        node.pid(), node.name(), visibleParentPid,
                        node.employeeCount(), children));
            } else {
                // Promote allowed descendants so the returned forest has no dangling parents.
                result.addAll(filterForest(node.children(), permittedPids, visibleParentPid));
            }
        }
        return List.copyOf(result);
    }
}

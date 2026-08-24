package com.auraboot.framework.organization.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.auraboot.framework.permission.engine.model.DataScopeCondition;
import com.auraboot.framework.permission.service.PermissionFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermittedDepartmentTreeServiceTest {

    @Mock private OrganizationService organizationService;
    @Mock private PermissionFacade permissionFacade;

    private PermittedDepartmentTreeService service;

    @BeforeEach
    void setUp() {
        service = new PermittedDepartmentTreeService(organizationService, permissionFacade);
        MetaContext.setContext(9L, 7L, "user-pid", "tester");
        MetaContext.setMemberId(5L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void deniesBeforeReadingOrganizationTreeWhenRbacDenies() {
        when(permissionFacade.canAction(5L, "crm_opportunity_common", "read")).thenReturn(false);

        assertTrue(service.getPermittedTree("crm_opportunity_common", "read").isEmpty());
        verifyNoInteractions(organizationService);
    }

    @Test
    void returnsFullTenantTreeForExplicitAllScope() {
        List<DepartmentTreeNode> tree = tree();
        when(permissionFacade.canAction(5L, "crm_opportunity_common", "read")).thenReturn(true);
        when(permissionFacade.getDataScopeCondition(5L, "crm_opportunity_common", "read"))
                .thenReturn(DataScopeCondition.all());
        when(organizationService.getDepartmentTree(9L)).thenReturn(tree);

        assertEquals(tree, service.getPermittedTree("crm_opportunity_common", "read"));
    }

    @Test
    void promotesOnlyPermittedDepartmentSubtreeToAValidRoot() {
        when(permissionFacade.canAction(5L, "crm_opportunity_common", "read")).thenReturn(true);
        when(permissionFacade.getDataScopeCondition(5L, "crm_opportunity_common", "read"))
                .thenReturn(new DataScopeCondition(
                        "dept_and_sub", "crm_opp_owner", "user-pid", null,
                        List.of("sales-east", "sales-east-one"), List.of()));
        when(organizationService.getDepartmentTree(9L)).thenReturn(tree());

        List<DepartmentTreeNode> result = service.getPermittedTree("crm_opportunity_common", "read");

        assertEquals(1, result.size());
        assertEquals("sales-east", result.getFirst().pid());
        assertNull(result.getFirst().parentPid());
        assertEquals(List.of("sales-east-one"),
                result.getFirst().children().stream().map(DepartmentTreeNode::pid).toList());
    }

    @Test
    void selfScopeResolvesOnlyTheCurrentUsersDepartment() {
        when(permissionFacade.canAction(5L, "crm_opportunity_common", "read")).thenReturn(true);
        when(permissionFacade.getDataScopeCondition(5L, "crm_opportunity_common", "read"))
                .thenReturn(new DataScopeCondition(
                        "self", "crm_opp_owner", "user-pid", null, List.of(), List.of()));
        when(organizationService.getCurrentDepartmentPid()).thenReturn("sales-east-one");
        when(organizationService.getDepartmentTree(9L)).thenReturn(tree());

        List<DepartmentTreeNode> result = service.getPermittedTree("crm_opportunity_common", "read");

        assertEquals(List.of("sales-east-one"),
                result.stream().map(DepartmentTreeNode::pid).toList());
        assertNull(result.getFirst().parentPid());
    }

    private static List<DepartmentTreeNode> tree() {
        DepartmentTreeNode eastOne = new DepartmentTreeNode(
                "sales-east-one", "华东一部", "sales-east", 2, List.of());
        DepartmentTreeNode east = new DepartmentTreeNode(
                "sales-east", "华东销售", "sales", 4, List.of(eastOne));
        DepartmentTreeNode west = new DepartmentTreeNode(
                "sales-west", "华西销售", "sales", 3, List.of());
        return List.of(new DepartmentTreeNode(
                "sales", "销售中心", null, 9, List.of(east, west)));
    }
}

package com.auraboot.framework.permission.capability;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CapabilityViewServiceImplTest {

    private final PermissionService permissionService = mock(PermissionService.class);
    private final CapabilityRegistryService registry = mock(CapabilityRegistryService.class);
    private final RolePermissionService rolePermissionService = mock(RolePermissionService.class);
    private final CapabilityResolver resolver = new CapabilityResolver();
    private final CapabilityViewServiceImpl service =
            new CapabilityViewServiceImpl(permissionService, registry, resolver, rolePermissionService);

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    private PermissionDTO perm(Long id, String pid, String code) {
        PermissionDTO d = new PermissionDTO();
        d.setId(id);
        d.setPid(pid);
        d.setCode(code);
        return d;
    }

    private CapabilityDefinitionDTO decl(String code, String group, String... includes) {
        return CapabilityDefinitionDTO.builder().code(code).group(group).includes(List.of(includes)).build();
    }

    @Test
    void resolvesRoleCapabilitiesFromLivePermissionAndGrantData() {
        when(permissionService.findAllActive())
                .thenReturn(List.of(perm(1L, "p1", "crm.account.read"), perm(2L, "p2", "crm.account.manage")));
        when(permissionService.findRolePermissions(5L))
                .thenReturn(List.of(perm(1L, "p1", "crm.account.read"))); // only read granted
        when(registry.listDeclarations(any())).thenReturn(List.of(
                CapabilityDefinitionDTO.builder().code("crm.cap.account").group("客户管理").nameZhCN("维护客户资料")
                        .includes(List.of("crm.account.read", "crm.account.manage")).build()));

        MetaContext.setContext(1L, 1L, "p", "u");
        List<CapabilityGroup> groups = service.resolveForRole(5L);

        CapabilityGroup group = groups.stream()
                .filter(g -> "客户管理".equals(g.getGroup())).findFirst().orElseThrow();
        Capability cap = group.getCapabilities().get(0);
        assertThat(cap.getCode()).isEqualTo("crm.cap.account");
        assertThat(cap.getLabel()).isEqualTo("维护客户资料");
        assertThat(cap.isGranted()).isFalse(); // crm.account.manage not granted
    }

    @Test
    void applyingSelectionGrantsMissingAndRevokesDeselectedWithinUniverse() {
        when(registry.listDeclarations(any())).thenReturn(List.of(
                decl("crm.cap.account", "客户管理", "crm.account.read", "crm.account.manage"),
                decl("crm.cap.lead", "线索", "crm.lead.read")));
        when(permissionService.findAllActive()).thenReturn(List.of(
                perm(1L, "p1", "crm.account.read"),
                perm(2L, "p2", "crm.account.manage"),
                perm(3L, "p3", "crm.lead.read")));
        // role currently has account.read + lead.read
        when(permissionService.findRolePermissions(5L)).thenReturn(List.of(
                perm(1L, "p1", "crm.account.read"),
                perm(3L, "p3", "crm.lead.read")));

        MetaContext.setContext(1L, 1L, "p", "u");
        service.applyCapabilitySelection(5L, Set.of("crm.cap.account")); // select account, drop lead

        // desired = {account.read, account.manage}; current = {account.read, lead.read}
        // grant = {account.manage} -> id 2 ; revoke = universe ∩ current - desired = {lead.read} -> pid p3
        verify(rolePermissionService).assignPermissionsToRole(eq(5L),
                argThat(ids -> ids.size() == 1 && ids.contains(2L)));
        verify(rolePermissionService).removePermissionsFromRoleByPids(eq(5L),
                argThat(pids -> pids.size() == 1 && pids.contains("p3")));
    }

    @Test
    void leavesCodesThatDoNotFormACapabilityUntouched() {
        // a code with fewer than module.resource.action segments cannot be convention-derived into a
        // capability, so it stays outside the capability universe and is never touched by a save.
        when(registry.listDeclarations(any())).thenReturn(List.of(
                decl("crm.cap.account", "客户管理", "crm.account.read", "crm.account.manage")));
        when(permissionService.findAllActive()).thenReturn(List.of(
                perm(1L, "p1", "crm.account.read"), perm(2L, "p2", "crm.account.manage"),
                perm(9L, "p9", "legacy_flag"))); // single segment -> not a capability
        when(permissionService.findRolePermissions(5L)).thenReturn(List.of(
                perm(1L, "p1", "crm.account.read"), perm(2L, "p2", "crm.account.manage"),
                perm(9L, "p9", "legacy_flag")));

        MetaContext.setContext(1L, 1L, "p", "u");
        service.applyCapabilitySelection(5L, Set.of("crm.cap.account")); // complete selection

        // account codes desired & held, legacy_flag outside the universe -> no mutation at all
        verifyNoInteractions(rolePermissionService);
    }

    @Test
    void conventionDerivedCapabilityIsSavable() {
        // no declaration covers billing.license.* -> it surfaces as convention-derived "billing.license";
        // selecting it must grant its codes (capability is the sole grant surface).
        when(registry.listDeclarations(any())).thenReturn(List.of());
        when(permissionService.findAllActive()).thenReturn(List.of(
                perm(1L, "p1", "billing.license.read"), perm(2L, "p2", "billing.license.manage")));
        when(permissionService.findRolePermissions(5L)).thenReturn(List.of()); // fresh role

        MetaContext.setContext(1L, 1L, "p", "u");
        service.applyCapabilitySelection(5L, Set.of("billing.license"));

        verify(rolePermissionService).assignPermissionsToRole(eq(5L),
                argThat(ids -> ids.size() == 2 && ids.contains(1L) && ids.contains(2L)));
    }

    @Test
    void partiallyGrantedResourceIsNotStrippedBySave() {
        // role holds only billing.license.read (e.g. granted earlier via the advanced matrix). The
        // convention-derived "billing.license" capability is NOT fully held, so it stays out of the
        // revoke universe and the partial read grant survives a capability save.
        when(registry.listDeclarations(any())).thenReturn(List.of());
        when(permissionService.findAllActive()).thenReturn(List.of(
                perm(1L, "p1", "billing.license.read"), perm(2L, "p2", "billing.license.manage")));
        when(permissionService.findRolePermissions(5L)).thenReturn(List.of(
                perm(1L, "p1", "billing.license.read"))); // partial

        MetaContext.setContext(1L, 1L, "p", "u");
        service.applyCapabilitySelection(5L, Set.of()); // deselect everything

        verifyNoInteractions(rolePermissionService);
    }
}

package com.auraboot.framework.permission.capability;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityViewServiceImplTest {

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    private PermissionDTO perm(String code) {
        PermissionDTO d = new PermissionDTO();
        d.setCode(code);
        return d;
    }

    @Test
    void resolvesRoleCapabilitiesFromLivePermissionAndGrantData() {
        PermissionService permissionService = mock(PermissionService.class);
        CapabilityRegistryService registry = mock(CapabilityRegistryService.class);
        CapabilityResolver resolver = new CapabilityResolver();

        when(permissionService.findAllActive())
                .thenReturn(List.of(perm("crm.account.read"), perm("crm.account.manage")));
        when(permissionService.findRolePermissions(5L))
                .thenReturn(List.of(perm("crm.account.read"))); // only read granted, manage not
        when(registry.listDeclarations(any())).thenReturn(List.of(
                CapabilityDefinitionDTO.builder()
                        .code("crm.cap.account").group("客户管理").nameZhCN("维护客户资料")
                        .includes(List.of("crm.account.read", "crm.account.manage")).build()));

        MetaContext.setContext(1L, 1L, "p", "u");
        CapabilityViewServiceImpl service =
                new CapabilityViewServiceImpl(permissionService, registry, resolver);

        List<CapabilityGroup> groups = service.resolveForRole(5L);

        CapabilityGroup group = groups.stream()
                .filter(g -> "客户管理".equals(g.getGroup())).findFirst().orElseThrow();
        Capability cap = group.getCapabilities().get(0);
        assertThat(cap.getCode()).isEqualTo("crm.cap.account");
        assertThat(cap.getLabel()).isEqualTo("维护客户资料");
        assertThat(cap.isGranted()).isFalse(); // crm.account.manage not granted -> capability not fully granted
    }
}

package com.auraboot.framework.permission.capability;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CapabilityViewServiceImpl implements CapabilityViewService {

    private final PermissionService permissionService;
    private final CapabilityRegistryService capabilityRegistryService;
    private final CapabilityResolver capabilityResolver;

    @Override
    public List<CapabilityGroup> resolveForRole(Long roleId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<String> allCodes = permissionService.findAllActive().stream()
                .map(PermissionDTO::getCode).filter(Objects::nonNull).toList();
        Set<String> granted = permissionService.findRolePermissions(roleId).stream()
                .map(PermissionDTO::getCode).filter(Objects::nonNull).collect(Collectors.toSet());
        List<CapabilityDefinitionDTO> declarations = capabilityRegistryService.listDeclarations(tenantId);
        return capabilityResolver.resolve(declarations, allCodes, granted);
    }
}

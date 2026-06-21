package com.auraboot.framework.permission.capability;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CapabilityViewServiceImpl implements CapabilityViewService {

    private final PermissionService permissionService;
    private final CapabilityRegistryService capabilityRegistryService;
    private final CapabilityResolver capabilityResolver;
    private final RolePermissionService rolePermissionService;

    @Override
    public List<CapabilityGroup> resolveForRole(Long roleId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<String> allCodes = permissionService.findAllActive().stream()
                .map(PermissionDTO::getCode).filter(Objects::nonNull).toList();
        Set<String> granted = roleCodes(roleId);
        List<CapabilityDefinitionDTO> declarations = capabilityRegistryService.listDeclarations(tenantId);
        return capabilityResolver.resolve(declarations, allCodes, granted);
    }

    @Override
    public void applyCapabilitySelection(Long roleId, Set<String> selectedCapabilityCodes) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<CapabilityDefinitionDTO> declarations = capabilityRegistryService.listDeclarations(tenantId);

        // Resolve against the full capability set the (now sole) capability view presents — declared
        // AND convention-derived — so every capability the checklist renders is actually grantable.
        // The caller sends the role's COMPLETE desired selection, so this is a full-state replace
        // within the capability universe (codes outside the universe are never touched).
        List<PermissionDTO> all = permissionService.findAllActive();
        List<String> allCodes = all.stream().map(PermissionDTO::getCode).filter(Objects::nonNull).toList();
        Map<String, Set<String>> capabilityCodes = capabilityResolver.capabilityCodeMap(declarations, allCodes);

        Set<String> current = roleCodes(roleId);
        Set<String> desired = selectedCapabilityCodes == null ? Set.of() : selectedCapabilityCodes.stream()
                .flatMap(c -> capabilityCodes.getOrDefault(c, Set.of()).stream())
                .collect(Collectors.toSet());

        // Revoke ONLY within capabilities the role currently holds in FULL (i.e. that render as
        // granted). A resource the role holds only partially (e.g. just *.read, granted via the raw
        // matrix) is not a "granted capability", so it stays outside the revoke universe and is never
        // stripped by a capability save. This is what makes the all-or-nothing convention-derived
        // capabilities safe to save.
        Set<String> revokableUniverse = capabilityCodes.values().stream()
                .filter(current::containsAll)
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        Set<String> toGrant = desired.stream()
                .filter(c -> !current.contains(c))
                .collect(Collectors.toSet());
        Set<String> toRevoke = revokableUniverse.stream()
                .filter(c -> !desired.contains(c))
                .collect(Collectors.toSet());

        Map<String, Long> codeToId = all.stream()
                .filter(p -> p.getCode() != null && p.getId() != null)
                .collect(Collectors.toMap(PermissionDTO::getCode, PermissionDTO::getId, (a, b) -> a));
        Map<String, String> codeToPid = all.stream()
                .filter(p -> p.getCode() != null && p.getPid() != null)
                .collect(Collectors.toMap(PermissionDTO::getCode, PermissionDTO::getPid, (a, b) -> a));

        List<Long> grantIds = toGrant.stream().map(codeToId::get).filter(Objects::nonNull).toList();
        List<String> revokePids = toRevoke.stream().map(codeToPid::get).filter(Objects::nonNull).toList();

        if (!grantIds.isEmpty()) {
            rolePermissionService.assignPermissionsToRole(roleId, grantIds);
        }
        if (!revokePids.isEmpty()) {
            rolePermissionService.removePermissionsFromRoleByPids(roleId, revokePids);
        }
    }

    private Set<String> roleCodes(Long roleId) {
        return permissionService.findRolePermissions(roleId).stream()
                .map(PermissionDTO::getCode).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}

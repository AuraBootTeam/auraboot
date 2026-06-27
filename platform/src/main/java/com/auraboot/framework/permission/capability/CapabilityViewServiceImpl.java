package com.auraboot.framework.permission.capability;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final MenuMapper menuMapper;

    @Override
    public List<CapabilityGroup> resolveForRole(Long roleId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<PermissionDTO> allActive = permissionService.findAllActive();
        List<String> allCodes = allActive.stream()
                .map(PermissionDTO::getCode).filter(Objects::nonNull).toList();
        // code -> localized display name, so convention-derived capabilities render business labels
        // (e.g. 许可证) instead of the raw resource segment (license).
        Map<String, String> names = allActive.stream()
                .filter(p -> p.getCode() != null && p.getName() != null && !p.getName().isBlank())
                .collect(Collectors.toMap(PermissionDTO::getCode, PermissionDTO::getName, (a, b) -> a));
        Map<String, Map<String, Object>> extensions = allActive.stream()
                .filter(p -> p.getCode() != null && p.getExtension() != null && !p.getExtension().isEmpty())
                .collect(Collectors.toMap(PermissionDTO::getCode, PermissionDTO::getExtension, (a, b) -> a));
        Set<String> granted = roleCodes(roleId);
        List<CapabilityDefinitionDTO> declarations = capabilityRegistryService.listDeclarations(tenantId);
        List<CapabilityGroup> groups = capabilityResolver.resolve(declarations, allCodes, granted, names, extensions);
        annotateUnlockedMenus(groups);
        return groups;
    }

    /**
     * Derive (not grant) the menus each capability unlocks: a menu is unlocked by a capability when
     * the menu's permissionCode is one of the capability's included codes. Pure read-side decoration
     * so the v2 page can render "解锁菜单: …"; never affects authorization.
     */
    private void annotateUnlockedMenus(List<CapabilityGroup> groups) {
        Map<String, List<String>> menusByPermission = new LinkedHashMap<>();
        for (Menu menu : menuMapper.findAllActiveMenus()) {
            String code = menu.getPermissionCode();
            String name = menu.getName();
            if (code == null || code.isBlank() || name == null || name.isBlank()) {
                continue;
            }
            menusByPermission.computeIfAbsent(code, k -> new ArrayList<>()).add(name);
        }
        if (menusByPermission.isEmpty()) {
            return;
        }
        for (CapabilityGroup group : groups) {
            for (Capability cap : group.getCapabilities()) {
                if (cap.getIncludes() == null) {
                    continue;
                }
                List<String> unlocked = new ArrayList<>();
                for (String includedCode : cap.getIncludes()) {
                    for (String menuName : menusByPermission.getOrDefault(includedCode, List.of())) {
                        if (!unlocked.contains(menuName)) {
                            unlocked.add(menuName);
                        }
                    }
                }
                if (!unlocked.isEmpty()) {
                    cap.setUnlockedMenus(unlocked);
                }
            }
        }
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

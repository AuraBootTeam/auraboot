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
import org.springframework.transaction.annotation.Transactional;
import com.auraboot.framework.exception.RootUnCheckedException;
import static com.auraboot.framework.common.constant.ResponseCode.BadParam;
import java.util.LinkedHashSet;

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
    public CapabilitySelectionPreview previewCapabilitySelection(Long roleId, Set<String> selectedCapabilityCodes) {
        return previewCapabilitySelection(roleId, selectedCapabilityCodes, Set.of());
    }

    @Override
    public CapabilitySelectionPreview previewCapabilitySelection(Long roleId, Set<String> selectedCapabilityCodes,
                                                                  Set<String> revokedPartialCapabilityCodes) {
        return calculateSelection(roleId, selectedCapabilityCodes, revokedPartialCapabilityCodes, permissionService.findAllActive());
    }

    @Override
    @Transactional
    public void applyCapabilitySelection(Long roleId, Set<String> selectedCapabilityCodes) {
        applyCapabilitySelection(roleId, selectedCapabilityCodes, Set.of());
    }

    @Override
    @Transactional
    public void applyCapabilitySelection(Long roleId, Set<String> selectedCapabilityCodes,
                                         Set<String> revokedPartialCapabilityCodes) {
        List<PermissionDTO> all = permissionService.findAllActive();
        CapabilitySelectionPreview plan = calculateSelection(roleId, selectedCapabilityCodes, revokedPartialCapabilityCodes, all);
        Map<String, Long> codeToId = all.stream()
                .filter(p -> p.getCode() != null && p.getId() != null)
                .collect(Collectors.toMap(PermissionDTO::getCode, PermissionDTO::getId, (a, b) -> a));
        Map<String, String> codeToPid = all.stream()
                .filter(p -> p.getCode() != null && p.getPid() != null)
                .collect(Collectors.toMap(PermissionDTO::getCode, PermissionDTO::getPid, (a, b) -> a));
        List<Long> grantIds = plan.grantedCodes().stream().map(codeToId::get).toList();
        List<String> revokePids = plan.revokedCodes().stream().map(codeToPid::get).toList();
        if (grantIds.stream().anyMatch(Objects::isNull) || revokePids.stream().anyMatch(Objects::isNull)) {
            throw new RootUnCheckedException(BadParam, "Capability references unavailable permission identities");
        }
        if (!grantIds.isEmpty()) rolePermissionService.assignPermissionsToRole(roleId, grantIds);
        if (!revokePids.isEmpty()) rolePermissionService.removePermissionsFromRoleByPids(roleId, revokePids);
    }

    private CapabilitySelectionPreview calculateSelection(
            Long roleId, Set<String> selectedCapabilityCodes, Set<String> revokedPartialCapabilityCodes,
            List<PermissionDTO> all) {
        List<CapabilityDefinitionDTO> declarations = capabilityRegistryService.listDeclarations(MetaContext.getCurrentTenantId());
        Map<String, Set<String>> capabilityCodes = new LinkedHashMap<>();
        declarations.stream().filter(CapabilityDefinitionDTO::isValid)
                .forEach(d -> capabilityCodes.put(d.getCode(), new LinkedHashSet<>(d.getIncludes())));
        Set<String> selected = selectedCapabilityCodes == null ? Set.of() : selectedCapabilityCodes;
        Set<String> explicitRevocations = revokedPartialCapabilityCodes == null ? Set.of() : revokedPartialCapabilityCodes;
        if (!capabilityCodes.keySet().containsAll(selected) || !capabilityCodes.keySet().containsAll(explicitRevocations)) {
            throw new RootUnCheckedException(BadParam, "Only declared capabilities can be selected");
        }
        if (explicitRevocations.stream().anyMatch(selected::contains)) {
            throw new RootUnCheckedException(BadParam, "A capability cannot be selected and explicitly revoked together");
        }
        Set<String> available = all.stream().map(PermissionDTO::getCode).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> current = roleCodes(roleId);
        Set<String> desired = selected.stream().flatMap(code -> capabilityCodes.get(code).stream()).collect(Collectors.toSet());
        if (!available.containsAll(desired)) {
            throw new RootUnCheckedException(BadParam, "Selected capability references unavailable permissions");
        }
        // Preserve partial and undeclared grants. Selected bundles retain shared actions.
        Set<String> revokable = capabilityCodes.values().stream().filter(current::containsAll)
                .flatMap(Set::stream).collect(Collectors.toSet());
        explicitRevocations.stream().flatMap(code -> capabilityCodes.get(code).stream())
                .filter(current::contains).forEach(revokable::add);
        List<String> grant = desired.stream().filter(code -> !current.contains(code)).sorted().toList();
        List<String> revoke = revokable.stream().filter(code -> !desired.contains(code)).sorted().toList();
        Set<String> resulting = new LinkedHashSet<>(current);
        resulting.removeAll(revoke);
        resulting.addAll(grant);
        List<CapabilityGroup> groups = capabilityResolver.resolve(declarations, new ArrayList<>(available), resulting);
        annotateUnlockedMenus(groups);
        List<Capability> affected = groups.stream().flatMap(group -> group.getCapabilities().stream())
                .filter(cap -> !cap.isConventionDerived())
                .filter(cap -> cap.getIncludes().stream().anyMatch(code -> grant.contains(code) || revoke.contains(code)))
                .toList();
        List<String> menus = affected.stream().filter(cap -> cap.getUnlockedMenus() != null)
                .flatMap(cap -> cap.getUnlockedMenus().stream()).distinct().sorted().toList();
        return new CapabilitySelectionPreview(grant, revoke,
                current.stream().filter(code -> !desired.contains(code) && !revoke.contains(code)).sorted().toList(),
                affected, menus);
    }

    private Set<String> roleCodes(Long roleId) {
        return permissionService.findRolePermissions(roleId).stream()
                .map(PermissionDTO::getCode).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}

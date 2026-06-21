package com.auraboot.framework.permission.capability;

import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the permission v2 capability view: declared capabilities (from plugins'
 * capabilities.json) plus convention-derived capabilities for any permission code not covered by a
 * declaration, each marked granted when the subject holds all of its included codes. Capability is
 * an authoring/display abstraction — it always resolves down to existing permission codes, so the
 * runtime engine is untouched.
 */
public class CapabilityResolver {

    public List<CapabilityGroup> resolve(List<CapabilityDefinitionDTO> declarations,
                                         List<String> allPermissionCodes,
                                         Set<String> grantedCodes) {
        List<CapabilityDefinitionDTO> decls = declarations == null ? List.of() : declarations;
        List<String> allCodes = allPermissionCodes == null ? List.of() : allPermissionCodes;
        Set<String> granted = grantedCodes == null ? Set.of() : grantedCodes;

        // group name -> capabilities (preserve first-seen order: declared groups before derived)
        LinkedHashMap<String, List<Capability>> byGroup = new LinkedHashMap<>();
        Set<String> coveredCodes = new HashSet<>();

        // 1. Declared capabilities, lowest order first.
        decls.stream()
                .filter(CapabilityDefinitionDTO::isValid)
                .sorted(Comparator.comparingInt(d -> d.getOrder() == null ? 100 : d.getOrder()))
                .forEach(d -> {
                    coveredCodes.addAll(d.getIncludes());
                    Capability cap = Capability.builder()
                            .code(d.getCode())
                            .group(d.getGroup())
                            .label(label(d))
                            .sensitive(Boolean.TRUE.equals(d.getSensitive()))
                            .includes(d.getIncludes())
                            .granted(granted.containsAll(d.getIncludes()))
                            .conventionDerived(false)
                            .build();
                    byGroup.computeIfAbsent(d.getGroup(), g -> new ArrayList<>()).add(cap);
                });

        // 2. Convention-derive any uncovered code, grouped by module -> resource.
        LinkedHashMap<String, List<String>> byModuleResource = new LinkedHashMap<>();
        for (String code : allCodes) {
            if (coveredCodes.contains(code)) {
                continue;
            }
            int firstDot = code.indexOf('.');
            int secondDot = firstDot < 0 ? -1 : code.indexOf('.', firstDot + 1);
            if (firstDot < 0 || secondDot < 0) {
                continue; // not a module.resource.action code — nothing to derive
            }
            String moduleResource = code.substring(0, secondDot); // module.resource
            byModuleResource.computeIfAbsent(moduleResource, k -> new ArrayList<>()).add(code);
        }
        byModuleResource.forEach((moduleResource, includes) -> {
            String module = moduleResource.substring(0, moduleResource.indexOf('.'));
            String resource = moduleResource.substring(moduleResource.indexOf('.') + 1);
            Capability cap = Capability.builder()
                    .code(moduleResource)
                    .group(module)
                    .label(resource)
                    .sensitive(false)
                    .includes(includes)
                    .granted(granted.containsAll(includes))
                    .conventionDerived(true)
                    .build();
            byGroup.computeIfAbsent(module, g -> new ArrayList<>()).add(cap);
        });

        List<CapabilityGroup> result = new ArrayList<>();
        byGroup.forEach((group, caps) -> result.add(new CapabilityGroup(group, caps)));
        return result;
    }

    public Set<String> expandToPermissionCodes(Set<String> selectedCapabilityCodes,
                                               List<CapabilityDefinitionDTO> declarations) {
        if (selectedCapabilityCodes == null || declarations == null) {
            return Set.of();
        }
        Map<String, CapabilityDefinitionDTO> byCode = new LinkedHashMap<>();
        declarations.stream()
                .filter(CapabilityDefinitionDTO::isValid)
                .forEach(d -> byCode.putIfAbsent(d.getCode(), d));

        Set<String> result = new LinkedHashSet<>();
        for (String capCode : selectedCapabilityCodes) {
            CapabilityDefinitionDTO d = byCode.get(capCode);
            if (d != null && d.getIncludes() != null) {
                result.addAll(d.getIncludes());
            }
        }
        return result;
    }

    private String label(CapabilityDefinitionDTO d) {
        if (d.getNameZhCN() != null && !d.getNameZhCN().isBlank()) {
            return d.getNameZhCN();
        }
        if (d.getNameEn() != null && !d.getNameEn().isBlank()) {
            return d.getNameEn();
        }
        return d.getCode();
    }
}

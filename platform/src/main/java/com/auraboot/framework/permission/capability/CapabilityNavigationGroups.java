package com.auraboot.framework.permission.capability;

import com.auraboot.framework.menu.entity.Menu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-side navigation projection. Never adds, removes, or evaluates a grant. */
final class CapabilityNavigationGroups {
    static final String CAPABILITY_CODES = "capabilityCodes";
    private CapabilityNavigationGroups() { }

    static List<CapabilityGroup> regroup(List<CapabilityGroup> groups, List<Menu> menus) {
        Map<Long, Menu> byId = new HashMap<>();
        menus.stream().filter(menu -> menu.getId() != null).forEach(menu -> byId.put(menu.getId(), menu));
        Map<String, Menu> declaredSections = new HashMap<>();
        for (Menu menu : menus) {
            Object configured = menu.getExtension() == null ? null : menu.getExtension().get(CAPABILITY_CODES);
            if (configured == null) continue;
            if (!(configured instanceof List<?> codes)) {
                throw new IllegalArgumentException("Navigation capabilityCodes must be a list");
            }
            Menu section = root(menu, byId);
            for (Object value : codes) {
                if (!(value instanceof String code) || code.isBlank()
                        || declaredSections.putIfAbsent(code, section) != null) {
                    throw new IllegalArgumentException("Navigation capability must have one declared module");
                }
            }
        }
        Map<String, Set<Menu>> sectionsByPermission = new HashMap<>();
        for (Menu menu : menus) {
            if (menu.getPermissionCode() == null || menu.getPermissionCode().isBlank()) continue;
            Menu section = root(menu, byId);
            if (section.getName() == null || section.getName().isBlank()) continue;
            sectionsByPermission.computeIfAbsent(menu.getPermissionCode(), key -> new HashSet<>()).add(section);
        }

        Map<String, List<Capability>> buckets = new LinkedHashMap<>();
        for (CapabilityGroup group : groups) {
            for (Capability capability : group.getCapabilities()) {
                // Technical diagnostics must not become selectable business duties just because
                // their underlying permission happens to appear on a menu.
                if (!capability.isConventionDerived()) {
                    Set<Menu> sections = new HashSet<>();
                    Menu declaredSection = declaredSections.get(capability.getCode());
                    if (declaredSection != null) {
                        sections.add(declaredSection);
                    } else if (capability.getIncludes() != null) {
                        capability.getIncludes().forEach(code ->
                                sections.addAll(sectionsByPermission.getOrDefault(code, Set.of())));
                    }
                    // A shared capability has one identity. Multiple navigation placements do not
                    // duplicate its checkbox or arbitrarily choose an owning module.
                    if (sections.size() == 1) {
                        Menu section = sections.iterator().next();
                        capability.setGroup(section.getName());
                        capability.setDisplayGroupOrder(section.getOrderNo() == null
                                ? Integer.MAX_VALUE : section.getOrderNo());
                    }
                }
                buckets.computeIfAbsent(capability.getGroup(), key -> new ArrayList<>()).add(capability);
            }
        }
        return buckets.entrySet().stream()
                .map(entry -> new CapabilityGroup(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(group -> group.getCapabilities().stream()
                        .map(Capability::getDisplayGroupOrder).filter(java.util.Objects::nonNull)
                        .min(Integer::compareTo).orElse(Integer.MAX_VALUE)))
                .toList();
    }

    private static Menu root(Menu menu, Map<Long, Menu> byId) {
        Set<Long> visited = new HashSet<>();
        Menu current = menu;
        while (current.getParentId() != null && current.getParentId() != 0) {
            if (current.getId() != null && !visited.add(current.getId())) {
                throw new IllegalArgumentException("Cyclic capability navigation hierarchy");
            }
            Menu parent = byId.get(current.getParentId());
            if (parent == null) {
                throw new IllegalArgumentException("Missing capability navigation parent: " + current.getParentId());
            }
            current = parent;
        }
        return current;
    }
}

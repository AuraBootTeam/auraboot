package com.auraboot.framework.menu.service;

import com.auraboot.framework.menu.entity.Menu;
import org.springframework.dao.DataRetrievalFailureException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optional Release-local navigation grouping; permissions remain the service's responsibility. */
public final class ApplicationNavigationPolicy {
    private ApplicationNavigationPolicy() {}

    private static Object declaration(Menu menu) {
        return menu.getExtension() == null ? null
                : menu.getExtension().get("applicationNavigation");
    }

    public static boolean declared(List<Menu> roots) {
        return roots.stream().anyMatch(menu -> declaration(menu) != null);
    }

    public static List<Menu> groupRoots(List<Menu> roots) {
        List<Menu> providers = roots.stream().filter(menu -> declaration(menu) != null).toList();
        if (providers.isEmpty()) return roots;
        if (providers.size() != 1) throw invalid("exactly one navigation group is allowed");
        Menu group = providers.getFirst();
        if (!Integer.valueOf(0).equals(group.getType()) || (group.getPath() != null && !group.getPath().isBlank())) {
            throw invalid("navigation group must be a directory without a route");
        }
        if (!(declaration(group) instanceof Map<?, ?> policy)
                || !policy.keySet().equals(Set.of("keepRootCodes", "deduplicatePaths"))
                || !Boolean.TRUE.equals(policy.get("deduplicatePaths"))
                || !(policy.get("keepRootCodes") instanceof List<?> raw)) {
            throw invalid("navigation declaration requires keepRootCodes and deduplicatePaths=true");
        }
        Set<String> keep = new HashSet<>();
        Set<String> rootCodes = new HashSet<>();
        roots.forEach(menu -> rootCodes.add(menu.getCode()));
        for (Object value : raw) {
            if (!(value instanceof String code) || code.isBlank() || !keep.add(code)
                    || !rootCodes.contains(code) || code.equals(group.getCode())) {
                throw invalid("kept roots must be unique existing roots outside the navigation group");
            }
        }
        List<Menu> result = new ArrayList<>();
        List<Menu> children = new ArrayList<>(group.getChildren() == null ? List.of() : group.getChildren());
        for (Menu root : roots) {
            if (root == group || keep.contains(root.getCode())) result.add(root);
            else children.add(root);
        }
        group.setChildren(children);
        return result;
    }

    /** Run after permission filtering so an inaccessible duplicate cannot hide an allowed route. */
    public static List<Menu> deduplicateVisible(List<Menu> roots) {
        return deduplicate(roots, new HashSet<>());
    }

    private static List<Menu> deduplicate(List<Menu> menus, Set<String> seen) {
        List<Menu> result = new ArrayList<>();
        for (Menu menu : menus) {
            if (Integer.valueOf(1).equals(menu.getType()) && menu.getPath() != null
                    && !menu.getPath().isBlank() && !seen.add(menu.getPath())) continue;
            if (menu.getChildren() != null) menu.setChildren(deduplicate(menu.getChildren(), seen));
            if (Integer.valueOf(0).equals(menu.getType())
                    && (menu.getChildren() == null || menu.getChildren().isEmpty())) continue;
            result.add(menu);
        }
        return result;
    }

    private static DataRetrievalFailureException invalid(String detail) {
        return new DataRetrievalFailureException("Invalid Application Release navigation policy: " + detail);
    }
}

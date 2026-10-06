package com.auraboot.framework.permission.capability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A resolved capability for the permission v2 UI: a business-language bundle of permission codes
 * with a granted flag (true when the role/subject already holds every included code).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Capability {

    private String code;
    private String group;
    private String label;
    /** Localized declaration labels; the legacy label remains available for older clients. */
    @Builder.Default
    private Map<String, String> localizedLabels = Map.of();
    /** Legacy description plus translations for display; neither affects grants. */
    private String description;
    @Builder.Default
    private Map<String, String> localizedDescriptions = Map.of();
    private boolean sensitive;
    /** Preset tier this capability belongs to (viewer/editor/admin); null for convention-derived. */
    private String tier;
    private Integer displayGroupOrder;
    private Integer displayOrder;
    private List<String> includes;
    /** True when every code in {@link #includes} is currently granted to the subject. */
    private boolean granted;
    /** none / partial / full, computed from the role's current atomic grants. */
    private String authorizationState;
    private List<String> missingCodes;
    /** True when this capability was auto-derived from code convention rather than declared. */
    private boolean conventionDerived;
    /**
     * Display names of the menus this capability unlocks — derived (NOT a grant primitive) by
     * matching each menu's permissionCode against {@link #includes}. Lets the v2 permission page
     * show "解锁菜单: …" so an admin sees the cause/effect of granting a capability.
     */
    private List<String> unlockedMenus;
}

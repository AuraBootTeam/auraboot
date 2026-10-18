package com.auraboot.framework.permission.capability;

import io.swagger.v3.oas.annotations.media.Schema;

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

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private String group;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String label;
    /** Localized declaration labels; the legacy label remains available for older clients. */
    @Builder.Default
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, String> localizedLabels = Map.of();
    /** Legacy description plus translations for display; neither affects grants. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private String description;
    @Builder.Default
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, String> localizedDescriptions = Map.of();
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean sensitive;
    /** Preset tier this capability belongs to (viewer/editor/admin); null for convention-derived. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private String tier;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer displayGroupOrder;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer displayOrder;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> includes;
    /** True when every code in {@link #includes} is currently granted to the subject. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean granted;
    /** none / partial / full, computed from the role's current atomic grants. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"none", "partial", "full"})
    private String authorizationState;
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> missingCodes;
    /** True when this capability was auto-derived from code convention rather than declared. */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean conventionDerived;
    /**
     * Display names of the menus this capability unlocks — derived (NOT a grant primitive) by
     * matching each menu's permissionCode against {@link #includes}. Lets the v2 permission page
     * show "解锁菜单: …" so an admin sees the cause/effect of granting a capability.
     */
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
    private List<String> unlockedMenus;
}

package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A capability declaration shipped by a plugin in {@code config/capabilities.json}. A capability is
 * a business-language grouping of atomic permission codes — the unit the permission v2 UI presents
 * (checkbox list folded by {@link #group}) instead of the raw resource x action matrix.
 *
 * <p>Not a runtime primitive: on save a selected capability expands to its {@link #includes}
 * permission codes, which the existing engine evaluates. See the permission v2 design doc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityDefinitionDTO {

    /** Unique capability code, e.g. {@code crm.cap.account}. */
    private String code;

    /** Business-function bucket the capability folds under, e.g. {@code 客户管理}. */
    private String group;

    @JsonProperty("name:zh-CN")
    private String nameZhCN;

    @JsonProperty("name:en")
    private String nameEn;

    private String description;

    /** Atomic permission codes granted when this capability is selected. */
    private List<String> includes;

    /** Preset tier this capability belongs to: viewer / editor / admin. Optional. */
    private String tier;

    /** Sensitive capability (UI marks with a lock, default off). */
    @Builder.Default
    private Boolean sensitive = Boolean.FALSE;

    /** model.field codes whose masking this capability lifts (capability-driven unmask). Optional. */
    private List<String> unmasksFields;

    @Builder.Default
    private Integer order = 100;

    /**
     * Group display order in the v2 permission page. Lets a declared capability control where its
     * group sorts without relying on an underlying permission's {@code displayGroupOrder} extension
     * (which scattered admin/org codes don't carry). Permission-extension group order still wins when
     * present (keeps business plugins unchanged); otherwise this value is used; otherwise 10000.
     */
    private Integer displayGroupOrder;

    public boolean isValid() {
        return code != null && !code.isBlank() && includes != null && !includes.isEmpty();
    }
}

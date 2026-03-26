package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.entity.payload.ComputedFieldOverride;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * Unified resolved field DTO representing the merged result
 * of three-layer field resolution:
 * Layer 1: Base field definition (Field entity)
 * Layer 2: Binding overrides (ModelFieldBinding)
 * Layer 3: Computed field overrides (ViewModelConfig.computedFields)
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResolvedFieldDTO {

    // ==================== Basic Info ====================

    private String code;
    private String displayName;
    private String dataType;
    private String description;

    // ==================== Binding Overrides ====================

    private Boolean required;
    private Boolean visible;
    private Boolean editable;
    private String aliasCode;
    private Integer fieldOrder;

    // ==================== Computed Field ====================

    private String computeExpression;
    private String returnType;
    private Boolean virtual;

    // ==================== UI Hints ====================

    private Map<String, Object> uiHint;

    // ==================== Source Tracking ====================

    /**
     * Source type indicating where this field originates from:
     * - field_binding: from model field binding (inherit mode)
     * - named_query_field: from named query field definition (compose/free mode)
     * - computed_only: purely virtual computed field (Layer 3 only)
     */
    private String sourceType;

    // ==================== Factory Methods ====================

    /**
     * Create from Field entity + ModelFieldBinding (inherit mode, Layer 1+2)
     */
    public static ResolvedFieldDTO from(Field field, ModelFieldBinding binding) {
        ResolvedFieldDTO dto = new ResolvedFieldDTO();
        dto.setCode(field.getCode());
        dto.setDataType(field.getDataType());
        dto.setSourceType("field_binding");

        // Extract display info from field extension
        if (field.getExtension() != null && field.getExtension().getExtension() != null) {
            Map<String, Object> ext = field.getExtension().getExtension();
            Object displayName = ext.get("displayName");
            if (displayName != null) dto.setDisplayName(displayName.toString());
            Object desc = ext.get("description");
            if (desc != null) dto.setDescription(desc.toString());
        }

        // Extract from field feature
        if (field.getFeature() != null) {
            if (field.getFeature().getComputeExpression() != null) {
                dto.setComputeExpression(field.getFeature().getComputeExpression());
            }
            if (field.getFeature().getVirtualType() != null) {
                dto.setVirtual(!"materialized".equalsIgnoreCase(field.getFeature().getVirtualType()));
            }
        }

        // Apply binding overrides (Layer 2)
        if (binding != null) {
            dto.setRequired(binding.getRequired());
            dto.setVisible(binding.getVisible());
            dto.setEditable(binding.getEditable());
            dto.setAliasCode(binding.getAliasCode());
            dto.setFieldOrder(binding.getFieldOrder());
        }

        return dto;
    }

    /**
     * Create from NamedQueryField (compose/free mode)
     */
    public static ResolvedFieldDTO fromNamedQueryField(NamedQueryField nqField) {
        ResolvedFieldDTO dto = new ResolvedFieldDTO();
        dto.setCode(nqField.getFieldCode());
        dto.setDataType(nqField.getDataType());
        dto.setDisplayName(nqField.getFieldCode()); // Default display name
        dto.setSourceType("named_query_field");
        dto.setVisible(true);
        dto.setEditable(false); // Named query fields are read-only by default
        return dto;
    }

    /**
     * Create a virtual-only computed field (Layer 3 only, no base)
     */
    public static ResolvedFieldDTO fromVirtual(String code, ComputedFieldOverride override) {
        ResolvedFieldDTO dto = new ResolvedFieldDTO();
        dto.setCode(code);
        dto.setSourceType("computed_only");
        dto.setVirtual(true);

        if (override != null) {
            dto.setDisplayName(override.getLabel());
            dto.setDescription(override.getDescription());
            dto.setComputeExpression(override.getExpression());
            dto.setReturnType(override.getReturnType());
            dto.setUiHint(override.getUiHint());
            if (override.getReturnType() != null) {
                dto.setDataType(override.getReturnType());
            }
        }

        return dto;
    }

    // ==================== Merge Method ====================

    /**
     * Merge Layer 3 computed field override onto this DTO.
     * Non-null values in the override will replace existing values.
     */
    public void mergeOverride(ComputedFieldOverride override) {
        if (override == null) return;

        if (override.getExpression() != null) {
            this.computeExpression = override.getExpression();
        }
        if (override.getReturnType() != null) {
            this.returnType = override.getReturnType();
        }
        if (override.getLabel() != null) {
            this.displayName = override.getLabel();
        }
        if (override.getVirtual() != null) {
            this.virtual = override.getVirtual();
        }
        if (override.getDescription() != null) {
            this.description = override.getDescription();
        }
        if (override.getUiHint() != null) {
            this.uiHint = override.getUiHint();
        }
    }
}

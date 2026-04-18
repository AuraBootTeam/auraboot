package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.entity.payload.ComputedFieldOverride;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** Editor UI input: whether this field can be used for sorting in lists. */
    private Boolean sortable;

    /** Editor UI input: whether this field can be used as a filter criterion. */
    private Boolean filterable;

    /** Editor UI input: whether this field accepts writes (used for form kind). */
    private Boolean writable;

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
        FieldFeatureBean feature = field.getFeature();
        if (feature != null) {
            if (feature.getComputeExpression() != null) {
                dto.setComputeExpression(feature.getComputeExpression());
            }
            if (feature.getVirtualType() != null) {
                dto.setVirtual(!"materialized".equalsIgnoreCase(feature.getVirtualType()));
            }
        }

        // Populate editor flags: prefer explicit feature values, fall back to heuristic defaults.
        // sortable/filterable default true for simple scalar types (string/number/date), false for json/text/blob.
        dto.setSortable(feature != null && feature.getSortable() != null
                ? feature.getSortable()
                : defaultQueryableForType(field.getDataType()));
        dto.setFilterable(feature != null && feature.getFilterable() != null
                ? feature.getFilterable()
                : defaultQueryableForType(field.getDataType()));
        // writable = not readonly and not virtual (computed fields are not user-writable).
        dto.setWritable(computeWritable(feature));

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
        // Populate editor flags: trust NamedQueryField.sortable; NamedQueryField has no filterable column,
        // so filterable defaults by dataType (queryable-by-type whitelist). writable=false (query-sourced).
        dto.setSortable(nqField.getSortable() != null
                ? nqField.getSortable()
                : defaultQueryableForType(nqField.getDataType()));
        dto.setFilterable(defaultQueryableForType(nqField.getDataType()));
        dto.setWritable(false);
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

        // Virtual-only computed fields: default to queryable by type, never writable.
        dto.setSortable(defaultQueryableForType(dto.getDataType()));
        dto.setFilterable(defaultQueryableForType(dto.getDataType()));
        dto.setWritable(false);
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

    // ==================== Flag Defaulting Helpers ====================

    private static final Set<String> QUERYABLE_SCALAR_TYPES = Set.of(
            "string", "enum", "dict",
            "integer", "int", "long", "bigint",
            "decimal", "numeric", "float", "double",
            "boolean", "bool",
            "date", "datetime", "timestamp", "time"
    );

    /**
     * Default queryable flag (used for both sortable and filterable) when the source
     * doesn't explicitly specify one. Sortable and filterable share the same scalar-type
     * whitelist today; keeping a single helper makes the shared-default intent explicit
     * and prevents silent divergence if the two flags are ever mis-wired.
     * <p>
     * Simple scalars (string/number/boolean/date/enum/dict) → true;
     * JSON/text/blob/reference/unknown → false.
     */
    private static Boolean defaultQueryableForType(String dataType) {
        if (dataType == null) return Boolean.FALSE;
        return QUERYABLE_SCALAR_TYPES.contains(dataType.toLowerCase(Locale.ROOT));
    }

    /**
     * writable = !readonly && !virtual (computed/transient fields are not user-writable).
     */
    private static Boolean computeWritable(FieldFeatureBean feature) {
        if (feature == null) return Boolean.TRUE;
        boolean readonly = Boolean.TRUE.equals(feature.getReadonly());
        String virtualType = feature.getVirtualType();
        boolean virtual = virtualType != null && !virtualType.isEmpty()
                && !"materialized".equalsIgnoreCase(virtualType);
        return !readonly && !virtual;
    }
}

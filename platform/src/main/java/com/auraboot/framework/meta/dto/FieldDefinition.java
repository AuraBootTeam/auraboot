package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * 字段定义
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDefinition {
    
    /**
     * 字段编码
     */
    private String code;
    
    /**
     * 字段名称
     */
    private String name;
    
    /**
     * 显示名称
     */
    private String displayName;
    
    /**
     * 描述
     */
    private String description;
    
    /**
     * 数据类型
     */
    private String dataType;
    
    /**
     * 列名
     */
    private String columnName;
    
    /**
     * 是否必填
     */
    @Builder.Default
    private Boolean required = false;

    /**
     * 是否参与关键词搜索
     */
    @Builder.Default
    private Boolean searchable = false;

    /**
     * 是否主键
     */
    @Builder.Default
    private Boolean primaryKey = false;
    
    /**
     * 是否唯一
     */
    @Builder.Default
    private Boolean unique = false;
    
    /**
     * 是否显示字段
     */
    @Builder.Default
    private Boolean displayField = false;

    /** UI editor input: checked in model fields tab. Normalized into capabilities.sortableFields at save time. */
    private Boolean sortable;

    /** UI editor input: checked in model fields tab. Normalized into capabilities.filterableFields at save time. */
    private Boolean filterable;
    
    /**
     * 默认值
     */
    private Object defaultValue;
    
    /**
     * 长度
     */
    private Integer length;
    
    /**
     * 最大长度
     */
    private Integer maxLength;
    
    /**
     * 最小长度
     */
    private Integer minLength;
    
    /**
     * 最大值
     */
    private Object maxValue;
    
    /**
     * 最小值
     */
    private Object minValue;
    
    /**
     * 格式
     */
    private String format;
    
    /**
     * 精度
     */
    private Integer precision;
    
    /**
     * 小数位数
     */
    private Integer scale;
    
    /**
     * 排序
     */
    private Integer sortOrder;
    
    /**
     * 数据类型映射
     */
    private DataTypeMapping dataTypeMapping;
    
    /**
     * 验证规则列表
     */
    private List<ValidationRule> validationRules;
    
    /**
     * Virtual field type: COMPUTED_READONLY, MATERIALIZED, TRANSIENT
     */
    private String virtualType;

    /**
     * Compute expression (SQL expression for computed columns)
     */
    private String computeExpression;

    /**
     * Dependency field codes
     */
    private List<String> computeDependencies;

    /**
     * JSONB virtual field: host column name (e.g., "crm_act_ext").
     * When set, this field is stored inside the host JSONB column, not as its own physical column.
     */
    private String jsonbColumn;

    /**
     * JSONB virtual field: key path inside the host JSONB column (e.g., "duration").
     * Simple string key for MVP — no nested path support.
     */
    private String jsonbPath;

    /**
     * Reference target configuration for REFERENCE data type fields.
     * Specifies the target entity and display field for join enrichment.
     */
    private RefTarget refTarget;

    /**
     * 扩展属性
     */
    private Map<String, Object> extraProps;

    /**
     * Unconditional field-level invariant: the field may never change once the
     * record exists, in any state.
     */
    private Boolean immutable;

    /**
     * Conditional field-level invariant: the field may not change while the record
     * is in one of the locking states.
     *
     * <p><b>This is not a permission.</b> It binds every subject and every write path —
     * admin, system handlers and commands that inherited an aggregate's authority
     * included — and can never be granted away by a role, scope or ACL. Changing a
     * locked field requires a legal state transition that moves the record out of the
     * locking state, not a broader privilege.</p>
     */
    private ImmutableWhen immutableWhen;

    /**
     * Locking condition for {@link FieldDefinition#immutableWhen}: the guarded field is
     * frozen while {@code field}'s <em>current</em> (pre-update) value is one of {@code in}.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImmutableWhen {
        /** Code of the field whose current value decides whether the lock applies. */
        private String field;
        /** The guarded field is frozen while {@link #field} currently holds one of these values. */
        private List<String> in;
    }

    // 便利方法
    public boolean isImmutable() {
        return Boolean.TRUE.equals(immutable);
    }

    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
    
    public boolean isPrimaryKey() {
        return Boolean.TRUE.equals(primaryKey);
    }
    
    public boolean isUnique() {
        return Boolean.TRUE.equals(unique);
    }
    
    public boolean isDisplayField() {
        return Boolean.TRUE.equals(displayField);
    }

    public boolean isSearchable() {
        return Boolean.TRUE.equals(searchable);
    }

    public boolean isVirtual() {
        return virtualType != null && !virtualType.isEmpty();
    }

    public boolean isComputedReadonly() {
        return "computed_readonly".equals(virtualType);
    }

    public boolean isMaterialized() {
        return "materialized".equals(virtualType);
    }

    public boolean isTransientField() {
        return "transient".equals(virtualType);
    }

    /**
     * Whether this field is a JSONB virtual field (stored inside a host JSONB column).
     */
    public boolean isJsonbVirtual() {
        return jsonbColumn != null && !jsonbColumn.isEmpty()
                && jsonbPath != null && !jsonbPath.isEmpty();
    }

    /**
     * Generate the SQL SELECT expression for extracting this field from its host JSONB column.
     * Returns null for regular (non-JSONB-virtual) fields.
     *
     * Examples:
     *   STRING  → ext->>'name'
     *   INTEGER → (ext->>'count')::integer
     *   DECIMAL → (ext->>'amount')::numeric
     */
    public String getJsonbSelectExpression() {
        if (!isJsonbVirtual()) return null;
        String extract = jsonbColumn + "->>'" + jsonbPath + "'";
        String cast = mapDataTypeToPgCast(dataType);
        if (cast == null) return extract;
        return "(" + extract + ")::" + cast;
    }

    /**
     * Generate the SQL expression for use in WHERE/ORDER BY clauses.
     * Same as getJsonbSelectExpression() but used for filter/sort contexts.
     */
    public String getJsonbFilterExpression() {
        return getJsonbSelectExpression();
    }

    /**
     * Reference target configuration: target entity model code and display field.
     * Used for REFERENCE data type field join enrichment.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefTarget {
        /** Model code of the referenced entity (e.g. "crm_account"). */
        @JsonAlias("targetModel")
        private String targetEntity;
        /** Physical target table when a legacy/system reference cannot be resolved by model code. */
        @JsonAlias("table")
        private String targetTable;
        /** Field/column used as the stored raw value. Defaults to pid at runtime. */
        private String valueField;
        /** Legacy relation/display key preserved for older plugin payloads. */
        private String targetField;
        /** Field code to display from the referenced entity (e.g. "name"). Defaults to "name". */
        private String displayField;
    }

    private static String mapDataTypeToPgCast(String dataType) {
        if (dataType == null) return null;
        return switch (dataType.toLowerCase(Locale.ROOT)) {
            case "string", "text", "enum", "dict" -> null; // ->> returns text, no cast needed
            case "integer", "int" -> "integer";
            case "long", "bigint" -> "bigint";
            case "decimal", "numeric", "float", "double" -> "numeric";
            case "boolean", "bool" -> "boolean";
            case "date" -> "date";
            case "datetime", "timestamp" -> "timestamp";
            case "time" -> "time";
            default -> null;
        };
    }
}

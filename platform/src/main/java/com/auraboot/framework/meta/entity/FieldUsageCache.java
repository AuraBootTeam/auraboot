package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Field usage cache entity
 * Maps to table: ab_field_usage_cache
 * 
 * Caches field usage statistics for performance
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "ab_field_usage_cache", autoResultMap = true)
public class FieldUsageCache {

    /**
     * Primary key
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Tenant ID
     */
    @TableField("tenant_id")
    private Long tenantId;

    /**
     * Field ID
     * References ab_meta_field(id)
     */
    @TableField("field_id")
    private Long fieldId;

    /**
     * Model count
     * Number of models using this field
     */
    @TableField("model_count")
    private Integer modelCount;

    /**
     * Page count
     * Number of pages using this field
     */
    @TableField("page_count")
    private Integer pageCount;

    /**
     * Query count
     * Number of named queries using this field
     */
    @TableField("query_count")
    private Integer queryCount;

    /**
     * Total references
     * Total number of references across all contexts
     */
    @TableField("total_references")
    private Integer totalReferences;

    /**
     * Is core field flag
     * True if this is a system/core field (id, created_at, etc.)
     */
    @TableField("is_core_field")
    private Boolean isCoreField;

    /**
     * Last used at timestamp
     * When this field was last used in any context
     */
    @TableField("last_used_at")
    private Instant lastUsedAt;

    /**
     * Usage frequency
     * Calculated metric for usage frequency (0-100)
     */
    @TableField("usage_frequency")
    private BigDecimal usageFrequency;

    /**
     * Updated at timestamp
     * When this cache entry was last updated
     */
    @TableField("updated_at")
    private Instant updatedAt;

    /**
     * Get total usage count
     * @return sum of all usage counts
     */
    public Integer getTotalUsageCount() {
        int model = modelCount != null ? modelCount : 0;
        int page = pageCount != null ? pageCount : 0;
        int query = queryCount != null ? queryCount : 0;
        return model + page + query;
    }

    /**
     * Check if field is unused
     * @return true if no references exist
     */
    public boolean isUnused() {
        return getTotalUsageCount() == 0;
    }

    /**
     * Check if field is highly used
     * @return true if usage frequency >= 50
     */
    public boolean isHighlyUsed() {
        return usageFrequency != null && usageFrequency.compareTo(BigDecimal.valueOf(50)) >= 0;
    }
}

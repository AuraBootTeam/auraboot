package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.FieldUsageCache;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Field usage cache mapper
 * Maps to table: ab_field_usage_cache
 */
@Mapper
public interface FieldUsageCacheMapper extends BaseMapper<FieldUsageCache> {

    /**
     * Find usage cache by field ID
     * @param fieldId Field ID
     * @return Usage cache record
     */
    @Select("SELECT * FROM ab_field_usage_cache WHERE field_id = #{fieldId} LIMIT 1")
    FieldUsageCache findByFieldId(@Param("fieldId") Long fieldId);

    /**
     * Find usage cache by tenant and field
     * @param tenantId Tenant ID
     * @param fieldId Field ID
     * @return Usage cache record
     */
    @Select("SELECT * FROM ab_field_usage_cache WHERE tenant_id = #{tenantId} AND field_id = #{fieldId} LIMIT 1")
    FieldUsageCache findByTenantAndField(@Param("tenantId") Long tenantId, @Param("fieldId") Long fieldId);

    /**
     * Find all core fields
     * @param tenantId Tenant ID
     * @return Core field usage cache list
     */
    @Select("SELECT * FROM ab_field_usage_cache WHERE tenant_id = #{tenantId} AND is_core_field = TRUE ORDER BY usage_frequency DESC")
    List<FieldUsageCache> findCoreFields(@Param("tenantId") Long tenantId);

    /**
     * Find unused fields
     * @param tenantId Tenant ID
     * @return Unused field usage cache list
     */
    @Select("SELECT * FROM ab_field_usage_cache WHERE tenant_id = #{tenantId} AND total_references = 0 ORDER BY updated_at DESC")
    List<FieldUsageCache> findUnusedFields(@Param("tenantId") Long tenantId);

    /**
     * Find highly used fields
     * @param tenantId Tenant ID
     * @param minUsageFrequency Minimum usage frequency threshold
     * @return Highly used field usage cache list
     */
    @Select("SELECT * FROM ab_field_usage_cache WHERE tenant_id = #{tenantId} AND usage_frequency >= #{minUsageFrequency} ORDER BY usage_frequency DESC")
    List<FieldUsageCache> findHighlyUsedFields(@Param("tenantId") Long tenantId, @Param("minUsageFrequency") Double minUsageFrequency);

    /**
     * Find fields by usage count range
     * @param tenantId Tenant ID
     * @param minCount Minimum usage count
     * @param maxCount Maximum usage count
     * @return Field usage cache list
     */
    @Select("""
        <script>
        SELECT * FROM ab_field_usage_cache
        WHERE tenant_id = #{tenantId}
        <if test="minCount != null">
          AND total_references >= #{minCount}
        </if>
        <if test="maxCount != null">
          AND total_references &lt;= #{maxCount}
        </if>
        ORDER BY total_references DESC
        </script>
        """)
    List<FieldUsageCache> findByUsageCountRange(
        @Param("tenantId") Long tenantId,
        @Param("minCount") Integer minCount,
        @Param("maxCount") Integer maxCount
    );

    /**
     * Update usage statistics
     * @param fieldId Field ID
     * @param modelCount Model count
     * @param pageCount Page count
     * @param queryCount Query count
     * @param totalReferences Total references
     * @param usageFrequency Usage frequency
     * @return Updated rows
     */
    @Update("""
        UPDATE ab_field_usage_cache
        SET model_count = #{modelCount},
            page_count = #{pageCount},
            query_count = #{queryCount},
            total_references = #{totalReferences},
            usage_frequency = #{usageFrequency},
            updated_at = NOW()
        WHERE field_id = #{fieldId}
        """)
    int updateUsageStatistics(
        @Param("fieldId") Long fieldId,
        @Param("modelCount") Integer modelCount,
        @Param("pageCount") Integer pageCount,
        @Param("queryCount") Integer queryCount,
        @Param("totalReferences") Integer totalReferences,
        @Param("usageFrequency") Double usageFrequency
    );

    /**
     * Upsert usage cache (insert or update)
     * @param cache Usage cache record
     * @return Affected rows
     */
    @Insert("""
        INSERT INTO ab_field_usage_cache
        (tenant_id, field_id, model_count, page_count, query_count, total_references,
         is_core_field, last_used_at, usage_frequency, updated_at)
        VALUES
        (#{tenantId}, #{fieldId}, #{modelCount}, #{pageCount}, #{queryCount}, #{totalReferences},
         #{isCoreField}, #{lastUsedAt}, #{usageFrequency}, NOW())
        ON CONFLICT (tenant_id, field_id)
        DO UPDATE SET
            model_count = EXCLUDED.model_count,
            page_count = EXCLUDED.page_count,
            query_count = EXCLUDED.query_count,
            total_references = EXCLUDED.total_references,
            is_core_field = EXCLUDED.is_core_field,
            last_used_at = EXCLUDED.last_used_at,
            usage_frequency = EXCLUDED.usage_frequency,
            updated_at = NOW()
        """)
    int upsert(FieldUsageCache cache);

    /**
     * Delete usage cache by field ID
     * @param fieldId Field ID
     * @return Deleted rows
     */
    @Delete("DELETE FROM ab_field_usage_cache WHERE field_id = #{fieldId}")
    int deleteByFieldId(@Param("fieldId") Long fieldId);

    /**
     * Find all usage cache by tenant
     * @param tenantId Tenant ID
     * @return Usage cache list
     */
    @Select("SELECT * FROM ab_field_usage_cache WHERE tenant_id = #{tenantId} ORDER BY total_references DESC")
    List<FieldUsageCache> findByTenantId(@Param("tenantId") Long tenantId);

    /**
     * Count fields by usage
     * @param tenantId Tenant ID
     * @return Total count
     */
    @Select("SELECT COUNT(*) FROM ab_field_usage_cache WHERE tenant_id = #{tenantId}")
    long countByTenantId(@Param("tenantId") Long tenantId);
}

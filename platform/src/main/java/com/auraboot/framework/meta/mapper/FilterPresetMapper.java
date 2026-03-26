package com.auraboot.framework.meta.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.auraboot.framework.meta.entity.FilterPreset;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for FilterPreset entity.
 *
 * @since 3.4.0
 */
@Mapper
public interface FilterPresetMapper extends BaseMapper<FilterPreset> {

    /**
     * Find presets for a page (global + current user).
     */
    @Select("""
        SELECT * FROM ab_filter_preset
        WHERE tenant_id = #{tenantId}
          AND page_code = #{pageCode}
          AND (user_id IS NULL OR user_id = #{userId})
        ORDER BY sort_order ASC, created_at DESC
        """)
    List<FilterPreset> findByPageCode(@Param("tenantId") Long tenantId,
                                      @Param("pageCode") String pageCode,
                                      @Param("userId") Long userId);

    /**
     * Clear default flag for all presets on a page (for a user or global).
     */
    @Update("""
        UPDATE ab_filter_preset
        SET is_default = FALSE, updated_at = now()
        WHERE tenant_id = #{tenantId}
          AND page_code = #{pageCode}
          AND (user_id IS NULL OR user_id = #{userId})
        """)
    int clearDefaults(@Param("tenantId") Long tenantId,
                      @Param("pageCode") String pageCode,
                      @Param("userId") Long userId);

    /**
     * Set a preset as default.
     */
    @Update("""
        UPDATE ab_filter_preset
        SET is_default = TRUE, updated_at = now()
        WHERE id = #{id} AND tenant_id = #{tenantId}
        """)
    int setDefault(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /**
     * Delete by ID with tenant guard.
     */
    @Delete("DELETE FROM ab_filter_preset WHERE id = #{id} AND tenant_id = #{tenantId}")
    int deleteByIdAndTenant(@Param("id") Long id, @Param("tenantId") Long tenantId);
}

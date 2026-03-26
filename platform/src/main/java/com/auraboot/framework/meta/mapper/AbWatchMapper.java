package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.AbWatch;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for ab_watch table.
 *
 * @since 6.1.0
 */
@Mapper
public interface AbWatchMapper extends BaseMapper<AbWatch> {

    /**
     * Get all watcher user IDs for a specific record.
     */
    @Select("""
        SELECT user_id FROM ab_watch
        WHERE tenant_id = #{tenantId}
          AND model_code = #{modelCode}
          AND record_id = #{recordId}
        """)
    List<Long> findWatcherUserIds(@Param("tenantId") Long tenantId,
                                  @Param("modelCode") String modelCode,
                                  @Param("recordId") Long recordId);

    /**
     * Get all watched record IDs for a user within a model.
     */
    @Select("""
        SELECT record_id FROM ab_watch
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
          AND model_code = #{modelCode}
        """)
    List<Long> findWatchedRecordIds(@Param("tenantId") Long tenantId,
                                    @Param("userId") Long userId,
                                    @Param("modelCode") String modelCode);
}

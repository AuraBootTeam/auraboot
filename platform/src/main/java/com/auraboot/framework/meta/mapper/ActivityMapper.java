package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.Activity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for ab_activity table.
 */
@Mapper
public interface ActivityMapper extends BaseMapper<Activity> {

    /**
     * Find activities for a specific record, ordered by most recent first.
     */
    @Select("""
        SELECT * FROM ab_activity
        WHERE tenant_id = #{tenantId}
          AND object_model = #{objectModel}
          AND object_record = #{objectRecord}
        ORDER BY occurred_at DESC
        LIMIT #{limit}
        """)
    List<Activity> findByObjectRecord(@Param("tenantId") Long tenantId,
                                       @Param("objectModel") String objectModel,
                                       @Param("objectRecord") String objectRecord,
                                       @Param("limit") int limit);

    /**
     * Count activities for a specific record.
     */
    @Select("""
        SELECT COUNT(*) FROM ab_activity
        WHERE tenant_id = #{tenantId}
          AND object_model = #{objectModel}
          AND object_record = #{objectRecord}
        """)
    int countByObjectRecord(@Param("tenantId") Long tenantId,
                            @Param("objectModel") String objectModel,
                            @Param("objectRecord") String objectRecord);
}

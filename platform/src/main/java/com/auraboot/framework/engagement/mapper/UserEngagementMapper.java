package com.auraboot.framework.engagement.mapper;

import com.auraboot.framework.engagement.entity.UserEngagement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

/**
 * MyBatis Plus mapper for {@link UserEngagement}.
 */
@Mapper
public interface UserEngagementMapper extends BaseMapper<UserEngagement> {

    /** Concurrent first visits share the stored identity, creation time and manual order. */
    @Select(value = """
            INSERT INTO ab_user_engagement
              (id, user_id, tenant_id, target_type, target_id, target_label, target_context,
               engagement_type, sort_order, created_at, updated_at)
            VALUES
              (#{record.id}, #{record.userId}, #{record.tenantId}, #{record.targetType},
               #{record.targetId}, #{record.targetLabel},
               #{record.targetContext,typeHandler=com.auraboot.framework.plugin.typehandler.PluginSettingsTypeHandler,jdbcType=OTHER},
               #{record.engagementType}, #{record.sortOrder}, #{record.createdAt}, #{record.updatedAt})
            ON CONFLICT (user_id, tenant_id, target_type, target_id, engagement_type) DO UPDATE
              SET target_label = EXCLUDED.target_label, target_context = EXCLUDED.target_context,
                  updated_at = GREATEST(ab_user_engagement.updated_at, EXCLUDED.updated_at)
            RETURNING *
            """, affectData = true)
    @ResultMap("mybatis-plus_UserEngagement")
    UserEngagement insertOrRefresh(@Param("record") UserEngagement record);
}

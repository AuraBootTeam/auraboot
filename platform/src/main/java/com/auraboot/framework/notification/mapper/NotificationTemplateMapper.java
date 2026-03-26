package com.auraboot.framework.notification.mapper;

import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for NotificationTemplate entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface NotificationTemplateMapper extends BaseMapper<NotificationTemplate> {

    @Select("""
        SELECT * FROM ab_notification_template
        WHERE tenant_id = #{tenantId} AND code = #{code} AND enabled = TRUE
        """)
    NotificationTemplate findByCode(@Param("tenantId") Long tenantId, @Param("code") String code);

    @Select("""
        SELECT * FROM ab_notification_template
        WHERE tenant_id = #{tenantId} AND pid = #{pid}
        """)
    NotificationTemplate findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("""
        SELECT * FROM ab_notification_template
        WHERE tenant_id = #{tenantId} AND channel = #{channel}
        ORDER BY created_at DESC
        """)
    List<NotificationTemplate> findByChannel(@Param("tenantId") Long tenantId, @Param("channel") String channel);

    @Select("""
        SELECT * FROM ab_notification_template
        WHERE tenant_id = #{tenantId}
        ORDER BY created_at DESC
        """)
    List<NotificationTemplate> findAll(@Param("tenantId") Long tenantId);

    @Delete("DELETE FROM ab_notification_template WHERE tenant_id = #{tenantId} AND pid = #{pid}")
    int deleteByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);
}

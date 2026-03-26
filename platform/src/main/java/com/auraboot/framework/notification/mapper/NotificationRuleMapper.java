package com.auraboot.framework.notification.mapper;

import com.auraboot.framework.notification.entity.NotificationRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * Mapper for NotificationRule entity.
 *
 * @since 5.2.0
 */
@Mapper
public interface NotificationRuleMapper extends BaseMapper<NotificationRule> {

    @Select("""
        SELECT * FROM ab_notification_rule
        WHERE tenant_id = #{tenantId}
          AND deleted_flag = FALSE
        ORDER BY created_at DESC
        """)
    List<NotificationRule> findAllByTenant(@Param("tenantId") Long tenantId);

    @Select("""
        SELECT * FROM ab_notification_rule
        WHERE tenant_id = #{tenantId}
          AND enabled = TRUE
          AND trigger_type = #{triggerType}
          AND deleted_flag = FALSE
        ORDER BY id ASC
        """)
    List<NotificationRule> findEnabledByTriggerType(@Param("tenantId") Long tenantId,
                                                     @Param("triggerType") String triggerType);

    @Select("""
        SELECT * FROM ab_notification_rule
        WHERE tenant_id = #{tenantId} AND code = #{code}
          AND deleted_flag = FALSE
        LIMIT 1
        """)
    Optional<NotificationRule> findByCode(@Param("tenantId") Long tenantId,
                                           @Param("code") String code);

    @Select("""
        SELECT COUNT(*) FROM ab_notification_rule
        WHERE tenant_id = #{tenantId}
          AND deleted_flag = FALSE
        """)
    long countByTenant(@Param("tenantId") Long tenantId);
}

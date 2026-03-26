package com.auraboot.framework.webhook.mapper;

import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for WebhookSubscription entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface WebhookSubscriptionMapper extends BaseMapper<WebhookSubscription> {

    @Select("SELECT * FROM ab_webhook_subscription WHERE tenant_id = #{tenantId} AND pid = #{pid}")
    WebhookSubscription findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("""
        SELECT * FROM ab_webhook_subscription
        WHERE tenant_id = #{tenantId} AND event_type = #{eventType} AND enabled = TRUE
        """)
    List<WebhookSubscription> findByEventType(@Param("tenantId") Long tenantId,
                                               @Param("eventType") String eventType);

    @Select("SELECT * FROM ab_webhook_subscription WHERE tenant_id = #{tenantId} ORDER BY created_at DESC")
    List<WebhookSubscription> findByTenant(@Param("tenantId") Long tenantId);

    @Update("""
        UPDATE ab_webhook_subscription SET enabled = #{enabled}, updated_at = now()
        WHERE tenant_id = #{tenantId} AND pid = #{pid}
        """)
    int updateEnabled(@Param("tenantId") Long tenantId, @Param("pid") String pid,
                      @Param("enabled") boolean enabled);

    @Delete("DELETE FROM ab_webhook_subscription WHERE tenant_id = #{tenantId} AND pid = #{pid}")
    int deleteByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);
}

package com.auraboot.framework.notification.mapper;

import com.auraboot.framework.notification.entity.NotificationSendLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;

/**
 * Mapper for NotificationSendLog entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface NotificationSendLogMapper extends BaseMapper<NotificationSendLog> {
    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
        INSERT INTO ab_notification_send_log(tenant_id,channel,recipient,status,delivery_key,request_hash,
          resolution_reference,configuration_hash,request_snapshot)
        VALUES(#{tenantId},'wechat_subscription',#{recipient},'pending',#{deliveryKey},#{requestHash},
          #{resolutionReference},#{configurationHash},
          #{requestSnapshot,jdbcType=OTHER,typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb)
        ON CONFLICT(tenant_id,channel,delivery_key) WHERE delivery_key IS NOT NULL DO NOTHING
        """)
    int insertDurableIntent(NotificationSendLog intent);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT id,tenant_id,channel,recipient,status,delivery_key,request_hash,resolution_reference,configuration_hash,
          request_snapshot::text AS request_snapshot,attempt_fence::text AS attempt_fence,
          attempt_started_at,attempt_lease_until,finished_at,provider_code
        FROM ab_notification_send_log WHERE tenant_id=#{tenant} AND channel='wechat_subscription' AND delivery_key=#{key}
        """)
    NotificationSendLog findDurable(@Param("tenant") long tenant,@Param("key") String key);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE ab_notification_send_log SET status='attempting',attempt_fence=#{fence}::uuid,
          attempt_started_at=clock_timestamp(),attempt_lease_until=clock_timestamp()+INTERVAL '5 minutes'
        WHERE tenant_id=#{tenant} AND channel='wechat_subscription' AND delivery_key=#{key}
          AND request_hash=#{hash} AND status='pending'
        """)
    int claimDurable(@Param("tenant") long tenant,@Param("key") String key,@Param("hash") String hash,@Param("fence") String fence);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE ab_notification_send_log SET status=#{state},provider_code=#{code},finished_at=clock_timestamp(),
          error_message=#{reason}
        WHERE tenant_id=#{tenant} AND channel='wechat_subscription' AND delivery_key=#{key}
          AND request_hash=#{hash} AND status='attempting' AND attempt_fence=#{fence}::uuid
        """)
    int finishDurable(@Param("tenant") long tenant,@Param("key") String key,@Param("hash") String hash,
            @Param("fence") String fence,@Param("state") String state,@Param("code") Long code,@Param("reason") String reason);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
        UPDATE ab_notification_send_log SET status='unknown',finished_at=clock_timestamp(),error_message='ATTEMPT_EXPIRED'
        WHERE tenant_id=#{tenant} AND channel='wechat_subscription' AND delivery_key=#{key}
          AND status='attempting' AND attempt_lease_until<clock_timestamp()
        """)
    int expireDurable(@Param("tenant") long tenant,@Param("key") String key);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT delivery_key FROM ab_notification_send_log WHERE tenant_id=#{tenant} AND channel='wechat_subscription'
          AND delivery_key IS NOT NULL AND (status='pending' OR (status='attempting' AND attempt_lease_until<clock_timestamp()))
        ORDER BY id LIMIT #{limit}
        """)
    List<String> dueDurable(@Param("tenant") long tenant,@Param("limit") int limit);
}

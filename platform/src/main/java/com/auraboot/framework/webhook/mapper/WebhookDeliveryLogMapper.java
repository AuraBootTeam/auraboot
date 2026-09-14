package com.auraboot.framework.webhook.mapper;

import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for WebhookDeliveryLog entity.
 *
 * @since 5.1.0
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface WebhookDeliveryLogMapper extends BaseMapper<WebhookDeliveryLog> {
    @Select("""
            WITH candidates AS (
                SELECT id FROM ab_webhook_delivery_log
                WHERE (delivery_status = 'pending' AND next_retry_at <= clock_timestamp())
                   OR (delivery_status = 'processing' AND lease_until < clock_timestamp())
                ORDER BY created_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT #{limit}
            )
            UPDATE ab_webhook_delivery_log d
            SET delivery_status = 'processing', lease_owner = #{leaseOwner},
                lease_token = #{leaseToken}, lease_until = #{leaseUntil}, last_attempt_at = NOW(),
                updated_at = NOW()
            FROM candidates c WHERE d.id = c.id
            RETURNING d.*
            """)
    List<WebhookDeliveryLog> claimReady(@Param("limit") int limit,
                                        @Param("leaseOwner") String leaseOwner,
                                        @Param("leaseToken") String leaseToken,
                                        @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            UPDATE ab_webhook_delivery_log
            SET delivery_status = 'success', response_status = #{responseStatus},
                response_body = #{responseBody}, delivered_at = NOW(), error_message = NULL,
                lease_owner = NULL, lease_token = NULL, lease_until = NULL, updated_at = NOW()
            WHERE id = #{id} AND delivery_status = 'processing' AND lease_token = #{leaseToken}
            """)
    int markSuccess(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                    @Param("responseStatus") int responseStatus,
                    @Param("responseBody") String responseBody);

    @Update("""
            UPDATE ab_webhook_delivery_log
            SET retry_count = retry_count + 1,
                delivery_status = CASE WHEN retry_count + 1 >= max_retries
                    THEN 'dead_letter' ELSE 'pending' END,
                response_status = #{responseStatus}, response_body = #{responseBody},
                error_message = #{errorMessage}, next_retry_at = #{nextRetryAt},
                lease_owner = NULL, lease_token = NULL, lease_until = NULL, updated_at = NOW()
            WHERE id = #{id} AND delivery_status = 'processing' AND lease_token = #{leaseToken}
            """)
    int markFailure(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                    @Param("responseStatus") Integer responseStatus,
                    @Param("responseBody") String responseBody,
                    @Param("errorMessage") String errorMessage,
                    @Param("nextRetryAt") Instant nextRetryAt);

    @Update("""
            UPDATE ab_webhook_delivery_log
            SET retry_count = retry_count + 1, delivery_status = 'dead_letter',
                error_message = #{errorMessage}, lease_owner = NULL, lease_token = NULL,
                lease_until = NULL, updated_at = NOW()
            WHERE id = #{id} AND delivery_status = 'processing' AND lease_token = #{leaseToken}
            """)
    int markPermanentFailure(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                             @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE ab_webhook_delivery_log
            SET delivery_status = 'pending', retry_count = 0, next_retry_at = NOW(),
                response_status = NULL, response_body = NULL, error_message = NULL,
                delivered_at = NULL, lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                replay_count = replay_count + 1, last_replayed_at = NOW(),
                last_replayed_by_pid = #{actorPid}, updated_at = NOW()
            WHERE tenant_id = #{tenantId} AND pid = #{pid}
              AND delivery_status IN ('dead_letter', 'failed')
            """)
    int replay(@Param("tenantId") Long tenantId, @Param("pid") String pid,
               @Param("actorPid") String actorPid);

    @Select("""
            SELECT d.pid, d.subscription_pid, s.name AS subscription_name,
                   d.event_id, d.delivery_status, d.retry_count, d.max_retries,
                   d.response_status, d.next_retry_at, d.last_attempt_at, d.delivered_at,
                   d.replay_count, d.last_replayed_at, d.created_at
            FROM ab_webhook_delivery_log d
            LEFT JOIN ab_webhook_subscription s
              ON s.tenant_id = d.tenant_id AND s.pid = d.subscription_pid
            WHERE d.tenant_id = #{tenantId} AND d.installation_pid = #{installationPid}
              AND (CAST(#{status} AS varchar) IS NULL OR d.delivery_status = #{status})
            ORDER BY d.created_at DESC, d.id DESC LIMIT #{limit}
            """)
    List<OperationsDelivery> findForOperations(@Param("tenantId") Long tenantId,
                                                @Param("installationPid") String installationPid,
                                                @Param("status") String status,
                                                @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM ab_webhook_delivery_log
            WHERE tenant_id = #{tenantId} AND installation_pid = #{installationPid}
              AND delivery_status = 'dead_letter' AND created_at >= #{since}
            """)
    long countDeadLetters(@Param("tenantId") Long tenantId,
                          @Param("installationPid") String installationPid,
                          @Param("since") Instant since);

    @Update("""
            UPDATE ab_webhook_delivery_log
            SET delivery_status = 'pending', retry_count = 0, next_retry_at = NOW(),
                response_status = NULL, response_body = NULL, error_message = NULL,
                delivered_at = NULL, lease_owner = NULL, lease_token = NULL, lease_until = NULL,
                replay_count = replay_count + 1, last_replayed_at = NOW(),
                last_replayed_by_pid = #{actorPid}, updated_at = NOW()
            WHERE tenant_id = #{tenantId} AND installation_pid = #{installationPid} AND pid = #{pid}
              AND delivery_status IN ('dead_letter', 'failed')
            """)
    int replayForInstallation(@Param("tenantId") Long tenantId,
                              @Param("installationPid") String installationPid,
                              @Param("pid") String pid,
                              @Param("actorPid") String actorPid);

    record OperationsDelivery(String pid, String subscriptionPid, String subscriptionName,
                              String eventId, String deliveryStatus, Integer retryCount,
                              Integer maxRetries, Integer responseStatus, Instant nextRetryAt,
                              Instant lastAttemptAt, Instant deliveredAt,
                              Integer replayCount, Instant lastReplayedAt, Instant createdAt) { }
}

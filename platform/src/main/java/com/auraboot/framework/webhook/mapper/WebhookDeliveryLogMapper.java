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
    @org.apache.ibatis.annotations.Insert("""
            INSERT INTO ab_webhook_receiver_lane(tenant_id, receiver_key)
            VALUES(#{tenantId}, #{receiverKey}) ON CONFLICT DO NOTHING
            """)
    int registerReceiver(@Param("tenantId") Long tenantId, @Param("receiverKey") String receiverKey);

    @Select("""
            WITH candidates AS MATERIALIZED (
                SELECT l.tenant_id, l.receiver_key FROM ab_webhook_receiver_lane l
                WHERE (l.lease_until IS NULL OR l.lease_until < clock_timestamp())
                  AND l.ready_at <= clock_timestamp()
                  AND EXISTS (SELECT 1 FROM ab_webhook_delivery_log d
                      WHERE d.tenant_id=l.tenant_id AND d.receiver_key=l.receiver_key
                        AND ((d.delivery_status='pending' AND d.next_retry_at<=clock_timestamp())
                          OR (d.delivery_status='processing' AND d.lease_until<clock_timestamp())))
                ORDER BY l.last_claimed_at, l.tenant_id, l.receiver_key
                FOR UPDATE OF l SKIP LOCKED LIMIT #{limit}
            ), lanes AS (
                UPDATE ab_webhook_receiver_lane l
                SET lease_token=#{leaseToken}, lease_until=#{leaseUntil}, last_claimed_at=clock_timestamp()
                FROM candidates c WHERE l.tenant_id=c.tenant_id AND l.receiver_key=c.receiver_key
                  AND (l.lease_until IS NULL OR l.lease_until<clock_timestamp())
                RETURNING l.tenant_id,l.receiver_key
            ), attempts AS (
                SELECT selected.id FROM lanes l CROSS JOIN LATERAL (
                    SELECT d.id FROM ab_webhook_delivery_log d
                    WHERE d.tenant_id=l.tenant_id AND d.receiver_key=l.receiver_key
                      AND ((d.delivery_status='pending' AND d.next_retry_at<=clock_timestamp())
                        OR (d.delivery_status='processing' AND d.lease_until<clock_timestamp()))
                    ORDER BY d.created_at,d.id FOR UPDATE OF d SKIP LOCKED LIMIT 1
                ) selected
            )
            UPDATE ab_webhook_delivery_log d
            SET delivery_status='processing', lease_owner=#{leaseOwner}, lease_token=#{leaseToken},
                lease_until=#{leaseUntil},last_attempt_at=clock_timestamp(),updated_at=clock_timestamp()
            FROM attempts a WHERE d.id=a.id
              AND ((d.delivery_status='pending' AND d.next_retry_at<=clock_timestamp())
                OR (d.delivery_status='processing' AND d.lease_until<clock_timestamp()))
            RETURNING d.*
            """)
    List<WebhookDeliveryLog> claimReady(@Param("limit") int limit,
                                        @Param("leaseOwner") String leaseOwner,
                                        @Param("leaseToken") String leaseToken,
                                        @Param("leaseUntil") Instant leaseUntil);

    @Update("""
            UPDATE ab_webhook_receiver_lane l SET lease_token=NULL,lease_until=NULL
            WHERE l.lease_token=#{leaseToken} AND NOT EXISTS (
                SELECT 1 FROM ab_webhook_delivery_log d WHERE d.tenant_id=l.tenant_id
                  AND d.receiver_key=l.receiver_key AND d.delivery_status='processing'
                  AND d.lease_token=#{leaseToken})
            """)
    int releaseUnusedReceivers(@Param("leaseToken") String leaseToken);

    @Update("""
            WITH completed AS (UPDATE ab_webhook_delivery_log
            SET delivery_status = 'success', response_status = #{responseStatus},
                response_body = #{responseBody}, delivered_at = NOW(), error_message = NULL,
                lease_owner = NULL, lease_token = NULL, lease_until = NULL, updated_at = NOW()
            WHERE id = #{id} AND delivery_status = 'processing' AND lease_token = #{leaseToken}
            RETURNING tenant_id,receiver_key)
            UPDATE ab_webhook_receiver_lane l SET lease_token=NULL,lease_until=NULL
            FROM completed c WHERE l.tenant_id=c.tenant_id AND l.receiver_key=c.receiver_key
                AND l.lease_token=#{leaseToken}
            """)
    int markSuccess(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                    @Param("responseStatus") int responseStatus,
                    @Param("responseBody") String responseBody);

    @Update("""
            WITH completed AS (UPDATE ab_webhook_delivery_log
            SET retry_count = retry_count + 1,
                delivery_status = CASE WHEN retry_count + 1 >= max_retries
                    THEN 'dead_letter' ELSE 'pending' END,
                response_status = #{responseStatus}, response_body = #{responseBody},
                error_message = #{errorMessage}, next_retry_at = #{nextRetryAt},
                lease_owner = NULL, lease_token = NULL, lease_until = NULL, updated_at = NOW()
            WHERE id = #{id} AND delivery_status = 'processing' AND lease_token = #{leaseToken}
            RETURNING tenant_id,receiver_key)
            UPDATE ab_webhook_receiver_lane l SET lease_token=NULL,lease_until=NULL,
                ready_at=#{nextRetryAt}
            FROM completed c WHERE l.tenant_id=c.tenant_id AND l.receiver_key=c.receiver_key
                AND l.lease_token=#{leaseToken}
            """)
    int markFailure(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                    @Param("responseStatus") Integer responseStatus,
                    @Param("responseBody") String responseBody,
                    @Param("errorMessage") String errorMessage,
                    @Param("nextRetryAt") Instant nextRetryAt);

    @Update("""
            WITH completed AS (UPDATE ab_webhook_delivery_log
            SET retry_count = retry_count + 1, delivery_status = 'dead_letter',
                error_message = #{errorMessage}, lease_owner = NULL, lease_token = NULL,
                lease_until = NULL, updated_at = NOW()
            WHERE id = #{id} AND delivery_status = 'processing' AND lease_token = #{leaseToken}
            RETURNING tenant_id,receiver_key)
            UPDATE ab_webhook_receiver_lane l SET lease_token=NULL,lease_until=NULL
            FROM completed c WHERE l.tenant_id=c.tenant_id AND l.receiver_key=c.receiver_key
                AND l.lease_token=#{leaseToken}
            """)
    int markPermanentFailure(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                             @Param("errorMessage") String errorMessage);

    @Update("""
            WITH rebound AS (UPDATE ab_webhook_delivery_log
                SET receiver_key=#{receiverKey},request_url=#{targetUrl},delivery_status='pending',
                    lease_owner=NULL,lease_token=NULL,lease_until=NULL,next_retry_at=clock_timestamp()
                WHERE id=#{id} AND delivery_status='processing' AND lease_token=#{leaseToken}
                RETURNING tenant_id)
            UPDATE ab_webhook_receiver_lane l SET lease_token=NULL,lease_until=NULL
            FROM rebound r WHERE l.tenant_id=r.tenant_id AND l.receiver_key=#{oldReceiverKey}
                AND l.lease_token=#{leaseToken}
            """)
    int rebindReceiver(@Param("id") Long id,@Param("leaseToken") String leaseToken,
                      @Param("oldReceiverKey") String oldReceiverKey,@Param("receiverKey") String receiverKey,
                      @Param("targetUrl") String targetUrl);

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
                   d.event_id, d.request_id, d.delivery_status, d.retry_count, d.max_retries,
                   d.response_status, d.next_retry_at, d.last_attempt_at, d.delivered_at,
                   d.replay_count, d.last_replayed_at, d.created_at, d.error_message
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
                              String eventId, String requestId, String deliveryStatus, Integer retryCount,
                              Integer maxRetries, Integer responseStatus, Instant nextRetryAt,
                              Instant lastAttemptAt, Instant deliveredAt,
                              Integer replayCount, Instant lastReplayedAt, Instant createdAt, String errorMessage) { }
}

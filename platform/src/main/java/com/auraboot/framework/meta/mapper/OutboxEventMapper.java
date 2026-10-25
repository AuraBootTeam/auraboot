package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.OutboxEvent;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Mapper for outbox event table operations.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface OutboxEventMapper {

    // An expired processing lease consumed an attempt even if its worker never recorded a failure.
    String EXHAUSTED_CLAIM = "o.retry_count::bigint + CASE WHEN o.status = 'processing' THEN 1 ELSE 0 END >= o.max_retries";
    String CLAIM_STATE = "UPDATE ab_outbox o SET retry_count = CASE WHEN o.status = 'processing' " +
            "THEN LEAST(o.retry_count::bigint + 1, 2147483647)::integer ELSE o.retry_count END, " +
            "status = CASE WHEN " + EXHAUSTED_CLAIM + " THEN 'failed' ELSE 'processing' END, " +
            "lease_owner = CASE WHEN " + EXHAUSTED_CLAIM + " THEN NULL ELSE #{leaseOwner} END, " +
            "lease_token = CASE WHEN " + EXHAUSTED_CLAIM + " THEN NULL ELSE #{leaseToken} END, " +
            "lease_until = CASE WHEN " + EXHAUSTED_CLAIM + " THEN NULL ELSE #{leaseUntil}::timestamptz END, " +
            "last_error = CASE WHEN o.status = 'processing' THEN 'Recovered expired dispatcher lease' " +
            "ELSE o.last_error END, claimed_at = NOW() ";
    String CLAIM_DEAD_LETTER = ") , dead_lettered AS (INSERT INTO ab_integration_dead_letter " +
            "(outbox_id, tenant_id, event_id, event_type, payload, error_detail, failed_at, status) " +
            "SELECT id, tenant_id, event_id, event_type, payload, last_error, NOW(), 'open' " +
            "FROM claimed WHERE status = 'failed' ON CONFLICT (outbox_id) DO UPDATE " +
            "SET error_detail = EXCLUDED.error_detail, failed_at = EXCLUDED.failed_at, status = 'open') " +
            "SELECT * FROM claimed</script>";

    @Insert("INSERT INTO ab_outbox (tenant_id, event_id, event_type, command_code, payload, " +
            "status, retry_count, max_retries, next_retry_at, created_at) " +
            "VALUES (#{tenantId}, #{eventId}, #{eventType}, #{commandCode}, " +
            "#{payload, jdbcType=OTHER, " +
            "typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{status}, #{retryCount}, #{maxRetries}, #{nextRetryAt}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEvent(OutboxEvent event);

    @Insert("INSERT INTO ab_outbox (tenant_id, event_id, event_type, command_code, schema_version, " +
            "event_source, subject, occurred_at, correlation_id, causation_id, ordering_key, event_sequence, " +
            "payload, headers, status, retry_count, max_retries, next_retry_at, created_at) " +
            "VALUES (#{tenantId}, #{eventId}, #{eventType}, #{commandCode}, #{schemaVersion}, " +
            "#{eventSource}, #{subject}, #{occurredAt}, #{correlationId}, #{causationId}, #{orderingKey}, " +
            "#{eventSequence}, #{payload, jdbcType=OTHER, " +
            "typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{headers, jdbcType=OTHER, " +
            "typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{status}, #{retryCount}, #{maxRetries}, #{nextRetryAt}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEnvelope(OutboxEvent event);

    @Insert("INSERT INTO ab_outbox (tenant_id, event_id, event_type, command_code, schema_version, " +
            "event_source, subject, occurred_at, correlation_id, causation_id, ordering_key, event_sequence, " +
            "payload, headers, status, retry_count, max_retries, next_retry_at, created_at) " +
            "VALUES (#{tenantId}, #{eventId}, #{eventType}, #{commandCode}, #{schemaVersion}, " +
            "#{eventSource}, #{subject}, #{occurredAt}, #{correlationId}, #{causationId}, #{orderingKey}, " +
            "#{eventSequence}, #{payload, jdbcType=OTHER, " +
            "typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{headers, jdbcType=OTHER, " +
            "typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb, " +
            "#{status}, #{retryCount}, #{maxRetries}, #{nextRetryAt}, #{createdAt}) ON CONFLICT (tenant_id, event_id) WHERE schema_version IS NOT NULL DO NOTHING")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEnvelopeIfAbsent(OutboxEvent event);

    @Select("SELECT COUNT(*) FROM ab_outbox WHERE tenant_id = #{tenantId} AND event_id = #{eventId} " +
            "AND event_type = #{eventType} AND command_code IS NOT DISTINCT FROM #{commandCode} " +
            "AND schema_version = #{schemaVersion} AND event_source = #{eventSource} " +
            "AND subject = #{subject} AND occurred_at = #{occurredAt} " +
            "AND correlation_id = #{correlationId} AND causation_id IS NOT DISTINCT FROM #{causationId} " +
            "AND ordering_key = #{orderingKey} AND event_sequence = #{eventSequence} " +
            "AND payload = #{payload, jdbcType=OTHER, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb " +
            "AND headers = #{headers, jdbcType=OTHER, typeHandler=com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler}::jsonb")
    int countMatchingEnvelope(OutboxEvent event);

    @Select("SELECT * FROM ab_outbox WHERE status IN ('pending', 'processing') " +
            "AND next_retry_at <= NOW() ORDER BY created_at LIMIT #{limit}")
    List<OutboxEvent> findReadyEvents(@Param("limit") int limit);

    @Select("<script>WITH candidates AS (" +
            "SELECT o.id FROM ab_outbox o WHERE " +
            "((o.status = 'pending' AND o.next_retry_at &lt;= NOW()) " +
            "OR (o.status = 'processing' AND o.lease_until &lt; NOW())) " +
            "AND (o.ordering_key IS NULL OR NOT EXISTS (SELECT 1 FROM ab_outbox earlier " +
            "WHERE earlier.tenant_id = o.tenant_id AND earlier.ordering_key = o.ordering_key " +
            "AND earlier.event_sequence &lt; o.event_sequence " +
            "AND earlier.status IN ('pending', 'processing'))) " +
            "ORDER BY o.created_at, o.id FOR UPDATE SKIP LOCKED LIMIT #{limit}), " +
            "claimed AS (" + CLAIM_STATE +
            "FROM candidates c WHERE o.id = c.id RETURNING o.*" + CLAIM_DEAD_LETTER)
    List<OutboxEvent> claimReadyEvents(@Param("limit") int limit,
                                      @Param("leaseOwner") String leaseOwner,
                                      @Param("leaseToken") String leaseToken,
                                      @Param("leaseUntil") Instant leaseUntil);

    /** A host-authorized current-tenant dispatch cannot claim another source or an unversioned event. */
    @Select("<script>WITH candidates AS (SELECT o.id FROM ab_outbox o WHERE " +
            "o.tenant_id = #{tenantId} AND o.event_source = #{source} AND o.schema_version IS NOT NULL " +
            "AND o.event_type IN <foreach collection='eventTypes' item='type' open='(' separator=',' close=')'>#{type}</foreach> " +
            "AND ((o.status = 'pending' AND o.next_retry_at &lt;= NOW()) " +
            "OR (o.status = 'processing' AND o.lease_until &lt; NOW())) " +
            "AND (o.ordering_key IS NULL OR NOT EXISTS (SELECT 1 FROM ab_outbox earlier " +
            "WHERE earlier.tenant_id = o.tenant_id AND earlier.ordering_key = o.ordering_key " +
            "AND earlier.event_sequence &lt; o.event_sequence AND earlier.status IN ('pending', 'processing'))) " +
            "ORDER BY o.created_at, o.id FOR UPDATE SKIP LOCKED LIMIT #{limit}), " +
            "claimed AS (" + CLAIM_STATE +
            "FROM candidates c WHERE o.id = c.id AND o.tenant_id = #{tenantId} " +
            "AND o.event_source = #{source} RETURNING o.*" + CLAIM_DEAD_LETTER)
    List<OutboxEvent> claimReadyEventsForTenant(@Param("tenantId") long tenantId,
                                              @Param("source") String source,
                                              @Param("eventTypes") List<String> eventTypes,
                                              @Param("limit") int limit,
                                              @Param("leaseOwner") String leaseOwner,
                                              @Param("leaseToken") String leaseToken,
                                              @Param("leaseUntil") Instant leaseUntil);

    /** Internal exact-event lease claim; never polls or claims another tenant's queue. */
    @Select("<script>WITH candidate AS (" +
            "SELECT o.id FROM ab_outbox o WHERE o.tenant_id = #{tenantId} AND o.event_id = #{eventId} " +
            "AND ((o.status = 'pending' AND o.next_retry_at &lt;= NOW()) " +
            "OR (o.status = 'processing' AND o.lease_until &lt; NOW())) " +
            "AND (o.ordering_key IS NULL OR NOT EXISTS (SELECT 1 FROM ab_outbox earlier " +
            "WHERE earlier.tenant_id = o.tenant_id AND earlier.ordering_key = o.ordering_key " +
            "AND earlier.event_sequence &lt; o.event_sequence " +
            "AND earlier.status IN ('pending', 'processing'))) " +
            "ORDER BY o.created_at, o.id FOR UPDATE SKIP LOCKED LIMIT 1), " +
            "claimed AS (" + CLAIM_STATE +
            "FROM candidate c WHERE o.id = c.id AND o.tenant_id = #{tenantId} AND o.event_id = #{eventId} RETURNING o.*" + CLAIM_DEAD_LETTER)
    List<OutboxEvent> claimKnownEvent(@Param("tenantId") Long tenantId,
                                     @Param("eventId") String eventId,
                                     @Param("leaseOwner") String leaseOwner,
                                     @Param("leaseToken") String leaseToken,
                                     @Param("leaseUntil") Instant leaseUntil);

    @Update("UPDATE ab_outbox SET status = 'processing' WHERE id = #{id} AND status IN ('pending', 'processing')")
    int claimEvent(@Param("id") Long id);

    @Update("UPDATE ab_outbox SET status = 'delivered', delivered_at = NOW(), " +
            "lease_owner = NULL, lease_token = NULL, lease_until = NULL " +
            "WHERE id = #{id} AND status = 'processing' AND lease_token = #{leaseToken} " +
            "AND lease_until > clock_timestamp()")
    int markDeliveredFenced(@Param("id") Long id, @Param("leaseToken") String leaseToken);

    @Update("UPDATE ab_outbox SET status = 'delivered', delivered_at = NOW() WHERE id = #{id}")
    int markDelivered(@Param("id") Long id);

    @Update("UPDATE ab_outbox SET retry_count = retry_count + 1, " +
            "next_retry_at = #{nextRetryAt}, last_error = #{lastError}, " +
            "status = CASE WHEN retry_count + 1 >= max_retries THEN 'failed' ELSE 'pending' END " +
            "WHERE id = #{id}")
    int incrementRetry(@Param("id") Long id, @Param("nextRetryAt") Instant nextRetryAt,
                       @Param("lastError") String lastError);

    @Update("UPDATE ab_outbox SET retry_count = retry_count + 1, next_retry_at = #{nextRetryAt}, " +
            "last_error = #{lastError}, status = CASE WHEN retry_count + 1 >= max_retries " +
            "THEN 'failed' ELSE 'pending' END, lease_owner = NULL, lease_token = NULL, " +
            "lease_until = NULL WHERE id = #{id} AND status = 'processing' AND lease_token = #{leaseToken} " +
            "AND lease_until > clock_timestamp()")
    int recordFailureFenced(@Param("id") Long id, @Param("leaseToken") String leaseToken,
                            @Param("nextRetryAt") Instant nextRetryAt,
                            @Param("lastError") String lastError);

    /** Wall-clock lease expiry must be rechecked even inside a long-running delivery transaction. */
    @Select("SELECT COUNT(*) FROM ab_outbox WHERE tenant_id = #{tenantId} AND event_id = #{eventId} " +
            "AND status = 'processing' AND lease_token = #{leaseToken} AND lease_until > clock_timestamp()")
    int countActiveDeliveryLease(@Param("tenantId") Long tenantId, @Param("eventId") String eventId,
                                 @Param("leaseToken") String leaseToken);

    /** Trusted counter holds this exact lease row until its effect and receipt transaction commits. */
    @Select("SELECT id FROM ab_outbox WHERE tenant_id = #{tenantId} AND event_id = #{eventId} " +
            "AND status = 'processing' AND lease_token = #{leaseToken} AND lease_until > clock_timestamp() FOR UPDATE")
    Long lockActiveDeliveryLease(@Param("tenantId") Long tenantId, @Param("eventId") String eventId,
                                  @Param("leaseToken") String leaseToken);

    @Insert("INSERT INTO ab_integration_receipt (tenant_id, event_id, consumer_code, status, " +
            "lease_token, received_at) VALUES (#{tenantId}, #{eventId}, #{consumerCode}, " +
            "'processing', #{leaseToken}, NOW()) ON CONFLICT (tenant_id, event_id, consumer_code) DO NOTHING")
    int claimReceipt(@Param("tenantId") Long tenantId, @Param("eventId") String eventId,
                     @Param("consumerCode") String consumerCode, @Param("leaseToken") String leaseToken);

    @Update("UPDATE ab_integration_receipt SET status = 'applied', applied_at = NOW() " +
            "WHERE tenant_id = #{tenantId} AND event_id = #{eventId} " +
            "AND consumer_code = #{consumerCode} AND lease_token = #{leaseToken}")
    int markReceiptApplied(@Param("tenantId") Long tenantId, @Param("eventId") String eventId,
                           @Param("consumerCode") String consumerCode, @Param("leaseToken") String leaseToken);

    @Insert("INSERT INTO ab_integration_dead_letter (outbox_id, tenant_id, event_id, event_type, " +
            "payload, error_detail, failed_at, status) SELECT id, tenant_id, event_id, event_type, " +
            "payload, last_error, NOW(), 'open' FROM ab_outbox WHERE id = #{id} AND status = 'failed' " +
            "ON CONFLICT (outbox_id) DO UPDATE SET error_detail = EXCLUDED.error_detail, " +
            "failed_at = EXCLUDED.failed_at, status = 'open'")
    int upsertDeadLetter(@Param("id") Long id);

    @Update("WITH replayed AS (UPDATE ab_integration_dead_letter SET status = 'replayed', " +
            "replayed_at = NOW(), replayed_by = #{replayedBy} WHERE outbox_id = #{id} AND status = 'open' " +
            "RETURNING outbox_id) UPDATE ab_outbox o SET status = 'pending', retry_count = 0, " +
            "next_retry_at = NOW(), last_error = NULL, lease_owner = NULL, lease_token = NULL, " +
            "lease_until = NULL, replay_count = replay_count + 1 FROM replayed r WHERE o.id = r.outbox_id")
    int replayDeadLetter(@Param("id") Long id, @Param("replayedBy") String replayedBy);

    @Update("WITH expired AS (SELECT id FROM ab_outbox WHERE status = 'processing' " +
            "AND lease_until < #{expiredBefore} ORDER BY lease_until, id FOR UPDATE SKIP LOCKED LIMIT 100) " +
            "UPDATE ab_outbox o SET status = CASE WHEN o.retry_count::bigint + 1 >= o.max_retries " +
            "THEN 'failed' ELSE 'pending' END, retry_count = LEAST(o.retry_count::bigint + 1, 2147483647)::integer, " +
            "next_retry_at = NOW(), lease_owner = NULL, lease_token = NULL, lease_until = NULL, " +
            "last_error = 'Recovered expired dispatcher lease' FROM expired e WHERE o.id = e.id")
    int recoverExpiredLeases(@Param("expiredBefore") Instant expiredBefore);

    @Insert("INSERT INTO ab_integration_dead_letter (outbox_id, tenant_id, event_id, event_type, payload, " +
            "error_detail, failed_at, status) SELECT id, tenant_id, event_id, event_type, payload, " +
            "last_error, NOW(), 'open' FROM ab_outbox o WHERE o.status = 'failed' " +
            "AND NOT EXISTS (SELECT 1 FROM ab_integration_dead_letter d WHERE d.outbox_id = o.id) " +
            "ON CONFLICT (outbox_id) DO NOTHING")
    int reconcileMissingDeadLetters();

    @Select("SELECT COUNT(*) FROM ab_integration_dead_letter WHERE status = 'open'")
    long countOpenDeadLetters();

    @Select("SELECT COUNT(*) FROM ab_integration_dead_letter WHERE status = 'quarantined'")
    long countQuarantinedDeadLetters();

    @Select("SELECT COUNT(*) FROM ab_outbox WHERE status = 'processing' AND lease_until < NOW()")
    long countExpiredLeases();

    @Select("SELECT COUNT(*) FROM ab_outbox WHERE status = 'pending' AND next_retry_at <= NOW()")
    long countReadyEvents();

    @Select("SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0) FROM ab_outbox " +
            "WHERE status IN ('pending', 'processing')")
    long oldestUndeliveredAgeSeconds();

    @Select("SELECT status, COUNT(*) as cnt FROM ab_outbox GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Delete("DELETE FROM ab_outbox WHERE status = 'delivered' AND delivered_at < #{before}")
    int cleanupDelivered(@Param("before") Instant before);
}

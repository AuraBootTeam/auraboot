package com.auraboot.framework.integration.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** Explicitly tenant-fenced persistence for the public reliable-integration operator API. */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ReliableIntegrationOperatorMapper {

    String FILTERS = "<if test='status != null and status != \"\"'> AND d.status = #{status}</if>"
            + "<if test='eventType != null and eventType != \"\"'> AND d.event_type = #{eventType}</if>"
            + "<if test='correlationId != null and correlationId != \"\"'>"
            + " AND o.correlation_id = #{correlationId}</if>";

    String PROJECTION = "SELECT d.event_id AS eventId, d.event_type AS eventType, "
            + "o.event_source AS eventSource, o.subject, o.correlation_id AS correlationId, "
            + "d.status, d.error_detail AS errorDetail, d.failed_at AS failedAt, "
            + "d.replayed_at AS replayedAt, d.replayed_by AS replayedBy, "
            + "o.replay_count AS replayCount, "
            + "(SELECT COUNT(*) FROM ab_integration_receipt r WHERE r.tenant_id = d.tenant_id "
            + "AND r.event_id = d.event_id) AS receiptCount, "
            + "(SELECT COUNT(*) FROM ab_integration_receipt r WHERE r.tenant_id = d.tenant_id "
            + "AND r.event_id = d.event_id AND r.status = 'applied') AS appliedReceiptCount "
            + "FROM ab_integration_dead_letter d JOIN ab_outbox o ON o.id = d.outbox_id ";

    @Select("<script>SELECT COUNT(*) FROM ab_integration_dead_letter d "
            + "JOIN ab_outbox o ON o.id = d.outbox_id WHERE d.tenant_id = #{tenantId} "
            + FILTERS + "</script>")
    long count(@Param("tenantId") long tenantId,
               @Param("status") String status,
               @Param("eventType") String eventType,
               @Param("correlationId") String correlationId);

    @Select("<script>" + PROJECTION + "WHERE d.tenant_id = #{tenantId} " + FILTERS
            + " ORDER BY d.failed_at DESC, d.event_id LIMIT #{limit} OFFSET #{offset}</script>")
    List<ReliableIntegrationOperatorRow> list(@Param("tenantId") long tenantId,
                                              @Param("status") String status,
                                              @Param("eventType") String eventType,
                                              @Param("correlationId") String correlationId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    @Select(PROJECTION + "WHERE d.tenant_id = #{tenantId} AND d.event_id = #{eventId}")
    ReliableIntegrationOperatorRow find(@Param("tenantId") long tenantId,
                                        @Param("eventId") String eventId);

    @Select("SELECT consumer_code AS consumerCode, status, received_at AS receivedAt, "
            + "applied_at AS appliedAt FROM ab_integration_receipt "
            + "WHERE tenant_id = #{tenantId} AND event_id = #{eventId} ORDER BY consumer_code")
    List<ReliableIntegrationReceiptRow> receipts(@Param("tenantId") long tenantId,
                                                 @Param("eventId") String eventId);

    @Select("SELECT pid AS recordPid, replay_attempt AS attempt, requested_by_pid AS requestedBy, "
            + "reason, correlation_id AS correlationId, requested_at AS requestedAt "
            + "FROM ab_integration_dead_letter_replay WHERE tenant_id = #{tenantId} "
            + "AND event_id = #{eventId} ORDER BY replay_attempt DESC")
    List<ReliableIntegrationReplayHistoryRow> replayHistory(@Param("tenantId") long tenantId,
                                                            @Param("eventId") String eventId);

    @Insert("WITH replayed AS (UPDATE ab_integration_dead_letter d SET status = 'replayed', "
            + "replayed_at = #{requestedAt}, replayed_by = #{requestedBy} FROM ab_outbox o "
            + "WHERE d.outbox_id = o.id AND d.tenant_id = #{tenantId} AND d.event_id = #{eventId} "
            + "AND d.status = 'open' AND o.replay_count = #{expectedReplayCount} "
            + "RETURNING d.outbox_id, d.tenant_id, d.event_id, o.correlation_id, "
            + "o.replay_count + 1 AS replay_attempt), reset AS (UPDATE ab_outbox o "
            + "SET status = 'pending', retry_count = 0, next_retry_at = #{requestedAt}, "
            + "last_error = NULL, lease_owner = NULL, lease_token = NULL, lease_until = NULL, "
            + "replay_count = r.replay_attempt FROM replayed r WHERE o.id = r.outbox_id "
            + "RETURNING r.outbox_id, r.tenant_id, r.event_id, r.correlation_id, r.replay_attempt) "
            + "INSERT INTO ab_integration_dead_letter_replay "
            + "(pid, outbox_id, tenant_id, event_id, correlation_id, replay_attempt, "
            + "requested_by_pid, reason, requested_at) SELECT #{recordPid}, outbox_id, tenant_id, "
            + "event_id, correlation_id, replay_attempt, #{requestedBy}, #{reason}, #{requestedAt} FROM reset")
    int replay(@Param("recordPid") String recordPid,
               @Param("tenantId") long tenantId,
               @Param("eventId") String eventId,
               @Param("requestedBy") String requestedBy,
               @Param("reason") String reason,
               @Param("expectedReplayCount") int expectedReplayCount,
               @Param("requestedAt") Instant requestedAt);
}

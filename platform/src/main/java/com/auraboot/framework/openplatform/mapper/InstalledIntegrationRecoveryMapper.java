package com.auraboot.framework.openplatform.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.*;
import java.time.Instant;

/** Explicit tenant predicates also protect calls outside a machine execution scope. */
@Mapper
@InterceptorIgnore(tenantLine="true")
public interface InstalledIntegrationRecoveryMapper {
    record Event(long tenantId, String eventId, String eventType, String eventSource, String subject,
                 Instant occurredAt, String correlationId, String causationId, String orderingKey,
                 long eventSequence, String schemaVersion, String payload, String headers,
                 String status, int replayCount, boolean openDeadLetter) {}
    record Audit(String pid, long tenantId, String installationPid, String sourceCode,
                 String externalEventId, String durableEventId, String requestPid,
                 String envelopeSha256, String bindingSha256, String previousCommandSha256,
                 String previousProviderSha256, String commandSha256, String providerSha256,
                 int replayCount, String requestedBy, String reason, Instant requestedAt) {}
    String EVENT_SQL="""
        SELECT o.tenant_id,o.event_id,o.event_type,o.event_source,o.subject,o.occurred_at,
               o.correlation_id,o.causation_id,o.ordering_key,o.event_sequence,o.schema_version,
               o.payload::text AS payload,o.headers::text AS headers,o.status,o.replay_count,
               EXISTS(SELECT 1 FROM ab_integration_dead_letter d
                      WHERE d.tenant_id=o.tenant_id AND d.event_id=o.event_id AND d.status='open') AS open_dead_letter
        FROM ab_outbox o WHERE o.tenant_id=#{tenant} AND o.event_id=#{event}
        """;
    @Select(EVENT_SQL) Event event(@Param("tenant") long tenant,@Param("event") String event);
    @Select(EVENT_SQL+" FOR UPDATE OF o") Event lockEvent(@Param("tenant") long tenant,@Param("event") String event);
    @Select("""
        SELECT pid,tenant_id,installation_pid,source_code,external_event_id,durable_event_id,request_pid,
               envelope_sha256,binding_sha256,previous_command_sha256,previous_provider_sha256,
               command_sha256,provider_sha256,replay_count,requested_by_pid AS requested_by,reason,requested_at
        FROM ab_installed_integration_recovery
        WHERE tenant_id=#{tenant} AND installation_pid=#{installation} AND source_code=#{source}
          AND external_event_id=#{event} AND previous_command_sha256=#{command}
          AND previous_provider_sha256=#{provider} AND replay_count=#{count}
        """)
    Audit audit(@Param("tenant") long tenant,@Param("installation") String installation,
                @Param("source") String source,@Param("event") String event,
                @Param("command") String command,@Param("provider") String provider,@Param("count") int count);
    @Insert("""
        INSERT INTO ab_installed_integration_recovery
        (pid,tenant_id,installation_pid,source_code,external_event_id,durable_event_id,request_pid,
         envelope_sha256,binding_sha256,previous_command_sha256,previous_provider_sha256,
         command_sha256,provider_sha256,replay_count,requested_by_pid,reason,requested_at)
        VALUES(#{a.pid},#{a.tenantId},#{a.installationPid},#{a.sourceCode},#{a.externalEventId},#{a.durableEventId},
          #{a.requestPid},#{a.envelopeSha256},#{a.bindingSha256},#{a.previousCommandSha256},#{a.previousProviderSha256},
          #{a.commandSha256},#{a.providerSha256},#{a.replayCount},#{a.requestedBy},#{a.reason},#{a.requestedAt})
        """) int insert(@Param("a") Audit audit);
}

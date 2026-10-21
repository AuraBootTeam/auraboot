package com.auraboot.framework.openplatform.mapper;

import org.apache.ibatis.annotations.*;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface InstalledIntegrationReceiptMapper {
    record Receipt(long tenantId, String installationPid, String sourceCode, String externalEventId,
                   String durableEventId, String envelopeSha256, String bindingSha256,
                   String commandSha256, String providerSha256, String requestPid, String state) {}

    @Insert("""
        INSERT INTO ab_installed_integration_receipt
          (tenant_id,installation_pid,source_code,external_event_id,durable_event_id,envelope_sha256,
           binding_sha256,command_sha256,provider_sha256,state)
        VALUES (#{r.tenantId},#{r.installationPid},#{r.sourceCode},#{r.externalEventId},#{r.durableEventId},
                #{r.envelopeSha256},#{r.bindingSha256},#{r.commandSha256},#{r.providerSha256},'accepting')
        ON CONFLICT (tenant_id,installation_pid,source_code,external_event_id) DO NOTHING
        """)
    int claim(@Param("r") Receipt receipt);

    @Select("""
        SELECT tenant_id,installation_pid,source_code,external_event_id,durable_event_id,envelope_sha256,
               binding_sha256,command_sha256,provider_sha256,request_pid,state
        FROM ab_installed_integration_receipt
        WHERE tenant_id=#{tenant} AND installation_pid=#{installation} AND source_code=#{source}
          AND external_event_id=#{event} FOR UPDATE
        """)
    Receipt find(@Param("tenant") long tenant, @Param("installation") String installation,
                 @Param("source") String source, @Param("event") String event);

    @Select("""
        SELECT status FROM ab_outbox WHERE tenant_id=#{tenant} AND event_id=#{event}
          AND schema_version IS NOT NULL
        """)
    String deliveryStatus(@Param("tenant") long tenant, @Param("event") String durableEventId);

    @Update("""
        UPDATE ab_installed_integration_receipt SET request_pid=#{pid},state='accepted',updated_at=NOW()
        WHERE tenant_id=#{tenant} AND installation_pid=#{installation} AND source_code=#{source}
          AND external_event_id=#{event} AND state='accepting'
        """)
    int accepted(@Param("tenant") long tenant, @Param("installation") String installation,
                 @Param("source") String source, @Param("event") String event, @Param("pid") String pid);

    @Update("""
        UPDATE ab_installed_integration_receipt SET state='completed',updated_at=NOW()
        WHERE tenant_id=#{tenant} AND installation_pid=#{installation} AND source_code=#{source}
          AND external_event_id=#{event} AND request_pid=#{pid} AND state='accepted'
        """)
    int completed(@Param("tenant") long tenant, @Param("installation") String installation,
                 @Param("source") String source, @Param("event") String event, @Param("pid") String pid);
}

package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** Read-only operational projections; no queue or business-state mutation. */
@Mapper
public interface AnnouncementOperationsMapper {
    @Select("""
        SELECT o.announcement_pid,o.result::text AS result,o.created_at
        FROM ab_announcement_operation o
        JOIN ab_tenant_member m ON m.tenant_id=o.tenant_id AND m.user_id=o.actor_user_id
            AND m.id=#{member} AND m.status='active' AND m.deleted_flag=FALSE AND m.created_at<=o.created_at
        WHERE o.tenant_id=#{tenant} AND o.actor_user_id=#{actor} AND o.command_code=#{command} AND o.operation_id=#{operation}
        """)
    Map<String,Object> operation(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("member") Long member,
        @Param("command") String command,@Param("operation") String operation);

    @Select("SELECT count(*) FROM ab_announcement_delivery WHERE tenant_id=#{tenant} AND version_pid=#{version} AND user_pid IN (SELECT jsonb_array_elements_text(CAST(#{users} AS jsonb)))")
    long deliveryCount(@Param("tenant") Long tenant,@Param("version") String version,@Param("users") String users);

    @Select("""
        SELECT d.batch_pid,d.user_pid,r.display_name_snapshot,d.channel,d.kind,d.status,d.created_at,d.delivered_at,d.error_code
        FROM ab_announcement_delivery d JOIN ab_announcement_recipient r
            ON r.tenant_id=d.tenant_id AND r.version_pid=d.version_pid AND r.user_pid=d.user_pid
        WHERE d.tenant_id=#{tenant} AND d.version_pid=#{version}
            AND d.user_pid IN (SELECT jsonb_array_elements_text(CAST(#{users} AS jsonb)))
        ORDER BY d.created_at DESC,d.batch_pid,d.user_pid,d.channel LIMIT #{size} OFFSET #{offset}
        """)
    List<Map<String,Object>> deliveries(@Param("tenant") Long tenant,@Param("version") String version,
        @Param("users") String users,@Param("size") int size,@Param("offset") long offset);
}

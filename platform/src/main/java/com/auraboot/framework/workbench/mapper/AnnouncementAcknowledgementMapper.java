package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.Map;

/** Exact tenant/actor queries for the command-scoped recipient proof bridge. */
@Mapper
public interface AnnouncementAcknowledgementMapper {
    @Select("""
            SELECT m.id AS member_id,m.pid AS member_pid,u.pid AS user_pid
            FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id
            WHERE m.tenant_id=#{tenant} AND m.user_id=#{actor} AND u.pid=#{user}
              AND m.status='active' AND m.deleted_flag=FALSE
              AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
            FOR SHARE OF m,u
            """)
    Map<String,Object> lockMember(@Param("tenant") Long tenant, @Param("actor") Long actor, @Param("user") String user);

    @Select("""
            SELECT v.pid AS version_pid,v.announcement_pid,v.snapshot_hash,v.snapshot::text AS snapshot,
                   c.publication_state,c.expires_at
            FROM ab_announcement_version v
            JOIN ab_announcement_version_control c ON c.tenant_id=v.tenant_id AND c.version_pid=v.pid
            WHERE v.tenant_id=#{tenant} AND v.pid=#{version}
            """)
    Map<String,Object> version(@Param("tenant") Long tenant, @Param("version") String version);

    @Select("""
            SELECT r.first_read_at,r.acknowledged_at,r.acknowledged_hash
            FROM ab_announcement_recipient r
            JOIN ab_announcement_recipient_snapshot s
              ON s.tenant_id=r.tenant_id AND s.version_pid=r.version_pid AND s.sealed=TRUE
            WHERE r.tenant_id=#{tenant} AND r.version_pid=#{version}
              AND r.user_pid=#{user} AND r.member_pid=#{member}
            """)
    Map<String,Object> recipient(@Param("tenant") Long tenant, @Param("version") String version,
            @Param("user") String user, @Param("member") String member);

    @Select("SELECT clock_timestamp()")
    Instant databaseNow();
}

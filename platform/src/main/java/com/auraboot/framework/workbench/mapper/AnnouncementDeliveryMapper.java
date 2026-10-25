package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** Fixed recipient facts, never watchers or role re-resolution. */
@Mapper
public interface AnnouncementDeliveryMapper {
    @Select("SELECT a.pid FROM ab_announcement a JOIN ab_announcement_version v ON v.tenant_id=a.tenant_id AND v.announcement_pid=a.pid WHERE a.tenant_id=#{tenant} AND v.pid=#{version} FOR UPDATE OF a")
    String lockRoot(@Param("tenant") Long tenant,@Param("version") String version);

    @Select("""
        SELECT r.user_pid,r.member_pid,v.pid AS version_pid,v.snapshot::text AS snapshot,c.published_at,c.expires_at,
            c.ack_due_at,v.announcement_pid,u.id AS user_id
        FROM ab_announcement_recipient r
        JOIN ab_announcement_recipient_snapshot s ON s.tenant_id=r.tenant_id AND s.version_pid=r.version_pid AND s.sealed=TRUE
        JOIN ab_announcement_version v ON v.tenant_id=r.tenant_id AND v.pid=r.version_pid
        JOIN ab_announcement_version_control c ON c.tenant_id=r.tenant_id AND c.version_pid=r.version_pid
        JOIN ab_announcement a ON a.tenant_id=r.tenant_id AND a.active_version_pid=r.version_pid AND a.deleted_flag=FALSE
        JOIN ab_tenant_member m ON m.tenant_id=r.tenant_id AND m.pid=r.member_pid AND m.status='active' AND m.deleted_flag=FALSE
        JOIN ab_user u ON u.id=m.user_id AND u.pid=r.user_pid AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
        WHERE r.tenant_id=#{tenant} AND r.version_pid=#{version} AND c.publication_state='published'
            AND (c.expires_at IS NULL OR c.expires_at>CURRENT_TIMESTAMP) AND r.user_pid>#{after}
        ORDER BY r.user_pid LIMIT 500
        """)
    List<Map<String,Object>> recipients(@Param("tenant") Long tenant,@Param("version") String version,@Param("after") String after);

    @Insert("""
        INSERT INTO ab_announcement_delivery(tenant_id,batch_pid,version_pid,user_pid,channel,kind)
        VALUES(#{tenant},#{batch},#{version},#{user},'in_app',#{kind}) ON CONFLICT DO NOTHING
        """)
    int intent(@Param("tenant") Long tenant,@Param("batch") String batch,@Param("version") String version,@Param("user") String user,@Param("kind") String kind);

    @Select("""
        SELECT d.*,v.snapshot::text AS snapshot,c.published_at,c.expires_at,a.pid AS announcement_pid,
            r.member_pid,r.first_read_at,r.acknowledged_at,u.id AS user_id
        FROM ab_announcement_delivery d JOIN ab_announcement_version v ON v.tenant_id=d.tenant_id AND v.pid=d.version_pid
        JOIN ab_announcement_version_control c ON c.tenant_id=d.tenant_id AND c.version_pid=d.version_pid
        JOIN ab_announcement_recipient r ON r.tenant_id=d.tenant_id AND r.version_pid=d.version_pid AND r.user_pid=d.user_pid
        JOIN ab_announcement_recipient_snapshot s ON s.tenant_id=r.tenant_id AND s.version_pid=r.version_pid AND s.sealed=TRUE
        JOIN ab_announcement a ON a.tenant_id=d.tenant_id AND a.active_version_pid=d.version_pid AND a.deleted_flag=FALSE
        JOIN ab_tenant_member m ON m.tenant_id=d.tenant_id AND m.pid=r.member_pid AND m.status='active' AND m.deleted_flag=FALSE
        JOIN ab_user u ON u.id=m.user_id AND u.pid=d.user_pid AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
        WHERE d.tenant_id=#{tenant} AND d.batch_pid=#{batch} AND d.user_pid=#{user} AND d.channel='in_app'
            AND c.publication_state='published' AND (c.expires_at IS NULL OR c.expires_at>CURRENT_TIMESTAMP)
        """)
    Map<String,Object> plan(@Param("tenant") Long tenant,@Param("batch") String batch,@Param("user") String user);

    @Update("""
        UPDATE ab_announcement_delivery SET status=#{state},delivered_at=CURRENT_TIMESTAMP,error_code=#{reason}
        WHERE tenant_id=#{tenant} AND batch_pid=#{batch} AND user_pid=#{user} AND channel='in_app' AND status='queued'
        """)
    int complete(@Param("tenant") Long tenant,@Param("batch") String batch,@Param("user") String user,@Param("state") String state,@Param("reason") String reason);

    @Select("SELECT status FROM ab_announcement_release WHERE tenant_id=#{tenant} AND pid=#{batch} AND version_pid=#{version}")
    String releaseState(@Param("tenant") Long tenant,@Param("batch") String batch,@Param("version") String version);
}

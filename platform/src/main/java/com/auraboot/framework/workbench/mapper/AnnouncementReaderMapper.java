package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.util.*;

/** Recipient eligibility is tied to the sealed membership incarnation and exact active version. */
@Mapper
public interface AnnouncementReaderMapper {
    String FROM="""
        FROM ab_announcement_recipient r
        JOIN ab_announcement_recipient_snapshot s ON s.tenant_id=r.tenant_id AND s.version_pid=r.version_pid AND s.sealed=TRUE
        JOIN ab_announcement_version v ON v.tenant_id=r.tenant_id AND v.pid=r.version_pid
        JOIN ab_announcement_version_control c ON c.tenant_id=r.tenant_id AND c.version_pid=r.version_pid
        JOIN ab_announcement a ON a.tenant_id=r.tenant_id AND a.active_version_pid=r.version_pid AND a.deleted_flag=FALSE
        JOIN ab_tenant_member m ON m.tenant_id=r.tenant_id AND m.pid=r.member_pid AND m.user_id=#{actor} AND m.status='active' AND m.deleted_flag=FALSE
        JOIN ab_user u ON u.id=m.user_id AND u.pid=r.user_pid AND u.pid=#{user} AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
        LEFT JOIN ab_user p ON p.id=a.published_by
        WHERE r.tenant_id=#{tenant} AND c.publication_state='published' AND (c.expires_at IS NULL OR c.expires_at>clock_timestamp())
        """;
    String FILTER="""
        <if test='query != null and query != ""'> AND v.snapshot->>'title' ILIKE CONCAT('%',#{query},'%') </if>
        <if test='filter == "unread"'> AND r.first_read_at IS NULL </if>
        <if test='filter == "pending_ack"'> AND r.acknowledged_at IS NULL AND v.snapshot->'policy'->>'acknowledgementRequired'='true' </if>
        """;
    String COLUMNS="""
        SELECT v.pid,v.announcement_pid,v.version_no,v.snapshot_hash,v.snapshot::text AS snapshot,s.origin,
            c.published_at,c.expires_at,c.ack_due_at,r.first_read_at,r.acknowledged_at,r.acknowledged_hash,
            a.row_version,a.pinned,COALESCE(NULLIF(p.nick_name,''),p.user_name) AS publisher_name
        """;
    @Select("<script>"+COLUMNS+FROM+FILTER+" ORDER BY a.pinned DESC,c.published_at DESC,v.pid LIMIT #{size} OFFSET #{offset}</script>")
    List<Map<String,Object>> list(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("user") String user,
        @Param("query") String query,@Param("filter") String filter,@Param("size") int size,@Param("offset") int offset);
    @Select("<script>SELECT count(*) "+FROM+FILTER+"</script>")
    long count(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("user") String user,@Param("query") String query,@Param("filter") String filter);
    @Select(COLUMNS+FROM+" AND v.pid=#{version}")
    Map<String,Object> visible(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("user") String user,@Param("version") String version);
    @Update("""
        UPDATE ab_announcement_recipient SET first_read_at=COALESCE(first_read_at,CURRENT_TIMESTAMP)
        WHERE tenant_id=#{tenant} AND version_pid=#{version} AND user_pid=#{user}
        """)
    int read(@Param("tenant") Long tenant,@Param("version") String version,@Param("user") String user);
    @Update("""
        UPDATE ab_announcement_recipient SET acknowledged_at=COALESCE(acknowledged_at,CURRENT_TIMESTAMP),acknowledged_hash=#{hash}
        WHERE tenant_id=#{tenant} AND version_pid=#{version} AND user_pid=#{user} AND first_read_at IS NOT NULL
            AND (acknowledged_hash IS NULL OR acknowledged_hash=#{hash})
        """)
    int acknowledge(@Param("tenant") Long tenant,@Param("version") String version,@Param("user") String user,@Param("hash") String hash);
    @Update("""
        UPDATE ab_inbox_item SET status='acted',action_taken='acknowledged',acted_at=COALESCE(acted_at,CURRENT_TIMESTAMP),
            is_read=TRUE,read_at=COALESCE(read_at,CURRENT_TIMESTAMP)
        WHERE tenant_id=#{tenant} AND user_id=#{actor} AND source_type='announcement' AND source_id=#{version}
            AND item_type='announcement_ack' AND status='pending'
        """)
    int closeAcknowledgement(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("version") String version);
}

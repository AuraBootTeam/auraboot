package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.*;

/** Explicit JSONB casts form the private capture contract; public rows are a separate allowlisted payload. */
@Mapper
public interface AnnouncementStatisticsMapper {
    @Select("SELECT CURRENT_TIMESTAMP")
    Instant databaseNow();

    @Select("""
        SELECT m.pid FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id
        WHERE m.tenant_id=#{tenant} AND m.id=#{member} AND m.user_id=#{actor} AND u.pid=#{user}
            AND m.status='active' AND m.deleted_flag=FALSE AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
        """)
    String currentMember(@Param("tenant") Long tenant,@Param("member") Long member,@Param("actor") Long actor,@Param("user") String user);

    @Select("""
        SELECT v.pid AS version_pid,v.announcement_pid,v.version_no,v.snapshot::text AS snapshot,c.published_at,c.ack_due_at,c.expires_at,
            s.origin,s.recipient_count,(a.active_version_pid=v.pid AND c.publication_state='published') AS version_active
        FROM ab_announcement_version v JOIN ab_announcement_version_control c ON c.tenant_id=v.tenant_id AND c.version_pid=v.pid
        JOIN ab_announcement_recipient_snapshot s ON s.tenant_id=v.tenant_id AND s.version_pid=v.pid AND s.sealed=TRUE
        JOIN ab_announcement a ON a.tenant_id=v.tenant_id AND a.pid=v.announcement_pid AND a.deleted_flag=FALSE
        WHERE v.tenant_id=#{tenant} AND v.pid=#{version}
        """)
    Map<String,Object> version(@Param("tenant") Long tenant,@Param("version") String version);

    @Select("""
        SELECT r.user_pid,r.member_pid,r.display_name_snapshot,r.organization_snapshot::text AS organization_snapshot,
            r.first_read_at,r.acknowledged_at,
            (m.pid IS NOT NULL AND m.status='active' AND m.deleted_flag=FALSE AND u.is_enabled=TRUE AND u.deleted_flag=FALSE) AS member_eligible,
            COALESCE((SELECT d.status FROM ab_announcement_delivery d WHERE d.tenant_id=r.tenant_id AND d.version_pid=r.version_pid
                AND d.user_pid=r.user_pid AND d.kind='publication' AND d.channel='in_app' ORDER BY d.created_at DESC,d.batch_pid LIMIT 1),'not_generated') AS notification_state
        FROM ab_announcement_recipient r
        LEFT JOIN ab_tenant_member m ON m.tenant_id=r.tenant_id AND m.pid=r.member_pid
        LEFT JOIN ab_user u ON u.id=m.user_id AND u.pid=r.user_pid
        WHERE r.tenant_id=#{tenant} AND r.version_pid=#{version} ORDER BY r.user_pid
        """)
    List<Map<String,Object>> facts(@Param("tenant") Long tenant,@Param("version") String version);

    @Insert("""
        INSERT INTO ab_announcement_statistics_snapshot(tenant_id,pid,version_pid,owner_user_id,owner_member_pid,snapshot_schema,
            as_of,view_expires_at,retained_until,metadata,summary,filters,row_count,filtered_count)
        VALUES(#{tenant},#{pid},#{version},#{actor},#{member},'announcement.statistics.v1',CAST(#{asOf} AS timestamptz),CAST(#{asOf} AS timestamptz)+INTERVAL '30 minutes',
            CAST(#{asOf} AS timestamptz)+INTERVAL '25 hours',#{metadata}::jsonb,#{summary}::jsonb,#{filters}::jsonb,#{count},#{filtered})
        """)
    int insertSnapshot(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version,@Param("actor") Long actor,
        @Param("member") String member,@Param("asOf") Instant asOf,@Param("metadata") String metadata,@Param("summary") String summary,
        @Param("filters") String filters,@Param("count") long count,@Param("filtered") long filtered);

    record StoredRow(String userPid,String memberPid,String organization,String proof,String view,boolean matches) {}
    @Insert("""
        <script>INSERT INTO ab_announcement_statistics_row(tenant_id,snapshot_pid,user_pid,member_pid,organization_snapshot,display_proof,view_payload,matches_filters)
        VALUES <foreach collection='rows' item='row' separator=','>
        (#{tenant},#{snapshot},#{row.userPid},#{row.memberPid},#{row.organization}::jsonb,#{row.proof}::jsonb,#{row.view}::jsonb,#{row.matches})
        </foreach></script>
        """)
    int insertRows(@Param("tenant") Long tenant,@Param("snapshot") String snapshot,@Param("rows") List<StoredRow> rows);

    @Update("UPDATE ab_announcement_statistics_snapshot SET sealed=TRUE WHERE tenant_id=#{tenant} AND pid=#{pid} AND sealed=FALSE")
    int seal(@Param("tenant") Long tenant,@Param("pid") String pid);

    @Select("""
        SELECT version_pid,owner_user_id,owner_member_pid,snapshot_schema,as_of,view_expires_at,retained_until,
            metadata::text AS metadata,summary::text AS summary,filters::text AS filters,row_count,filtered_count
        FROM ab_announcement_statistics_snapshot WHERE tenant_id=#{tenant} AND pid=#{pid} AND sealed=TRUE
        """)
    Map<String,Object> snapshot(@Param("tenant") Long tenant,@Param("pid") String pid);

    @Select("""
        SELECT user_pid,member_pid,organization_snapshot::text AS organization_snapshot,display_proof::text AS display_proof
        FROM ab_announcement_statistics_row WHERE tenant_id=#{tenant} AND snapshot_pid=#{pid} ORDER BY user_pid
        """)
    List<Map<String,Object>> authorizationRows(@Param("tenant") Long tenant,@Param("pid") String pid);

    @Select("""
        SELECT view_payload::text FROM ab_announcement_statistics_row
        WHERE tenant_id=#{tenant} AND snapshot_pid=#{pid} AND matches_filters=TRUE ORDER BY user_pid LIMIT #{size} OFFSET #{offset}
        """)
    List<String> page(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("size") int size,@Param("offset") int offset);

    @Delete("DELETE FROM ab_announcement_statistics_snapshot WHERE retained_until<=clock_timestamp()")
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine="true")
    int deleteExpired();
}

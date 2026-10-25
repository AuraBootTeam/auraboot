package com.auraboot.framework.workbench.mapper;

import com.auraboot.framework.workbench.entity.Announcement;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Tenant-explicit persistence for the announcement aggregate's sole writer. */
@Mapper
public interface AnnouncementLifecycleMapper {
    @Select("""
        SELECT COALESCE(NULLIF(u.nick_name,''),NULLIF(u.user_name,'')) FROM ab_announcement a
        LEFT JOIN ab_user u ON u.id=a.published_by AND u.deleted_flag=FALSE
            AND EXISTS(SELECT 1 FROM ab_tenant_member m WHERE m.tenant_id=a.tenant_id AND m.user_id=u.id)
        WHERE a.tenant_id=#{tenant} AND a.pid=#{pid} AND a.deleted_flag=FALSE
        """)
    String publisherName(@Param("tenant") Long tenant,@Param("pid") String pid);

    @Select("SELECT EXISTS(SELECT 1 FROM ab_file WHERE tenant_id=#{tenant} AND pid=#{pid} AND status='success' AND deleted_flag=FALSE)")
    boolean finalizedFileInTenant(@Param("tenant") Long tenant, @Param("pid") String pid);

    @Select("SELECT draft_settings::text FROM ab_announcement WHERE tenant_id=#{tenant} AND pid=#{pid}")
    String draftSettings(@Param("tenant") Long tenant, @Param("pid") String pid);

    @Update("UPDATE ab_announcement SET draft_settings=CAST(#{settings} AS jsonb) WHERE tenant_id=#{tenant} AND pid=#{pid} AND status='draft' AND deleted_flag=FALSE")
    int saveSettings(@Param("tenant") Long tenant, @Param("pid") String pid, @Param("settings") String settings);

    @Select(
            "SELECT EXISTS(SELECT 1 FROM ab_user WHERE id=#{actor} AND pid=#{userPid} AND"
                + " is_enabled=TRUE AND deleted_flag=FALSE)")
    boolean isEnabledActor(@Param("actor") Long actor, @Param("userPid") String userPid);

    @Select("SELECT * FROM ab_announcement WHERE tenant_id=#{tenant} AND pid=#{pid} FOR UPDATE")
    Announcement lock(@Param("tenant") Long tenant, @Param("pid") String pid);

    @Select("SELECT * FROM ab_announcement WHERE tenant_id=#{tenant} AND pid=#{pid}")
    Announcement readTarget(@Param("tenant") Long tenant, @Param("pid") String pid);

    @Update("""
        UPDATE ab_announcement SET pinned=#{pinned},row_version=row_version+1,lifecycle_revision=lifecycle_revision+1,
            updated_by=#{actor},updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND row_version=#{expected} AND status='published'
            AND active_version_pid IS NOT NULL AND deleted_flag=FALSE
            AND EXISTS(SELECT 1 FROM ab_announcement_version_control c WHERE c.tenant_id=#{tenant}
                AND c.version_pid=ab_announcement.active_version_pid AND c.publication_state='published'
                AND (c.expires_at IS NULL OR c.expires_at>clock_timestamp()))
        """)
    int setPinned(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("pid") String pid,
        @Param("expected") int expected,@Param("pinned") boolean pinned);

    @Insert(
            """
        INSERT INTO ab_announcement(pid,tenant_id,owner_user_id,title,content,announcement_priority,priority,
            pinned,expires_at,status,created_by,updated_by)
        VALUES(#{pid},#{tenant},#{actor},#{title},#{content},#{importance},#{importance},
            #{pinned},#{expiresAt},'draft',#{actor},#{actor})
        """)
    int insertDraft(
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("pid") String pid,
            @Param("title") String title,
            @Param("content") String content,
            @Param("importance") String importance,
            @Param("pinned") boolean pinned,
            @Param("expiresAt") Instant expiresAt);

    @Update(
            """
        UPDATE ab_announcement SET title=#{title},content=#{content},announcement_priority=#{importance},priority=#{importance},
            pinned=#{pinned},expires_at=#{expiresAt},row_version=row_version+1,editor_revision=editor_revision+1,
            updated_by=#{actor},updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND row_version=#{expected} AND status='draft' AND deleted_flag=FALSE
        """)
    int saveDraft(
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("pid") String pid,
            @Param("expected") int expected,
            @Param("title") String title,
            @Param("content") String content,
            @Param("importance") String importance,
            @Param("pinned") boolean pinned,
            @Param("expiresAt") Instant expiresAt);

    @Update(
            """
        UPDATE ab_announcement SET deleted_flag=TRUE,row_version=row_version+1,lifecycle_revision=lifecycle_revision+1,
            updated_by=#{actor},updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND row_version=#{expected} AND status='draft'
            AND deleted_flag=FALSE AND current_version_pid IS NULL
        """)
    int deleteDraft(
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("pid") String pid,
            @Param("expected") int expected);

    @Select(
            "SELECT snapshot::text FROM ab_announcement_version WHERE tenant_id=#{tenant} AND"
                + " pid=#{version}")
    String versionSnapshot(@Param("tenant") Long tenant, @Param("version") String version);

    @Update(
            """
        UPDATE ab_announcement SET status='draft',active_version_pid=NULL,title=#{title},content=#{content},
            row_version=row_version+1,editor_revision=editor_revision+1,lifecycle_revision=lifecycle_revision+1,
            updated_by=#{actor},updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND row_version=#{expected} AND deleted_flag=FALSE
        """)
    int revise(
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("pid") String pid,
            @Param("expected") int expected,
            @Param("title") String title,
            @Param("content") String content);

    @Update(
            """
        UPDATE ab_announcement SET status='withdrawn',active_version_pid=NULL,row_version=row_version+1,
            lifecycle_revision=lifecycle_revision+1,updated_by=#{actor},updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND row_version=#{expected} AND status='published' AND deleted_flag=FALSE
        """)
    int withdraw(
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("pid") String pid,
            @Param("expected") int expected);

    @Update(
            """
        UPDATE ab_announcement_version_control SET publication_state='withdrawn',publication_revision=publication_revision+1
        WHERE tenant_id=#{tenant} AND version_pid=#{version} AND publication_state='published'
        """)
    int withdrawVersion(@Param("tenant") Long tenant, @Param("version") String version);

    @Select("SELECT a.tenant_id,a.pid,a.active_version_pid AS version FROM ab_announcement a JOIN ab_announcement_version_control c ON c.tenant_id=a.tenant_id AND c.version_pid=a.active_version_pid WHERE a.status='published' AND a.deleted_flag=FALSE AND c.publication_state='published' AND c.expires_at<=clock_timestamp() ORDER BY c.expires_at,a.pid LIMIT 100")
    List<Map<String,Object>> dueExpiry();
    @Update("UPDATE ab_announcement SET status='expired',active_version_pid=NULL,row_version=row_version+1,lifecycle_revision=lifecycle_revision+1,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=#{tenant} AND pid=#{pid} AND active_version_pid=#{version} AND status='published'")
    int expire(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version);
    @Update("UPDATE ab_announcement_version_control SET publication_state='expired',publication_revision=publication_revision+1 WHERE tenant_id=#{tenant} AND version_pid=#{version} AND publication_state='published' AND expires_at<=clock_timestamp()")
    int expireVersion(@Param("tenant") Long tenant,@Param("version") String version);

    @Update("UPDATE ab_inbox_item SET status='closed',acted_at=CURRENT_TIMESTAMP,action_taken='announcement_unavailable' WHERE tenant_id=#{tenant} AND source_type='announcement' AND source_id=#{version} AND item_type='announcement_ack' AND status='pending'")
    int closeRecipientTasks(@Param("tenant") Long tenant,@Param("version") String version);
    @Update("UPDATE ab_announcement_delivery SET status='canceled',error_code='VERSION_INACTIVE' WHERE tenant_id=#{tenant} AND version_pid=#{version} AND status='queued'")
    int cancelRecipientDeliveries(@Param("tenant") Long tenant,@Param("version") String version);

    @Select(
            "SELECT count(*) FROM ab_announcement_version WHERE tenant_id=#{tenant} AND"
                + " announcement_pid=#{pid}")
    int versionCount(@Param("tenant") Long tenant, @Param("pid") String pid);

    @Insert(
            """
        INSERT INTO ab_announcement_operation(tenant_id,actor_user_id,command_code,operation_id,request_hash)
        VALUES(#{tenant},#{actor},#{command},#{operation},#{hash}) ON CONFLICT DO NOTHING
        """)
    int claim(
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("command") String command,
            @Param("operation") String operation,
            @Param("hash") String hash);

    @Select(
            """
        SELECT request_hash,result::text AS result,announcement_pid FROM ab_announcement_operation
        WHERE tenant_id=#{tenant} AND actor_user_id=#{actor} AND command_code=#{command} AND operation_id=#{operation}
        FOR UPDATE
        """)
    Map<String, Object> operation(
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("command") String command,
            @Param("operation") String operation);

    @Update(
            """
        UPDATE ab_announcement_operation SET announcement_pid=#{pid},result=CAST(#{result} AS jsonb)
        WHERE tenant_id=#{tenant} AND actor_user_id=#{actor} AND command_code=#{command} AND operation_id=#{operation}
        """)
    int finish(
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("command") String command,
            @Param("operation") String operation,
            @Param("pid") String pid,
            @Param("result") String result);

    @Insert(
            """
        INSERT INTO ab_announcement_audit(pid,tenant_id,actor_user_id,announcement_pid,version_pid,
            command_code,operation_id,previous_revision,resulting_revision)
        VALUES(#{auditPid},#{tenant},#{actor},#{pid},#{version},#{command},#{operation},#{before},#{after})
        """)
    int audit(
            @Param("auditPid") String auditPid,
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("pid") String pid,
            @Param("version") String version,
            @Param("command") String command,
            @Param("operation") String operation,
            @Param("before") Integer before,
            @Param("after") int after);

    @Select(
            """
        SELECT a.* FROM ab_announcement a
        JOIN ab_announcement_recipient r ON r.tenant_id=a.tenant_id AND r.version_pid=a.active_version_pid
        JOIN ab_announcement_recipient_snapshot s ON s.tenant_id=r.tenant_id AND s.version_pid=r.version_pid AND s.sealed=TRUE
        JOIN ab_tenant_member m ON m.tenant_id=a.tenant_id AND m.pid=r.member_pid AND m.user_id=#{actor}
        JOIN ab_user u ON u.id=m.user_id AND u.pid=r.user_pid AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
        WHERE a.tenant_id=#{tenant} AND r.user_pid=#{userPid} AND lower(m.status)='active' AND m.deleted_flag=FALSE
            AND a.status='published' AND a.deleted_flag=FALSE AND (a.expires_at IS NULL OR a.expires_at>CURRENT_TIMESTAMP)
        ORDER BY a.pinned DESC,a.published_at DESC,a.pid LIMIT #{limit}
        """)
    List<Announcement> listVisible(
            @Param("tenant") Long tenant,
            @Param("actor") Long actor,
            @Param("userPid") String userPid,
            @Param("limit") int limit);
}

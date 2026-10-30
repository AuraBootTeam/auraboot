package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.*;

/** Durable release state, fenced by an explicitly trusted tenant and version. */
@Mapper
public interface AnnouncementReleaseMapper {
    @Select("""
        SELECT m.id AS member_id,m.pid AS member_pid,u.pid AS user_pid,u.user_name AS username
        FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id
        WHERE m.tenant_id=#{tenant} AND m.user_id=#{actor} AND m.status='active' AND m.deleted_flag=FALSE
          AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
        """)
    Map<String,Object> actor(@Param("tenant") Long tenant,@Param("actor") Long actor);

    @Select("""
        SELECT ur.role_id FROM ab_user_role ur JOIN ab_role r ON r.id=ur.role_id
        WHERE ur.tenant_id=#{tenant} AND ur.member_id=#{member} AND ur.status='active' AND ur.deleted_flag=FALSE
          AND (ur.effective_date IS NULL OR ur.effective_date<=CURRENT_DATE)
          AND (ur.expiry_date IS NULL OR ur.expiry_date>=CURRENT_DATE)
          AND r.deleted_flag=FALSE AND r.status='active' AND (r.tenant_id=#{tenant} OR r.tenant_id IS NULL)
        """)
    Set<Long> actorRoles(@Param("tenant") Long tenant,@Param("member") Long member);

    @Select("""
        SELECT jsonb_build_object(
          'roles',(SELECT jsonb_agg(to_jsonb(ur) ORDER BY ur.role_id) FROM ab_user_role ur
             WHERE ur.tenant_id=#{tenant} AND ur.member_id=#{member}),
          'scopes',(SELECT jsonb_agg(to_jsonb(s) ORDER BY s.id) FROM ab_role_data_scope s
             JOIN ab_user_role ur ON ur.role_id=s.role_id AND ur.tenant_id=s.tenant_id
             WHERE s.tenant_id=#{tenant} AND ur.member_id=#{member} AND s.resource_code='ab_announcement'),
          'grants',(SELECT jsonb_agg(to_jsonb(rp) ORDER BY rp.id) FROM ab_role_permission rp
             JOIN ab_permission p ON p.id=rp.permission_id JOIN ab_user_role ur ON ur.role_id=rp.role_id AND ur.tenant_id=rp.tenant_id
             WHERE rp.tenant_id=#{tenant} AND ur.member_id=#{member} AND p.code LIKE 'announcement.announcement.%')
        )::text
        """)
    String authorizationEvidence(@Param("tenant") Long tenant,@Param("member") Long member);

    @Select("SELECT preference_value#>>'{}' FROM ab_tenant_preference WHERE tenant_id=#{tenant} AND preference_key='ui.timezone'")
    String timezone(@Param("tenant") Long tenant);

    @Insert("""
        INSERT INTO ab_announcement_version(tenant_id,pid,announcement_pid,version_no,source_editor_revision,
            snapshot_schema,snapshot_hash,snapshot,created_by)
        SELECT #{tenant},#{version},#{pid},COALESCE(max(version_no),0)+1,#{revision},'announcement.v1',#{hash},CAST(#{snapshot} AS jsonb),#{actor}
        FROM ab_announcement_version WHERE tenant_id=#{tenant} AND announcement_pid=#{pid}
        """)
    int freeze(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version,
        @Param("revision") int revision,@Param("hash") String hash,@Param("snapshot") String snapshot,@Param("actor") Long actor);

    @Insert("""
        INSERT INTO ab_announcement_version_control(tenant_id,version_pid,authorization_basis,approval_state,
            publication_state,release_attempt_pid,planned_publish_at,expires_at)
        VALUES(#{tenant},#{version},'exempt','not_submitted',#{state},#{release},#{planned},#{expires})
        """)
    int control(@Param("tenant") Long tenant,@Param("version") String version,@Param("release") String release,
        @Param("state") String state,@Param("planned") Instant planned,@Param("expires") Instant expires);

    @Insert("""
        INSERT INTO ab_announcement_release(tenant_id,pid,announcement_pid,version_pid,actor_user_id,actor_member_pid,
            authorization_hash,scheduled_at,latest_allowed_at,timezone)
        VALUES(#{tenant},#{release},#{pid},#{version},#{actor},#{member},#{authorization},#{planned},#{latest},#{timezone})
        """)
    int queue(@Param("tenant") Long tenant,@Param("release") String release,@Param("pid") String pid,
        @Param("version") String version,@Param("actor") Long actor,@Param("member") String member,
        @Param("authorization") String authorization,@Param("planned") Instant planned,@Param("latest") Instant latest,@Param("timezone") String timezone);

    @Update("""
        UPDATE ab_announcement SET current_version_pid=#{version},active_version_pid=NULL,status=#{state},
            row_version=row_version+1,lifecycle_revision=lifecycle_revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actor}
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND row_version=#{expected} AND status='draft' AND deleted_flag=FALSE
        """)
    int accept(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version,
        @Param("actor") Long actor,@Param("state") String state,@Param("expected") int expected);

    @Select("SELECT *,fencing_token AS fence,(lease_until>clock_timestamp()) AS lease_valid FROM ab_announcement_release WHERE tenant_id=#{tenant} AND version_pid=#{version} FOR UPDATE")
    Map<String,Object> lock(@Param("tenant") Long tenant,@Param("version") String version);

    @Select("SELECT * FROM ab_announcement_release WHERE tenant_id=#{tenant} AND version_pid=#{version}")
    Map<String,Object> read(@Param("tenant") Long tenant,@Param("version") String version);

    @Update("""
        UPDATE ab_announcement_release SET status='queued',next_attempt_at=clock_timestamp(),fencing_token=fencing_token+1,
            lease_until=NULL,error_code=NULL
        WHERE tenant_id=#{tenant} AND pid=#{release} AND fencing_token=#{fence}
            AND status IN ('blocked','retryable_failed') AND latest_allowed_at>=clock_timestamp()
        """)
    int retry(@Param("tenant") Long tenant,@Param("release") String release,@Param("fence") long fence);

    @Update("""
        UPDATE ab_announcement SET row_version=row_version+1,lifecycle_revision=lifecycle_revision+1,
            updated_by=#{actor},updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND row_version=#{expected} AND current_version_pid=#{version}
            AND active_version_pid IS NULL AND status IN ('unpublished','scheduled') AND deleted_flag=FALSE
        """)
    int bumpRetry(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version,
        @Param("actor") Long actor,@Param("expected") int expected);

    @Select(value="""
        UPDATE ab_announcement_release SET status='running',lease_until=clock_timestamp()+INTERVAL '2 minutes',
            fencing_token=fencing_token+1,attempts=attempts+1
        WHERE (tenant_id,pid) IN (SELECT tenant_id,pid FROM ab_announcement_release
            WHERE scheduled_at<=clock_timestamp() AND next_attempt_at<=clock_timestamp()
              AND (status IN ('queued','retryable_failed') OR (status='running' AND lease_until<=clock_timestamp()))
            ORDER BY scheduled_at,pid FOR UPDATE SKIP LOCKED LIMIT 1)
        RETURNING tenant_id,pid,version_pid,actor_user_id,fencing_token,attempts
        """,affectData=true)
    Map<String,Object> claim();

    @Select("""
        SELECT v.snapshot::text AS snapshot,v.snapshot_hash,v.announcement_pid,c.* FROM ab_announcement_version v
        JOIN ab_announcement_version_control c ON c.tenant_id=v.tenant_id AND c.version_pid=v.pid
        WHERE v.tenant_id=#{tenant} AND v.pid=#{version}
        """)
    Map<String,Object> version(@Param("tenant") Long tenant,@Param("version") String version);

    @Insert("""
        INSERT INTO ab_announcement_recipient_snapshot(tenant_id,pid,version_pid,origin,resolved_at,recipient_count,sealed)
        VALUES(#{tenant},#{snapshot},#{version},'publication',#{at},#{count},FALSE)
        """)
    int beginSnapshot(@Param("tenant") Long tenant,@Param("snapshot") String snapshot,@Param("version") String version,@Param("at") Instant at,@Param("count") int count);

    @Update("""
        UPDATE ab_announcement_recipient_snapshot s SET sealed=TRUE
        WHERE s.tenant_id=#{tenant} AND s.version_pid=#{version} AND s.sealed=FALSE
            AND s.recipient_count=(SELECT count(*) FROM ab_announcement_recipient r
                WHERE r.tenant_id=s.tenant_id AND r.version_pid=s.version_pid)
        """)
    int seal(@Param("tenant") Long tenant,@Param("version") String version);

    @Insert("""
        <script>
        INSERT INTO ab_announcement_recipient(tenant_id,version_pid,user_pid,member_pid,display_name_snapshot,organization_snapshot) VALUES
        <foreach collection="recipients" item="r" separator=",">
          (#{tenant},#{version},#{r.userPid},#{r.memberPid},#{r.displayName},CAST(#{r.organizationSnapshotJson} AS jsonb))
        </foreach>
        </script>
        """)
    int recipients(@Param("tenant") Long tenant,@Param("version") String version,
        @Param("recipients") List<com.auraboot.framework.workbench.service.AnnouncementAudienceService.Recipient> recipients);

    @Update("""
        UPDATE ab_announcement_version_control SET publication_state='published',published_at=#{at},ack_due_at=#{ackDue},publication_revision=publication_revision+1
        WHERE tenant_id=#{tenant} AND version_pid=#{version} AND publication_state IN ('unpublished','scheduled')
        """)
    int publishControl(@Param("tenant") Long tenant,@Param("version") String version,@Param("at") Instant at,@Param("ackDue") Instant ackDue);

    @Update("""
        UPDATE ab_announcement SET status='published',active_version_pid=#{version},published_at=#{at},published_by=#{actor},
            row_version=row_version+1,lifecycle_revision=lifecycle_revision+1,updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND current_version_pid=#{version} AND active_version_pid IS NULL AND deleted_flag=FALSE
        """)
    int publishRecord(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version,@Param("actor") Long actor,@Param("at") Instant at);

    @Update("""
        UPDATE ab_announcement_release SET lease_until=clock_timestamp()+INTERVAL '2 minutes'
        WHERE tenant_id=#{tenant} AND pid=#{release} AND status='running' AND fencing_token=#{fence}
            AND lease_until>clock_timestamp()
        """)
    int renew(@Param("tenant") Long tenant,@Param("release") String release,@Param("fence") long fence);

    @Update("""
        UPDATE ab_announcement_release SET status=#{state},error_code=#{error},finished_at=CURRENT_TIMESTAMP,lease_until=NULL,
            next_attempt_at=CURRENT_TIMESTAMP+CASE attempts WHEN 1 THEN INTERVAL '5 seconds' WHEN 2 THEN INTERVAL '30 seconds' ELSE INTERVAL '120 seconds' END
        WHERE tenant_id=#{tenant} AND pid=#{release} AND status='running' AND fencing_token=#{fence}
            AND (#{state}!='succeeded' OR lease_until>clock_timestamp())
        """)
    int finish(@Param("tenant") Long tenant,@Param("release") String release,@Param("fence") long fence,@Param("state") String state,@Param("error") String error);

    @Update("""
        UPDATE ab_announcement_version_control SET publication_state=#{state},publication_revision=publication_revision+1
        WHERE tenant_id=#{tenant} AND version_pid=#{version} AND publication_state IN ('unpublished','scheduled')
        """)
    int stopControl(@Param("tenant") Long tenant,@Param("version") String version,@Param("state") String state);

    @Update("""
        UPDATE ab_announcement_release SET status='canceled',lease_until=NULL,finished_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND version_pid=#{version} AND status IN ('queued','running','retryable_failed','blocked')
        """)
    int cancel(@Param("tenant") Long tenant,@Param("version") String version);

    @Update("""
        UPDATE ab_announcement SET status=#{state},row_version=row_version+1,lifecycle_revision=lifecycle_revision+1,updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND current_version_pid=#{version} AND active_version_pid IS NULL
        """)
    int stopRecord(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version,@Param("state") String state);
}

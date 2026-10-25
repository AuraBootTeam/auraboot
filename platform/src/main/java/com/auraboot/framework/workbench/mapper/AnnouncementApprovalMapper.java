package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.*;

/** Business-owned approval coordination; no engine tables or engine identities are queried. */
@Mapper
public interface AnnouncementApprovalMapper {
    @Select("SELECT *,binding::text AS binding_json,terminal_result::text AS result_json FROM ab_announcement_approval_submission WHERE tenant_id=#{tenant} AND pid=#{submission}")
    Map<String,Object> read(@Param("tenant") Long tenant,@Param("submission") String submission);
    @Select("SELECT *,binding::text AS binding_json,terminal_result::text AS result_json FROM ab_announcement_approval_submission WHERE tenant_id=#{tenant} AND pid=#{submission} FOR UPDATE")
    Map<String,Object> lock(@Param("tenant") Long tenant,@Param("submission") String submission);
    @Select("SELECT * FROM ab_announcement_tenant_policy WHERE tenant_id=#{tenant}")
    Map<String,Object> policy(@Param("tenant") Long tenant);

    @Insert("""
        INSERT INTO ab_announcement_approval_submission(tenant_id,pid,announcement_pid,version_pid,actor_user_id,actor_member_pid,
            applicant_pid,provider_id,definition_label,binding,binding_hash)
        VALUES(#{tenant},#{submission},#{pid},#{version},#{actor},#{member},#{applicant},#{provider},#{label},CAST(#{binding} AS jsonb),#{hash})
        """)
    int insert(@Param("tenant") Long tenant,@Param("submission") String submission,@Param("pid") String pid,@Param("version") String version,
        @Param("actor") Long actor,@Param("member") String member,@Param("applicant") String applicant,@Param("provider") String provider,@Param("label") String label,
        @Param("binding") String binding,@Param("hash") String hash);

    @Insert("""
        INSERT INTO ab_announcement_version_control(tenant_id,version_pid,authorization_basis,approval_state,publication_state,
            current_submission_pid,planned_publish_at,expires_at)
        VALUES(#{tenant},#{version},'none','pending','unpublished',#{submission},#{planned},#{expires})
        """)
    int control(@Param("tenant") Long tenant,@Param("version") String version,@Param("submission") String submission,
        @Param("planned") Instant planned,@Param("expires") Instant expires);

    @Select(value="""
        UPDATE ab_announcement_approval_submission SET sync_state='polling',lease_until=CURRENT_TIMESTAMP+INTERVAL '2 minutes',fence=fence+1
        WHERE (tenant_id,pid)=(SELECT tenant_id,pid FROM ab_announcement_approval_submission
          WHERE state IN ('pending_dispatch','running','withdraw_requested') AND next_sync_at<=CURRENT_TIMESTAMP
            AND (sync_state IN ('queued','waiting') OR (sync_state='polling' AND lease_until<CURRENT_TIMESTAMP))
          ORDER BY next_sync_at,pid FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING tenant_id,pid,announcement_pid,fence,failure_count
        """,affectData=true)
    Map<String,Object> claim();

    @Update("""
        UPDATE ab_announcement_approval_submission SET state=CASE WHEN state='withdraw_requested' THEN state ELSE 'running' END,
            sync_state='waiting',instance_pid=COALESCE(#{instance},instance_pid),provider_revision=GREATEST(provider_revision,#{revision}),
            next_sync_at=CURRENT_TIMESTAMP+INTERVAL '10 seconds',lease_until=NULL,last_error=#{reason},provider_state=#{providerState},failure_count=0,updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{submission} AND sync_state='polling' AND fence=#{fence}
        """)
    int waiting(@Param("tenant") Long tenant,@Param("submission") String submission,@Param("fence") long fence,
        @Param("instance") String instance,@Param("revision") long revision,@Param("providerState") String providerState,@Param("reason") String reason);

    @Update("""
        UPDATE ab_announcement_approval_submission SET state=#{state},sync_state='completed',instance_pid=COALESCE(#{instance},instance_pid),
            terminal_result_pid=#{resultId},terminal_result=CAST(#{result} AS jsonb),provider_revision=#{revision},last_error=NULL,
            failure_count=0,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{submission} AND sync_state='polling' AND fence=#{fence} AND terminal_result_pid IS NULL
        """)
    int terminal(@Param("tenant") Long tenant,@Param("submission") String submission,@Param("fence") long fence,
        @Param("instance") String instance,@Param("resultId") String resultId,@Param("result") String result,
        @Param("revision") long revision,@Param("state") String state);

    @Update("""
        UPDATE ab_announcement_approval_submission SET sync_state=#{sync},failure_count=failure_count+1,last_error=#{reason},
            next_sync_at=#{next},lease_until=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{submission} AND sync_state='polling' AND fence=#{fence}
        """)
    int fail(@Param("tenant") Long tenant,@Param("submission") String submission,@Param("fence") long fence,
        @Param("sync") String sync,@Param("reason") String reason,@Param("next") Instant next);

    @Update("""
        UPDATE ab_announcement_approval_submission SET state='withdraw_requested',withdraw_actor_user_id=#{actor},withdraw_member_pid=#{member},
            withdraw_reason=#{reason},sync_state='queued',next_sync_at=CURRENT_TIMESTAMP,fence=fence+1,lease_until=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{submission} AND state IN ('pending_dispatch','running') AND terminal_result_pid IS NULL
        """)
    int withdraw(@Param("tenant") Long tenant,@Param("submission") String submission,@Param("actor") Long actor,
        @Param("member") String member,@Param("reason") String reason);

    @Update("""
        UPDATE ab_announcement_approval_submission SET sync_state='queued',next_sync_at=CURRENT_TIMESTAMP,
            fence=fence+1,lease_until=NULL,failure_count=0,last_error=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{submission} AND state IN ('pending_dispatch','running','withdraw_requested')
            AND terminal_result_pid IS NULL AND sync_state IN ('queued','waiting','blocked','polling')
        """)
    int reconcile(@Param("tenant") Long tenant,@Param("submission") String submission);

    @Update("UPDATE ab_announcement SET row_version=row_version+1,lifecycle_revision=lifecycle_revision+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actor} WHERE tenant_id=#{tenant} AND pid=#{pid} AND row_version=#{expected} AND status='pending_approval'")
    int bump(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("actor") Long actor,@Param("expected") int expected);

    @Update("""
        UPDATE ab_announcement_version_control SET approval_state=#{approval},authorization_basis=#{basis},publication_state=#{publication},
            release_attempt_pid=#{release},publication_revision=publication_revision+1
        WHERE tenant_id=#{tenant} AND version_pid=#{version} AND current_submission_pid=#{submission} AND approval_state='pending'
        """)
    int terminalControl(@Param("tenant") Long tenant,@Param("version") String version,@Param("submission") String submission,
        @Param("approval") String approval,@Param("basis") String basis,@Param("publication") String publication,@Param("release") String release);

    @Update("""
        UPDATE ab_announcement SET status=#{status},row_version=row_version+1,lifecycle_revision=lifecycle_revision+1,updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{pid} AND current_version_pid=#{version} AND active_version_pid IS NULL AND status='pending_approval'
        """)
    int terminalRecord(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version,@Param("status") String status);

    @Update("""
        UPDATE ab_announcement_approval_submission SET next_sync_at=CURRENT_TIMESTAMP,sync_state='queued',updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{submission} AND sync_state IN ('queued','waiting') AND terminal_result_pid IS NULL
        """)
    int wake(@Param("tenant") Long tenant,@Param("submission") String submission);
    @Update("""
        UPDATE ab_announcement_approval_submission SET state='withdrawn',sync_state='completed',lease_until=NULL,
            provider_state='not_started',last_error=NULL,updated_at=CURRENT_TIMESTAMP
        WHERE tenant_id=#{tenant} AND pid=#{submission} AND sync_state='polling' AND fence=#{fence} AND state='withdraw_requested'
            AND instance_pid IS NULL AND terminal_result_pid IS NULL
        """)
    int withdrawBeforeDispatch(@Param("tenant") Long tenant,@Param("submission") String submission,@Param("fence") long fence);
}

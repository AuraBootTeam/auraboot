package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.util.Map;

/** Explicit tenant CAS and JSONB audit contracts for announcement policy changes. */
@Mapper
public interface AnnouncementOperatingPolicyMapper {
    @Select("SELECT * FROM ab_announcement_tenant_policy WHERE tenant_id=#{tenant} FOR UPDATE")
    Map<String,Object> lock(@Param("tenant") Long tenant);

    @Insert("""
        INSERT INTO ab_announcement_tenant_policy(tenant_id,revision,security_revision,force_approval,default_definition_pid,
         late_window_minutes,reminder_at,automatic_reminder_limit,reminder_dedupe_minutes,quiet_start,quiet_end,updated_by)
        VALUES(#{tenant},1,#{p.securityRevision},#{p.forceApproval},#{p.defaultDefinitionPid},#{p.lateWindowMinutes},
         CAST(#{p.reminderAt} AS time),#{p.automaticReminderLimit},#{p.reminderDedupeMinutes},
         CAST(#{p.quietStart} AS time),CAST(#{p.quietEnd} AS time),#{actor}) ON CONFLICT DO NOTHING
        """)
    int insert(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("p") Map<String,Object> policy);

    @Update("""
        UPDATE ab_announcement_tenant_policy SET revision=revision+1,security_revision=#{p.securityRevision},
         force_approval=#{p.forceApproval},default_definition_pid=#{p.defaultDefinitionPid},late_window_minutes=#{p.lateWindowMinutes},
         reminder_at=CAST(#{p.reminderAt} AS time),automatic_reminder_limit=#{p.automaticReminderLimit},
         reminder_dedupe_minutes=#{p.reminderDedupeMinutes},quiet_start=CAST(#{p.quietStart} AS time),quiet_end=CAST(#{p.quietEnd} AS time),
         updated_by=#{actor},updated_at=CURRENT_TIMESTAMP WHERE tenant_id=#{tenant} AND revision=#{expected}
        """)
    int update(@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("expected") int expected,@Param("p") Map<String,Object> policy);

    @Insert("""
        INSERT INTO ab_announcement_policy_audit(pid,tenant_id,actor_user_id,actor_member_pid,operation_id,previous_revision,
         resulting_revision,previous_policy,resulting_policy)
        VALUES(#{pid},#{tenant},#{actor},#{member},#{operation},#{expected},#{expected}+1,CAST(#{before} AS jsonb),CAST(#{after} AS jsonb))
        """)
    int audit(@Param("pid") String pid,@Param("tenant") Long tenant,@Param("actor") Long actor,@Param("member") String member,
        @Param("operation") String operation,@Param("expected") int expected,@Param("before") String before,@Param("after") String after);
}

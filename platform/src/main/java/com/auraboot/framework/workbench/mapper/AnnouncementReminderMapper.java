package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import java.time.*;
import java.util.*;

/** All business mutations hold the announcement root lock first; global access is restricted to plan polling. */
@Mapper
public interface AnnouncementReminderMapper {
    @Insert("INSERT INTO ab_announcement_reminder_plan(tenant_id,version_pid,release_pid) VALUES(#{tenant},#{version},#{release})")
    int createPlan(@Param("tenant") Long tenant,@Param("version") String version,@Param("release") String release);

    @Select("""
        SELECT p.*,j.actor_user_id,j.actor_member_pid,j.announcement_pid FROM ab_announcement_reminder_plan p
        JOIN ab_announcement_release j ON j.tenant_id=p.tenant_id AND j.pid=p.release_pid
        WHERE p.status='active' AND p.next_scan_at<=clock_timestamp() ORDER BY p.next_scan_at,p.tenant_id,p.version_pid LIMIT 100
        """)
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine="true")
    List<Map<String,Object>> due();

    @Select("""
        SELECT p.*,j.actor_user_id,j.actor_member_pid FROM ab_announcement_reminder_plan p
        JOIN ab_announcement_release j ON j.tenant_id=p.tenant_id AND j.pid=p.release_pid
        WHERE p.tenant_id=#{tenant} AND p.version_pid=#{version} FOR UPDATE OF p
        """)
    Map<String,Object> plan(@Param("tenant") Long tenant,@Param("version") String version);

    @Update("""
        UPDATE ab_announcement_reminder_plan SET status=#{status},error_code=#{error},
        next_scan_at=clock_timestamp()+INTERVAL '5 minutes',automatic_batches=automatic_batches+#{increment}
        WHERE tenant_id=#{tenant} AND version_pid=#{version}
        """)
    int updatePlan(@Param("tenant") Long tenant,@Param("version") String version,@Param("status") String status,@Param("error") String error,@Param("increment") int increment);

    @Update("UPDATE ab_announcement_reminder_plan SET status='active',error_code=NULL,resume_after=clock_timestamp(),next_scan_at=clock_timestamp() WHERE tenant_id=#{tenant} AND version_pid=#{version} AND status='blocked'")
    int resume(@Param("tenant") Long tenant,@Param("version") String version);

    @Insert("""
        INSERT INTO ab_announcement_reminder_preview(tenant_id,pid,version_pid,owner_user_id,owner_member_pid,record_revision,filters,eligible_count,evaluated_at,expires_at)
        VALUES(#{tenant},#{pid},#{version},#{actor},#{member},#{revision},#{filters}::jsonb,#{count},#{now},#{now}+INTERVAL '30 minutes')
        """)
    int preview(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version,@Param("actor") Long actor,@Param("member") String member,
        @Param("revision") int revision,@Param("filters") String filters,@Param("count") int count,@Param("now") Instant now);

    @Select("SELECT *,filters::text AS filter_json FROM ab_announcement_reminder_preview WHERE tenant_id=#{tenant} AND pid=#{pid} AND owner_user_id=#{actor} AND owner_member_pid=#{member}")
    Map<String,Object> previewByOwner(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("actor") Long actor,@Param("member") String member);

    @Select("SELECT user_pid,last_intention_at FROM ab_announcement_reminder_guard WHERE tenant_id=#{tenant} AND version_pid=#{version}")
    List<Map<String,Object>> readGuards(@Param("tenant") Long tenant,@Param("version") String version);

    @Insert("""
        INSERT INTO ab_announcement_reminder_batch(tenant_id,pid,version_pid,actor_user_id,actor_member_pid,mode,local_date,target,filters,policy_hash,scope_hash,status,evaluated_at,intention_count,throttled_count,preview_count)
        VALUES(#{tenant},#{pid},#{version},#{actor},#{member},#{mode},#{day},#{target},#{filters}::jsonb,#{policy},#{scope},#{status},#{now},#{count},#{throttled},#{preview})
        """)
    int insertBatch(@Param("tenant") Long tenant,@Param("pid") String pid,@Param("version") String version,@Param("actor") Long actor,@Param("member") String member,
        @Param("mode") String mode,@Param("day") LocalDate day,@Param("target") String target,@Param("filters") String filters,@Param("policy") String policy,
        @Param("scope") String scope,@Param("status") String status,@Param("now") Instant now,@Param("count") int count,@Param("throttled") int throttled,@Param("preview") Integer preview);

    @Select("SELECT *,filters::text AS filter_json FROM ab_announcement_reminder_batch WHERE tenant_id=#{tenant} AND pid=#{batch}")
    Map<String,Object> findBatch(@Param("tenant") Long tenant,@Param("batch") String batch);

    @Select("SELECT EXISTS(SELECT 1 FROM ab_announcement_reminder_batch WHERE tenant_id=#{tenant} AND version_pid=#{version} AND mode='AUTO' AND local_date=#{day})")
    boolean scanned(@Param("tenant") Long tenant,@Param("version") String version,@Param("day") LocalDate day);

    @Insert("""
        <script>INSERT INTO ab_announcement_reminder_guard(tenant_id,version_pid,user_pid,last_intention_at,batch_pid) VALUES
        <foreach collection='users' item='user' separator=','>(#{tenant},#{version},#{user},#{now},#{batch})</foreach>
        ON CONFLICT(tenant_id,version_pid,user_pid) DO UPDATE SET last_intention_at=EXCLUDED.last_intention_at,batch_pid=EXCLUDED.batch_pid</script>
        """)
    int writeGuards(@Param("tenant") Long tenant,@Param("version") String version,@Param("users") List<String> users,@Param("now") Instant now,@Param("batch") String batch);

    @Insert("""
        <script>INSERT INTO ab_announcement_delivery(tenant_id,batch_pid,version_pid,user_pid,channel,kind) VALUES
        <foreach collection='users' item='user' separator=','>(#{tenant},#{batch},#{version},#{user},'in_app','reminder')</foreach></script>
        """)
    int intentions(@Param("tenant") Long tenant,@Param("batch") String batch,@Param("version") String version,@Param("users") List<String> users);

    @Select("SELECT user_pid FROM ab_announcement_delivery WHERE tenant_id=#{tenant} AND batch_pid=#{batch} AND kind='reminder' AND user_pid>#{after} ORDER BY user_pid LIMIT 500")
    List<String> users(@Param("tenant") Long tenant,@Param("batch") String batch,@Param("after") String after);

    @Update("UPDATE ab_announcement_reminder_batch SET status='blocked',error_code=#{reason} WHERE tenant_id=#{tenant} AND pid=#{batch} AND status='accepted'")
    int blockBatch(@Param("tenant") Long tenant,@Param("batch") String batch,@Param("reason") String reason);

    @Delete("DELETE FROM ab_announcement_reminder_preview WHERE expires_at<=clock_timestamp()")
    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine="true")
    int deleteExpiredPreviews();
}

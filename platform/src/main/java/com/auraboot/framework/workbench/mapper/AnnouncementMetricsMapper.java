package com.auraboot.framework.workbench.mapper;

import org.apache.ibatis.annotations.*;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

/** Host telemetry aggregates only; no tenant, actor or document identity is returned. */
@Mapper
public interface AnnouncementMetricsMapper {
    @Select("SELECT count(*) FROM ab_announcement_release WHERE status IN ('queued','running','retryable_failed') AND scheduled_at<=CURRENT_TIMESTAMP")
    @InterceptorIgnore(tenantLine="true")
    long publicationPending();
    @Select("SELECT count(*) FROM ab_announcement_release WHERE status IN ('blocked','retryable_failed','missed')")
    @InterceptorIgnore(tenantLine="true")
    long publicationFailures();
    @Select("SELECT COALESCE(MAX(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP-scheduled_at)),0) FROM ab_announcement_release WHERE status IN ('queued','running','retryable_failed') AND scheduled_at<=CURRENT_TIMESTAMP")
    @InterceptorIgnore(tenantLine="true")
    double publicationAge();
    @Select("SELECT COALESCE(AVG(EXTRACT(EPOCH FROM finished_at-scheduled_at)),0) FROM ab_announcement_release WHERE status='succeeded' AND finished_at>=CURRENT_TIMESTAMP-INTERVAL '24 hours'")
    @InterceptorIgnore(tenantLine="true")
    double publicationDelay();
    @Select("SELECT count(*) FROM ab_announcement_approval_submission WHERE sync_state IN ('queued','polling','waiting') AND next_sync_at<=CURRENT_TIMESTAMP AND terminal_result_pid IS NULL")
    @InterceptorIgnore(tenantLine="true")
    long approvalPending();
    @Select("SELECT count(*) FROM ab_announcement_approval_submission WHERE sync_state='blocked'")
    @InterceptorIgnore(tenantLine="true")
    long approvalFailures();
    @Select("SELECT count(*) FROM ab_announcement_delivery WHERE status='queued'")
    @InterceptorIgnore(tenantLine="true")
    long deliveryPending();
    @Select("SELECT count(*) FROM ab_announcement_delivery WHERE status='failed'")
    @InterceptorIgnore(tenantLine="true")
    long deliveryFailures();
    @Select("SELECT COALESCE(MAX(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP-created_at)),0) FROM ab_announcement_delivery WHERE status='queued'")
    @InterceptorIgnore(tenantLine="true")
    double deliveryAge();
    @Select("SELECT count(*) FROM ab_announcement_reminder_plan WHERE status='blocked'")
    @InterceptorIgnore(tenantLine="true")
    long reminderFailures();
    @Select("SELECT COALESCE(MAX(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP-next_sync_at)),0) FROM ab_announcement_approval_submission WHERE sync_state IN ('queued','polling','waiting') AND next_sync_at<=CURRENT_TIMESTAMP AND terminal_result_pid IS NULL")
    @InterceptorIgnore(tenantLine="true")
    double approvalAge();
    @Select("SELECT count(*) FROM ab_outbox WHERE event_source='auraboot.announcement' AND event_type=#{event} AND status IN ('pending','processing')")
    @InterceptorIgnore(tenantLine="true")
    long projectionPending(@Param("event") String event);
    @Select("SELECT count(*) FROM ab_outbox WHERE event_source='auraboot.announcement' AND event_type=#{event} AND status='failed'")
    @InterceptorIgnore(tenantLine="true")
    long projectionFailures(@Param("event") String event);
    @Select("SELECT COALESCE(MAX(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP-created_at)),0) FROM ab_outbox WHERE event_source='auraboot.announcement' AND event_type=#{event} AND status IN ('pending','processing')")
    @InterceptorIgnore(tenantLine="true")
    double projectionAge(@Param("event") String event);
}

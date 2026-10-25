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

    @Select("""
        SELECT count(*) FROM ab_export_task e JOIN ab_async_task q ON q.tenant_id=e.tenant_id
            AND q.created_by=e.created_by AND q.task_code=e.request_params->>'asyncTaskCode'
        WHERE e.query_code='domain:announcement.statistics.v1' AND q.task_type='domain-export'
            AND e.expires_at>CURRENT_TIMESTAMP AND e.status<>'completed' AND q.status IN ('pending','running')
        """)
    @InterceptorIgnore(tenantLine="true")
    long exportPending();
    @Select("""
        SELECT count(*) FROM ab_export_task e JOIN ab_async_task q ON q.tenant_id=e.tenant_id
            AND q.created_by=e.created_by AND q.task_code=e.request_params->>'asyncTaskCode'
        WHERE e.query_code='domain:announcement.statistics.v1' AND q.task_type='domain-export'
            AND e.expires_at>CURRENT_TIMESTAMP AND q.status='failed'
        """)
    @InterceptorIgnore(tenantLine="true")
    long exportFailures();
    @Select("""
        SELECT COALESCE(MAX(GREATEST(0,EXTRACT(EPOCH FROM CURRENT_TIMESTAMP-e.created_at))),0)
        FROM ab_export_task e JOIN ab_async_task q ON q.tenant_id=e.tenant_id AND q.created_by=e.created_by
            AND q.task_code=e.request_params->>'asyncTaskCode'
        WHERE e.query_code='domain:announcement.statistics.v1' AND q.task_type='domain-export'
            AND e.expires_at>CURRENT_TIMESTAMP AND e.status<>'completed' AND q.status IN ('pending','running')
        """)
    @InterceptorIgnore(tenantLine="true")
    double exportAge();
    @Select("""
        SELECT count(*) FROM ab_domain_export_attempt a JOIN ab_export_task e
            ON e.tenant_id=a.tenant_id AND e.pid=a.export_task_pid
        WHERE e.query_code='domain:announcement.statistics.v1' AND a.cleanup_error IS NOT NULL
        """)
    @InterceptorIgnore(tenantLine="true")
    long exportCleanupFailures();
}

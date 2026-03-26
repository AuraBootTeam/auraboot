package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.ApprovalTask;
import com.auraboot.framework.bpm.entity.ChainExecution;
import com.auraboot.framework.bpm.mapper.ApprovalTaskMapper;
import com.auraboot.framework.bpm.mapper.ChainExecutionMapper;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * GAP-003: Approval timeout / escalation job.
 *
 * Runs hourly and examines every pending approval task whose associated process definition
 * has a {@code timeout_hours} value set. When a task has been waiting longer than the
 * configured threshold the job executes the configured {@code timeout_action}:
 *
 * <ul>
 *   <li>ESCALATE   — sends a CC notification to {@code escalate_to_user_id} and marks
 *                    the task as ESCALATED (status stays PENDING so the original approver
 *                    can still act).</li>
 *   <li>AUTO_APPROVE — auto-approves the task (sets status=APPROVED, records the action).</li>
 *   <li>AUTO_REJECT  — auto-rejects the task (sets status=REJECTED, records the action).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalTimeoutJob {

    private final ApprovalTaskMapper approvalTaskMapper;
    private final ChainExecutionMapper chainExecutionMapper;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final BpmNotifyService bpmNotifyService;
    private final JdbcTemplate jdbcTemplate;

    /** System user ID used as sender for auto-action notifications. */
    private static final Long SYSTEM_USER_ID = 0L;

    /**
     * Run every hour (3 600 000 ms).
     * Finds all PENDING approval tasks where:
     * - The associated process definition has timeout_hours set
     * - The task was created more than timeout_hours ago
     */
    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void processTimeouts() {
        List<Long> tenantIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT tenant_id FROM ab_approval_task WHERE status = 'pending'",
                Long.class);

        for (Long tenantId : tenantIds) {
            MetaContext.setContext(tenantId, 0L, null, "system");
            try {
                List<ApprovalTask> pendingTasks = approvalTaskMapper.selectList(
                        new QueryWrapper<ApprovalTask>().eq("status", StatusConstants.PENDING));

                for (ApprovalTask task : pendingTasks) {
                    processTask(task);
                }
            } finally {
                MetaContext.clear();
            }
        }
    }

    private void processTask(ApprovalTask task) {
        BpmProcessDefinition processDef = processDefinitionMapper.selectOne(
                new QueryWrapper<BpmProcessDefinition>()
                        .eq("process_key", task.getProcessKey())
                        .eq("is_current", true)
                        .eq("deleted_flag", false));

        if (processDef == null || processDef.getTimeoutHours() == null) {
            return; // No timeout configured for this process
        }

        Instant timeoutAt = task.getCreatedAt().plus(processDef.getTimeoutHours(), ChronoUnit.HOURS);
        if (Instant.now().isBefore(timeoutAt)) {
            return; // Not yet overdue
        }

        String action = processDef.getTimeoutAction() != null ? processDef.getTimeoutAction() : "escalate";
        log.info("Timeout action={} for task={} (processKey={}, tenantId={})",
                action, task.getPid(), task.getProcessKey(), task.getTenantId());

        switch (action) {
            case "auto_approve" -> autoApprove(task);
            case "auto_reject" -> autoReject(task);
            default -> escalate(task, processDef.getEscalateToUserId(), processDef.getTimeoutHours());
        }
    }

    private void autoApprove(ApprovalTask task) {
        int updated = approvalTaskMapper.update(null,
                new UpdateWrapper<ApprovalTask>()
                        .set("status", StatusConstants.APPROVED)
                        .set("actual_approver_id", SYSTEM_USER_ID)
                        .set("approval_comment", "Auto-approved due to approval timeout policy")
                        .set("completed_at", Instant.now())
                        .set("updated_at", Instant.now())
                        .eq("pid", task.getPid())
                        .eq("status", StatusConstants.PENDING));

        if (updated > 0) {
            log.info("Auto-approved timed-out task={}", task.getPid());
        }
    }

    private void autoReject(ApprovalTask task) {
        int updated = approvalTaskMapper.update(null,
                new UpdateWrapper<ApprovalTask>()
                        .set("status", StatusConstants.REJECTED)
                        .set("actual_approver_id", SYSTEM_USER_ID)
                        .set("approval_comment", "Auto-rejected due to approval timeout policy")
                        .set("completed_at", Instant.now())
                        .set("updated_at", Instant.now())
                        .eq("pid", task.getPid())
                        .eq("status", StatusConstants.PENDING));

        if (updated > 0) {
            // Fail the chain execution
            chainExecutionMapper.update(null,
                    new UpdateWrapper<ChainExecution>()
                            .set("status", StatusConstants.FAILED)
                            .set("error_message", "Approval task auto-rejected at timeout: " + task.getChainNodeId())
                            .set("completed_at", Instant.now())
                            .set("updated_at", Instant.now())
                            .eq("pid", task.getChainExecutionId())
                            .eq("status", StatusConstants.SUSPENDED));

            log.info("Auto-rejected timed-out task={}", task.getPid());
        }
    }

    private void escalate(ApprovalTask task, Long escalateToUserId, int timeoutHours) {
        if (escalateToUserId == null) {
            log.warn("Timeout action=ESCALATE but no escalate_to_user_id for processKey={}; skipping",
                    task.getProcessKey());
            return;
        }

        // Send escalation CC notification to the configured escalation target
        String content = String.format(
                "Approval task [%s] has been pending for over %d hours and requires your attention. Task ID: %s",
                task.getTaskTitle(), timeoutHours, task.getPid());

        bpmNotifyService.sendCarbonCopy(
                task.getPid(), task.getChainExecutionId(),
                SYSTEM_USER_ID, List.of(escalateToUserId), content);

        // Also notify original assignees of the escalation
        if (task.getAssigneeUserIds() != null && !task.getAssigneeUserIds().isEmpty()) {
            String assigneeContent = String.format(
                    "Approval task [%s] has exceeded the timeout threshold and has been escalated. Please take action immediately.",
                    task.getTaskTitle());
            bpmNotifyService.sendCarbonCopy(
                    task.getPid(), task.getChainExecutionId(),
                    SYSTEM_USER_ID, task.getAssigneeUserIds(), assigneeContent);
        }

        log.info("Escalated timed-out task={} to userId={}", task.getPid(), escalateToUserId);
    }
}

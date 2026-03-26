package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.ApprovalTask;
import com.auraboot.framework.bpm.entity.ChainExecution;
import com.auraboot.framework.bpm.mapper.ApprovalTaskMapper;
import com.auraboot.framework.bpm.mapper.ChainExecutionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Scheduled job to expire overdue approval tasks.
 * Runs every 60 seconds and marks tasks past their deadline as EXPIRED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalDeadlineScheduler {

    private final ApprovalTaskMapper approvalTaskMapper;
    private final ChainExecutionMapper chainExecutionMapper;
    private final JdbcTemplate jdbcTemplate;

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void expireOverdueTasks() {
        List<Long> tenantIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT tenant_id FROM ab_approval_task WHERE status = 'pending' AND deadline_at IS NOT NULL AND deadline_at < NOW()",
                Long.class);

        for (Long tenantId : tenantIds) {
            MetaContext.setContext(tenantId, 0L, null, "system");
            try {
                List<ApprovalTask> expiredTasks = approvalTaskMapper.selectList(
                        new QueryWrapper<ApprovalTask>()
                                .eq("status", StatusConstants.PENDING)
                                .isNotNull("deadline_at")
                                .lt("deadline_at", Instant.now()));

                for (ApprovalTask task : expiredTasks) {
                    int updated = approvalTaskMapper.update(null,
                            new UpdateWrapper<ApprovalTask>()
                                    .set("status", StatusConstants.EXPIRED)
                                    .set("completed_at", Instant.now())
                                    .set("updated_at", Instant.now())
                                    .eq("pid", task.getPid())
                                    .eq("status", StatusConstants.PENDING));

                    if (updated > 0) {
                        chainExecutionMapper.update(null,
                                new UpdateWrapper<ChainExecution>()
                                        .set("status", StatusConstants.FAILED)
                                        .set("error_message",
                                                "Approval task expired at node " + task.getChainNodeId())
                                        .set("completed_at", Instant.now())
                                        .set("updated_at", Instant.now())
                                        .eq("pid", task.getChainExecutionId())
                                        .eq("status", StatusConstants.SUSPENDED));

                        log.info("Expired approval task {} (chain {})", task.getPid(), task.getChainExecutionId());
                    }
                }
            } finally {
                MetaContext.clear();
            }
        }
    }
}

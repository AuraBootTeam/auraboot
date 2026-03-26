package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.bpm.mapper.SlaRecordMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import com.auraboot.framework.common.constant.StatusConstants;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlaRecordService {
    private final SlaRecordMapper slaRecordMapper;
    private final SlaConfigMapper slaConfigMapper;

    /**
     * Create a new SLA record when a task/process node starts.
     */
    @Transactional
    public SlaRecordEntity createRecord(SlaConfigEntity config, String processInstanceId, String taskId, String nodeId, Instant deadlineTime) {
        Long tenantId = MetaContext.getCurrentTenantId();
        SlaRecordEntity record = SlaRecordEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .slaConfigId(config.getPid())
                .processInstanceId(processInstanceId)
                .taskId(taskId)
                .nodeId(nodeId)
                .startTime(Instant.now())
                .deadlineTime(deadlineTime)
                .status(StatusConstants.RUNNING)
                .currentWarningLevel(0)
                .warningHistory(new ArrayList<>())
                .totalPausedMs(0L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        slaRecordMapper.insert(record);
        log.info("Created SLA record: pid={}, configId={}, deadline={}", record.getPid(), config.getPid(), deadlineTime);
        return record;
    }

    /**
     * Complete an SLA record.
     */
    @Transactional
    public void completeRecord(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        SlaRecordEntity record = slaRecordMapper.findByPid(pid, tenantId);
        if (record == null || !record.isActive()) return;
        record.setStatus(StatusConstants.COMPLETED);
        record.setCompletedTime(Instant.now());
        record.setUpdatedAt(Instant.now());
        slaRecordMapper.updateById(record);
        log.info("SLA record completed: pid={}", pid);
    }

    /**
     * Complete all active records for a task.
     */
    @Transactional
    public void completeByTaskId(String taskId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<SlaRecordEntity> records = slaRecordMapper.findByTaskId(taskId, tenantId);
        for (SlaRecordEntity record : records) {
            if (record.isActive()) {
                record.setStatus(StatusConstants.COMPLETED);
                record.setCompletedTime(Instant.now());
                record.setUpdatedAt(Instant.now());
                slaRecordMapper.updateById(record);
            }
        }
    }

    /**
     * Cancel all active records for a process instance.
     */
    @Transactional
    public void cancelByProcessInstance(String processInstanceId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<SlaRecordEntity> records = slaRecordMapper.findByProcessInstance(processInstanceId, tenantId);
        for (SlaRecordEntity record : records) {
            if (record.isActive()) {
                record.setStatus(StatusConstants.CANCELLED);
                record.setUpdatedAt(Instant.now());
                slaRecordMapper.updateById(record);
            }
        }
    }

    /**
     * Pause SLA records for a process instance based on suspend policy.
     * Called when a process instance is suspended.
     */
    @Transactional
    public void pauseByProcessInstance(String processInstanceId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<SlaRecordEntity> records = slaRecordMapper.findActiveByProcessInstance(processInstanceId, tenantId);

        for (SlaRecordEntity record : records) {
            if (record.isPaused()) {
                continue;
            }

            SlaConfigEntity config = slaConfigMapper.findByPid(record.getSlaConfigId());
            String policy = config != null && config.getSuspendPolicy() != null
                    ? config.getSuspendPolicy() : "pause";

            switch (policy) {
                case "pause" -> {
                    record.setPausedAt(Instant.now());
                    record.setStatus("paused");
                    record.setUpdatedAt(Instant.now());
                    slaRecordMapper.updateById(record);
                    log.info("SLA record paused: pid={}, processInstanceId={}", record.getPid(), processInstanceId);
                }
                case "cancel" -> {
                    record.setStatus(StatusConstants.CANCELLED);
                    record.setUpdatedAt(Instant.now());
                    slaRecordMapper.updateById(record);
                    log.info("SLA record cancelled by suspend policy: pid={}", record.getPid());
                }
                case "continue" -> log.debug("SLA record continues during suspend: pid={}", record.getPid());
                default -> log.warn("Unknown suspend policy '{}' for SLA config: {}", policy, config.getPid());
            }
        }
    }

    /**
     * Resume SLA records for a process instance.
     * Called when a process instance is resumed.
     */
    @Transactional
    public void resumeByProcessInstance(String processInstanceId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<SlaRecordEntity> records = slaRecordMapper.findActiveByProcessInstance(processInstanceId, tenantId);

        for (SlaRecordEntity record : records) {
            if (!record.isPaused() || record.getPausedAt() == null) {
                continue;
            }

            long pausedDuration = Duration.between(record.getPausedAt(), Instant.now()).toMillis();
            long totalPaused = (record.getTotalPausedMs() != null ? record.getTotalPausedMs() : 0L) + pausedDuration;

            record.setTotalPausedMs(totalPaused);
            record.setPausedAt(null);
            record.setStatus(StatusConstants.RUNNING);
            record.setUpdatedAt(Instant.now());
            slaRecordMapper.updateById(record);

            log.info("SLA record resumed: pid={}, pausedDuration={}ms, totalPaused={}ms",
                    record.getPid(), pausedDuration, totalPaused);
        }
    }

    /**
     * Get all active SLA records (for scheduler).
     */
    public List<SlaRecordEntity> getActiveRecords() {
        return slaRecordMapper.findActiveRecords();
    }

    /**
     * Update record status and warning level.
     */
    @Transactional
    public void updateWarning(SlaRecordEntity record, int warningLevel, String status, Map<String, Object> warningEntry) {
        record.setCurrentWarningLevel(warningLevel);
        record.setStatus(status);
        List<Map<String, Object>> history = record.getWarningHistory();
        if (history == null) history = new ArrayList<>();
        history.add(warningEntry);
        record.setWarningHistory(history);
        record.setUpdatedAt(Instant.now());
        slaRecordMapper.updateById(record);
    }

    public List<SlaRecordEntity> findByProcessInstance(String processInstanceId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return slaRecordMapper.findByProcessInstance(processInstanceId, tenantId);
    }

    public SlaRecordEntity getByPid(String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return slaRecordMapper.findByPid(pid, tenantId);
    }

    /**
     * List SLA records for the current tenant with optional status filter.
     * Used by monitor drill-down UI.
     */
    public List<SlaRecordEntity> listByStatus(String status) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return slaRecordMapper.findByTenantFiltered(tenantId, status);
    }

    /**
     * Calculate progress percentage (0.0 to N where 1.0 = 100% = at deadline).
     * Deducts paused time from elapsed time to get accurate progress.
     */
    public double calculateProgress(SlaRecordEntity record) {
        Instant now = Instant.now();
        long totalMs = Duration.between(record.getStartTime(), record.getDeadlineTime()).toMillis();
        if (totalMs <= 0) return 1.0;

        long totalPausedMs = record.getTotalPausedMs() != null ? record.getTotalPausedMs() : 0L;

        // If currently paused, add the ongoing pause duration
        if (record.getPausedAt() != null) {
            totalPausedMs += Duration.between(record.getPausedAt(), now).toMillis();
        }

        long elapsedMs = Duration.between(record.getStartTime(), now).toMillis() - totalPausedMs;
        return (double) elapsedMs / totalMs;
    }
}

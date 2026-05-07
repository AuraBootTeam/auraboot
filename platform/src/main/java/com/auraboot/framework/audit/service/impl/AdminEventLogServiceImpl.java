package com.auraboot.framework.audit.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.mapper.AdminEventLogMapper;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Default implementation of {@link AdminEventLogService}.
 *
 * <p>Fire-and-forget contract — exceptions during persistence are logged at WARN
 * and swallowed so audit-write failures cannot break the underlying action.
 */
@Slf4j
@Service
public class AdminEventLogServiceImpl implements AdminEventLogService {

    private static final int MAX_LIMIT = 1000;

    @Autowired
    private AdminEventLogMapper adminEventLogMapper;

    @Override
    public void record(AdminEventLog logEntry) {
        if (logEntry == null) {
            return;
        }
        try {
            // Fill defaults from MetaContext where missing.
            if (logEntry.getTenantId() == null) {
                logEntry.setTenantId(MetaContext.getCurrentTenantId());
            }
            if (logEntry.getActorUserId() == null) {
                logEntry.setActorUserId(MetaContext.getCurrentUserId());
            }
            if (logEntry.getActorType() == null || logEntry.getActorType().isBlank()) {
                logEntry.setActorType("user");
            }
            if (logEntry.getCreatedAt() == null) {
                logEntry.setCreatedAt(Instant.now());
            }
            if (logEntry.getPid() == null || logEntry.getPid().isBlank()) {
                logEntry.setPid(UniqueIdGenerator.generate());
            }
            if (logEntry.getActionType() == null || logEntry.getActionType().isBlank()) {
                log.warn("AdminEventLog.record called with blank actionType — dropping entry");
                return;
            }
            if (logEntry.getSuccess() == null) {
                logEntry.setSuccess(Boolean.TRUE);
            }
            if (logEntry.getTenantId() == null) {
                // Cannot persist a multi-tenant audit row without tenant context.
                log.warn("AdminEventLog.record called with no tenantId in MetaContext — dropping entry actionType={}", logEntry.getActionType());
                return;
            }

            adminEventLogMapper.insertEventLog(logEntry);
        } catch (Exception e) {
            log.warn("AdminEventLog persist failed (action={}, resource={}/{}): {}",
                    logEntry.getActionType(),
                    logEntry.getResourceType(),
                    logEntry.getResourcePid(),
                    e.getMessage());
        }
    }

    @Override
    public List<AdminEventLog> recentByTenant(Long tenantId, int limit) {
        if (tenantId == null) {
            return Collections.emptyList();
        }
        int safeLimit = clamp(limit);
        QueryWrapper<AdminEventLog> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId)
                .orderByDesc("created_at")
                .last("LIMIT " + safeLimit);
        return adminEventLogMapper.selectList(qw);
    }

    @Override
    public List<AdminEventLog> byResource(Long tenantId, String resourceType, String resourcePid, int limit) {
        if (tenantId == null || resourceType == null || resourcePid == null) {
            return Collections.emptyList();
        }
        int safeLimit = clamp(limit);
        QueryWrapper<AdminEventLog> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId)
                .eq("resource_type", resourceType)
                .eq("resource_pid", resourcePid)
                .orderByDesc("created_at")
                .last("LIMIT " + safeLimit);
        return adminEventLogMapper.selectList(qw);
    }

    private static int clamp(int limit) {
        if (limit < 1) return 1;
        if (limit > MAX_LIMIT) return MAX_LIMIT;
        return limit;
    }
}

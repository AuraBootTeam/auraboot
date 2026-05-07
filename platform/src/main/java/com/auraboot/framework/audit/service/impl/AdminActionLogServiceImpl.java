package com.auraboot.framework.audit.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminActionLog;
import com.auraboot.framework.audit.mapper.AdminActionLogMapper;
import com.auraboot.framework.audit.service.AdminActionLogService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Default implementation of {@link AdminActionLogService}.
 *
 * <p>Fire-and-forget contract — exceptions during persistence are logged at WARN
 * and swallowed so audit-write failures cannot break the underlying action.
 */
@Slf4j
@Service
public class AdminActionLogServiceImpl implements AdminActionLogService {

    private static final int MAX_LIMIT = 1000;

    @Autowired
    private AdminActionLogMapper adminActionLogMapper;

    @Override
    public void record(AdminActionLog logEntry) {
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
                log.warn("AdminActionLog.record called with blank actionType — dropping entry");
                return;
            }
            if (logEntry.getSuccess() == null) {
                logEntry.setSuccess(Boolean.TRUE);
            }
            if (logEntry.getTenantId() == null) {
                // Cannot persist a multi-tenant audit row without tenant context.
                log.warn("AdminActionLog.record called with no tenantId in MetaContext — dropping entry actionType={}", logEntry.getActionType());
                return;
            }

            adminActionLogMapper.insertActionLog(logEntry);
        } catch (Exception e) {
            log.warn("AdminActionLog persist failed (action={}, resource={}/{}): {}",
                    logEntry.getActionType(),
                    logEntry.getResourceType(),
                    logEntry.getResourcePid(),
                    e.getMessage());
        }
    }

    @Override
    public List<AdminActionLog> recentByTenant(Long tenantId, int limit) {
        if (tenantId == null) {
            return Collections.emptyList();
        }
        int safeLimit = clamp(limit);
        QueryWrapper<AdminActionLog> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId)
                .orderByDesc("created_at")
                .last("LIMIT " + safeLimit);
        return adminActionLogMapper.selectList(qw);
    }

    @Override
    public List<AdminActionLog> byResource(Long tenantId, String resourceType, String resourcePid, int limit) {
        if (tenantId == null || resourceType == null || resourcePid == null) {
            return Collections.emptyList();
        }
        int safeLimit = clamp(limit);
        QueryWrapper<AdminActionLog> qw = new QueryWrapper<>();
        qw.eq("tenant_id", tenantId)
                .eq("resource_type", resourceType)
                .eq("resource_pid", resourcePid)
                .orderByDesc("created_at")
                .last("LIMIT " + safeLimit);
        return adminActionLogMapper.selectList(qw);
    }

    private static int clamp(int limit) {
        if (limit < 1) return 1;
        if (limit > MAX_LIMIT) return MAX_LIMIT;
        return limit;
    }
}

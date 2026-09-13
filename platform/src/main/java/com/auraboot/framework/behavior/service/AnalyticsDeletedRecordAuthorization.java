package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.RuleCenterRecordReadAuthorization;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.tenant.service.TenantMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;

/** Loads server-owned basis and evaluates current authorization; never accepts a caller snapshot. */
@Service
@RequiredArgsConstructor
public class AnalyticsDeletedRecordAuthorization {
    private final JdbcTemplate jdbc;
    private final TenantMemberService members;
    private final PermissionFacade permissions;
    private final DataPermissionEngine scopes;

    public void requireReadable(String eventId, String model, String target) {
        Long tenant = MetaContext.getCurrentTenantId();
        Long actor = MetaContext.getCurrentUserId();
        var rows = jdbc.queryForList("""
                SELECT record_id, record_pid, created_by FROM ab_analytics_deleted_record_basis
                WHERE tenant_id=? AND event_id=? AND model_code=? AND target_key=? AND basis_version=1
                """, tenant, eventId, model, target);
        if (rows.size() != 1) throw new AccessDeniedException("Historical business result basis is unavailable");
        var member = members.findByTenantIdAndUserId(tenant, actor);
        if (member == null) throw new AccessDeniedException("Historical business result member is unavailable");
        var basis = rows.get(0);
        var record = new LinkedHashMap<String, Object>();
        record.put("id", basis.get("record_id"));
        record.put("pid", basis.get("record_pid"));
        record.put("tenant_id", tenant);
        record.put("created_by", basis.get("created_by"));
        RuleCenterRecordReadAuthorization.requireReadable(model, record, member::getId, () -> permissions);
        if (!scopes.canAccessHistoricalRecord(tenant, model, actor, member.getId(), record)) {
            throw new AccessDeniedException("Historical business result is outside the current data scope");
        }
    }
}

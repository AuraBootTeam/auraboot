package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Record visibility at subscription and delivery boundaries. */
@Service
public class RecordReadAccess {
    private final DynamicDataService data;
    private final PermissionFacade permissions;
    private final TenantMemberService members;
    private final UserRoleService roles;
    private final com.auraboot.framework.user.service.UserService users;

    public RecordReadAccess(@Lazy DynamicDataService data, PermissionFacade permissions,
                            TenantMemberService members, UserRoleService roles,
                            com.auraboot.framework.user.service.UserService users) {
        this.data = data;
        this.permissions = permissions;
        this.members = members;
        this.roles = roles;
        this.users = users;
    }

    public void requireReadable(String modelCode, String recordPid) {
        if (!permissions.canAction(MetaContext.getCurrentMemberId(), modelCode, "read")
                || data.getById(modelCode, recordPid) == null) {
            throw new AccessDeniedException("Record read permission is required");
        }
    }

    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public boolean isReadableBy(Long tenantId, Long userId, String modelCode, String recordPid) {
        var member = members.findByTenantIdAndUserId(tenantId, userId);
        if (member == null || Boolean.TRUE.equals(member.getDeletedFlag())
                || !"active".equalsIgnoreCase(member.getStatus())) return false;
        var user = users.findByUserId(userId);
        if (user == null || !user.isEnabled() || Boolean.TRUE.equals(user.getDeletedFlag())) return false;
        var source = MetaContext.snapshot();
        var snapshot = new MetaContext.Snapshot(tenantId, userId, user.getPid(), user.getUserName(),
                Set.copyOf(roles.getRoleIdsByMemberIdAndTenantId(member.getId(), tenantId)),
                member.getId(), source == null ? null : source.envId(),
                source == null ? null : source.otelTraceId(), null);
        var readable = new AtomicBoolean();
        MetaContext.runWithSnapshot(snapshot, () -> {
            try {
                requireReadable(modelCode, recordPid);
                readable.set(true);
            } catch (AccessDeniedException denied) {
                // CATCH: an expected per-recipient authorization denial excludes this recipient.
                readable.set(false);
            }
        });
        return readable.get();
    }
}

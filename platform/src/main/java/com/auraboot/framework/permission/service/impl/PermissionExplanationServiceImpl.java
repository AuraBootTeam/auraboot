package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.permission.service.PermissionExplanationService;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/** Resolves records as the caller, then evaluates them as the selected tenant member. */
@Service
@RequiredArgsConstructor
public class PermissionExplanationServiceImpl implements PermissionExplanationService {
    private final TenantMemberMapper tenantMemberMapper;
    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final DynamicDataService dynamicDataService;
    private final PermissionEvaluator permissionEvaluator;

    @Override
    public PermissionExplanation explain(Long memberId, String resource, String action, String recordPid) {
        MetaContext.Snapshot caller = MetaContext.snapshot();
        if (caller == null || caller.tenantId() == null || memberId == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Tenant and member are required");
        }
        TenantMember member = tenantMemberMapper.selectById(memberId);
        if (member == null || !Objects.equals(caller.tenantId(), member.getTenantId())
                || Boolean.TRUE.equals(member.getDeletedFlag())
                || !"ACTIVE".equalsIgnoreCase(member.getStatus())) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Member must be active in the current tenant");
        }
        User user = member.getUserId() == null ? null : userMapper.selectById(member.getUserId());
        if (user == null || !user.isEnabled() || Boolean.TRUE.equals(user.getDeletedFlag())) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Member must have an enabled user");
        }
        Map<String, Object> record = null;
        if (recordPid != null) {
            if (recordPid.isBlank()) {
                throw new RootUnCheckedException(ResponseCode.BadParam, "recordPid cannot be blank");
            }
            // Do not bypass the caller's record/field authorization for diagnostics.
            record = dynamicDataService.getById(resource, recordPid);
            if (record == null || record.isEmpty()) {
                throw new RootUnCheckedException(ResponseCode.BadParam, "Target record is not available to the caller");
            }
        }
        var roles = userRoleMapper.findRoleIdsByMemberId(memberId);
        try {
            // Tenant-member diagnostics do not inherit the administrator's party/session identity.
            MetaContext.restore(new MetaContext.Snapshot(caller.tenantId(), user.getId(), user.getPid(),
                    user.getUserName(), new HashSet<>(roles), memberId, caller.envId(),
                    caller.otelTraceId(), null));
            PermissionResult result = permissionEvaluator.canOperate(memberId, resource, action, record);
            return new PermissionExplanation(memberId, resource, action, null, recordPid,
                    result.granted(), result.steps());
        } finally {
            MetaContext.restore(caller);
        }
    }
}

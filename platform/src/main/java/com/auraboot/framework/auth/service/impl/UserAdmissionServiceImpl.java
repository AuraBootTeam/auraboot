package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.service.UserAdmissionService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserAdmissionServiceImpl implements UserAdmissionService {

    private final SystemModeService systemModeService;
    private final TenantMemberService tenantMemberService;
    private final TenantService tenantService;

    @Override
    public void assertSelfRegistrationAllowed() {
        if (!systemModeService.isRegistrationAllowed()) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Self-registration is disabled");
        }
    }

    @Override
    @Transactional
    public TenantMember admitSelfRegisteredUser(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("Persisted user is required for admission");
        }
        if (!systemModeService.isSingleTenant()) {
            return null;
        }

        Long tenantId = systemModeService.getDefaultTenantId();
        Tenant tenant = tenantId != null && tenantId > 0 ? tenantService.getById(tenantId) : null;
        if (tenant == null || "System".equalsIgnoreCase(tenant.getName())) {
            throw new IllegalStateException("SINGLE mode default business tenant is not configured");
        }

        TenantMember existing = tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
        if (existing != null) {
            return existing;
        }
        return tenantMemberService.addMember(user.getId(), tenantId, StatusConstants.ACTIVE);
    }
}

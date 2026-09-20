package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.TokenRenewResponse;
import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.service.SessionRenewalService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.tenant.service.TenantMemberService;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionRenewalServiceImpl implements SessionRenewalService {

    private final SessionManagementService sessionManagementService;
    private final JwtUtil jwtUtil;
    private final UserService userService;
    private final TenantService tenantService;
    private final TenantMemberService tenantMemberService;


    @Override
    public TokenRenewResponse renew(String bearerToken, String ipAddress, String userAgent) {
        UserSession session = sessionManagementService.findByToken(bearerToken);
        if (session == null || Boolean.TRUE.equals(session.getRevoked())) {
            log.warn("Session renewal rejected: session missing or revoked");
            throw new BusinessException(ResponseCode.Unauthorized, "Session is no longer valid, please login again");
        }

        String userPid = jwtUtil.extractIdentifier(bearerToken);
        User user = userPid == null ? null : userService.findByPid(userPid);
        if (user == null || !user.isEnabled()) {
            log.warn("Session renewal rejected: user {} no longer usable", userPid);
            throw new BusinessException(ResponseCode.Unauthorized, "User is no longer active, please login again");
        }

        Long tenantId = jwtUtil.extractTenantId(bearerToken);
        if (tenantId != null) {
            var tenant = tenantService.getById(tenantId);
            var member = tenantMemberService.findByTenantIdAndUserId(tenantId, user.getId());
            if (tenant == null || "suspended".equalsIgnoreCase(tenant.getStatus()) || member == null
                    || !"active".equalsIgnoreCase(member.getStatus())) {
                throw new BusinessException(ResponseCode.Unauthorized, "Tenant membership is no longer active");
            }
        }
        int currentVersion = user.getSecurityVersion() == null ? 0 : user.getSecurityVersion();
        if (jwtUtil.extractSecurityVersion(bearerToken) != currentVersion) {
            throw new BusinessException(ResponseCode.Unauthorized, "Session security version changed");
        }
        // Keep the same server-side session. Revoking it invalidates every token
        // from this login, including tokens held by concurrent browser tabs.
        String renewed;
        try {
            renewed = jwtUtil.renewSessionToken(bearerToken, session.getPid());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.Unauthorized, "Session has expired, please login again");
        }
        Long expiresAt = jwtUtil.extractExpiration(renewed).toInstant().getEpochSecond();
        log.info("Session renewed for user {}", userPid);
        return new TokenRenewResponse(renewed, expiresAt);
    }
}

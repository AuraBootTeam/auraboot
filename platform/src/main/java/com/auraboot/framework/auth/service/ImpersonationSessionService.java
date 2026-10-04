package com.auraboot.framework.auth.service;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.auth.constant.ExecutionScope;
import com.auraboot.framework.auth.constant.SessionStage;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.dto.ImpersonationSessionRequest;
import com.auraboot.framework.auth.dto.ImpersonationSessionResponse;
import com.auraboot.framework.auth.dto.ImpersonationAuditRecordResponse;
import com.auraboot.framework.auth.dto.SessionTokenContext;
import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.mapper.UserSessionMapper;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ImpersonationSessionService {

    private final TenantMemberService tenantMemberService;
    private final UserService userService;
    private final UserDetailsService userDetailsService;
    private final JwtUtil jwtUtil;
    private final SessionManagementService sessionManagementService;
    private final AdminRoleChecker adminRoleChecker;
    private final UserPermissionService userPermissionService;
    private final AdminEventLogService adminEventLogService;
    private final ObjectMapper objectMapper;
    private final UserSessionMapper userSessionMapper;

    @Value("${security.impersonation.ttl-seconds:1800}")
    private long ttlSeconds;

    @Transactional
    public ImpersonationSessionResponse start(
            ImpersonationSessionRequest request,
            String ipAddress,
            String userAgent) {
        Long tenantId = requireTenant();
        Long operatorUserId = MetaContext.getCurrentUserId();
        User operator = userService.findByUserId(operatorUserId);
        if (MetaContext.isImpersonating()) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Nested impersonation is not allowed");
        }

        TenantMember member = tenantMemberService.findByPid(request.targetMemberPid().trim());
        if (member == null || !tenantId.equals(member.getTenantId())
                || Boolean.TRUE.equals(member.getDeletedFlag())
                || !"active".equalsIgnoreCase(member.getStatus())) {
            throw new BusinessException(ResponseCode.NOT_FOUND,
                    "Target member is not active in the current tenant");
        }
        if (operatorUserId.equals(member.getUserId())) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Cannot impersonate the current account");
        }
        if (adminRoleChecker.hasRole(tenantId, member.getUserId(), RoleCodes.TENANT_ADMIN)
                || adminRoleChecker.hasRole(tenantId, member.getUserId(), RoleCodes.PLATFORM_ADMIN)
                || userPermissionService.hasPermission(
                        tenantId,
                        member.getUserId(),
                        member.getId(),
                        MetaPermission.CUSTOMER_IMPERSONATE)) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "Administrative accounts cannot be impersonated");
        }

        User target = userService.findByUserId(member.getUserId());
        if (target == null || !target.isEnabled() || !target.isAccountNonLocked()
                || !target.isAccountNonExpired() || !target.isCredentialsNonExpired()
                || Boolean.TRUE.equals(target.getDeletedFlag())
                || !"human".equalsIgnoreCase(target.getUserType())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Target user is not available");
        }

        CustomUserDetails targetDetails = (CustomUserDetails)
                userDetailsService.loadUserByUsername(target.getPid());
        String sessionPid = UlidGenerator.generate();
        MetaContext.SessionContext current = MetaContext.getSessionContext();
        SessionTokenContext targetContext = new SessionTokenContext(
                tenantId,
                member.getId(),
                current == null ? null : current.applicationId(),
                current == null ? null : current.loginChannelId(),
                ExecutionScope.TENANT,
                null,
                null,
                SessionStage.READY,
                1,
                target.getSecurityVersion() == null ? 0 : target.getSecurityVersion(),
                true,
                operatorUserId,
                request.clientType());
        String jwt = jwtUtil.generateImpersonationToken(
                targetDetails, target.getPid(), targetContext, sessionPid, ttlSeconds);
        UserSession session = sessionManagementService.createImpersonationSession(
                target.getId(),
                jwt,
                request.authorizationMethod(),
                request.reason().trim(),
                trimToNull(request.reference()),
                ipAddress,
                userAgent);

        adminEventLogService.record(AdminEventLog.builder()
                .tenantId(tenantId)
                .actorUserId(operatorUserId)
                .actionType("impersonation.start")
                .resourceType("user_session")
                .resourcePid(session.getPid())
                .success(true)
                .reason(request.reason().trim())
                .payload(objectMapper.createObjectNode()
                        .put("effectiveUserPid", target.getPid())
                        .put("targetMemberPid", member.getPid())
                        .put("authorizationMethod", request.authorizationMethod())
                        .put("clientType", request.clientType())
                        .put("reference", trimToNull(request.reference())))
                .build());

        return new ImpersonationSessionResponse(
                jwt,
                session.getPid(),
                session.getImpersonationExpiresAt(),
                target.getPid(),
                member.getPid(),
                displayName(target, "Customer"),
                operator == null ? "Administrator" : displayName(operator, "Administrator"),
                request.clientType());
    }

    @Transactional
    public void endCurrent(String bearerToken) {
        UserSession session = sessionManagementService.findByToken(bearerToken);
        if (session == null || !"impersonation".equals(session.getSessionKind())
                || !MetaContext.isImpersonating()) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "Current session is not an impersonation session");
        }
        sessionManagementService.revokeSessionByToken(bearerToken);
        adminEventLogService.record(AdminEventLog.builder()
                .tenantId(MetaContext.getCurrentTenantId())
                .actorUserId(MetaContext.getActualActorUserId())
                .actionType("impersonation.end")
                .resourceType("user_session")
                .resourcePid(session.getPid())
                .success(true)
                .payload(objectMapper.createObjectNode()
                        .put("effectiveUserPid", MetaContext.getCurrentUserPid())
                        .put("clientType", session.getClientType())
                        .put("endedAt", Instant.now().toString()))
                .build());
    }

    /**
     * Returns the immutable session history for one current-tenant customer.
     * The caller is permission-gated by the controller; the tenant membership
     * lookup prevents a PID from being used to inspect another tenant's data.
     */
    @Transactional(readOnly = true)
    public List<ImpersonationAuditRecordResponse> history(String targetMemberPid, int limit) {
        Long tenantId = requireTenant();
        TenantMember member = tenantMemberService.findByPid(targetMemberPid.trim());
        if (member == null || !tenantId.equals(member.getTenantId())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Target member was not found in the current tenant");
        }
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return userSessionMapper.findImpersonationHistory(tenantId, member.getUserId(), boundedLimit).stream()
                .map(session -> new ImpersonationAuditRecordResponse(
                        session.getPid(),
                        operatorDisplayName(session.getInitiatedByUserId()),
                        session.getImpersonationAuthorizationMethod(),
                        session.getImpersonationReason(),
                        session.getImpersonationReference(),
                        session.getClientType(),
                        historyStatus(session),
                        session.getCreatedAt(),
                        session.getImpersonationExpiresAt(),
                        session.getRevokedAt()))
                .toList();
    }

    private Long requireTenant() {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new BusinessException(ResponseCode.CommonValidationFailed,
                    "A tenant context is required");
        }
        return tenantId;
    }

    private static String displayName(User user, String fallback) {
        if (user.getNickName() != null && !user.getNickName().isBlank()) return user.getNickName();
        if (user.getUserName() != null && !user.getUserName().isBlank()) return user.getUserName();
        return fallback;
    }

    private String operatorDisplayName(Long operatorUserId) {
        User operator = operatorUserId == null ? null : userService.findByUserId(operatorUserId);
        return operator == null ? "Administrator" : displayName(operator, "Administrator");
    }

    private static String historyStatus(UserSession session) {
        if (Boolean.TRUE.equals(session.getRevoked())) return "ended";
        if (session.getImpersonationExpiresAt() != null && !session.getImpersonationExpiresAt().isAfter(Instant.now())) {
            return "expired";
        }
        return "active";
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}

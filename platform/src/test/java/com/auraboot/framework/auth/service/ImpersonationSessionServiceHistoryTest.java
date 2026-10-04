package com.auraboot.framework.auth.service;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.mapper.UserSessionMapper;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ImpersonationSessionServiceHistoryTest {

    private final TenantMemberService tenantMemberService = mock(TenantMemberService.class);
    private final UserService userService = mock(UserService.class);
    private final UserSessionMapper userSessionMapper = mock(UserSessionMapper.class);
    private final ImpersonationSessionService service = new ImpersonationSessionService(
            tenantMemberService, userService, mock(UserDetailsService.class), mock(JwtUtil.class),
            mock(SessionManagementService.class), mock(AdminRoleChecker.class), mock(UserPermissionService.class),
            mock(AdminEventLogService.class), new ObjectMapper(), userSessionMapper);

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void historyIsTenantScopedAndProjectsOnlySafeAuditFields() {
        MetaContext.setContext(7L, 1L, "operator", "operator");
        TenantMember member = new TenantMember();
        member.setTenantId(7L);
        member.setUserId(22L);
        when(tenantMemberService.findByPid("customer-member")).thenReturn(member);

        User operator = new User();
        operator.setNickName("Support Alice");
        when(userService.findByUserId(1L)).thenReturn(operator);
        UserSession session = new UserSession();
        session.setPid("session-1");
        session.setInitiatedByUserId(1L);
        session.setImpersonationAuthorizationMethod("offline");
        session.setImpersonationReason("Customer requested checkout help");
        session.setImpersonationReference("store visit");
        session.setClientType("web");
        session.setCreatedAt(Instant.parse("2026-09-24T01:00:00Z"));
        session.setImpersonationExpiresAt(Instant.now().plusSeconds(600));
        session.setRevoked(false);
        when(userSessionMapper.findImpersonationHistory(7L, 22L, 100)).thenReturn(List.of(session));

        var result = service.history("customer-member", 500);

        assertThat(result).singleElement().satisfies(record -> {
            assertThat(record.operatorDisplayName()).isEqualTo("Support Alice");
            assertThat(record.status()).isEqualTo("active");
            assertThat(record.reason()).isEqualTo("Customer requested checkout help");
        });
        verify(userSessionMapper).findImpersonationHistory(7L, 22L, 100);
    }
}

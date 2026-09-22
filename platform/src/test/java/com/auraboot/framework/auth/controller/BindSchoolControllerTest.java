package com.auraboot.framework.auth.controller;

import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantInviteService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BindSchoolControllerTest {
    @Mock TenantInviteService tenantInviteService;
    @Mock TenantMemberService tenantMemberService;
    @Mock UserRoleService userRoleService;
    @Mock UserService userService;
    @Mock SessionManagementService sessionManagementService;
    @Mock JwtUtil jwtUtil;
    @InjectMocks BindSchoolController controller;

    @BeforeEach
    void setUp() {
        Invitation invite = new Invitation();
        invite.setTenantId(22L);
        invite.setStatus("active");
        when(tenantInviteService.findByInvitationCode("SCHOOL-CODE")).thenReturn(invite);
        when(jwtUtil.extractIdentifier("old-token")).thenReturn("user-pid");
        User user = new User();
        user.setId(11L);
        user.setPid("user-pid");
        user.setSecurityVersion(3);
        when(userService.findByPid("user-pid")).thenReturn(user);
        TenantMember member = new TenantMember();
        member.setId(33L);
        when(tenantMemberService.findByTenantIdAndUserId(22L, 11L)).thenReturn(member);
        when(jwtUtil.generateTokenWithTenantId(any(CustomUserDetails.class), eq("user-pid"), eq(22L), eq(33L), eq(3)))
                .thenReturn("scoped-token");
    }

    @Test
    void persistsTenantSessionBeforeReturningJwt() {
        var response = controller.bindSchool("Bearer old-token", new BindSchoolController.BindSchoolRequest("SCHOOL-CODE", null));
        assertEquals("scoped-token", response.getData().get("jwt"));
        verify(sessionManagementService).createSession(11L, "scoped-token", null, "wechat-mini-bind-school");
        ArgumentCaptor<CustomUserDetails> details = ArgumentCaptor.forClass(CustomUserDetails.class);
        verify(jwtUtil).generateTokenWithTenantId(details.capture(), eq("user-pid"), eq(22L), eq(33L), eq(3));
        assertEquals("user-pid", details.getValue().getUserPid());
    }

    @Test
    void sessionPersistenceFailureNeverReturnsJwt() {
        doThrow(new IllegalStateException("session store unavailable"))
                .when(sessionManagementService).createSession(11L, "scoped-token", null, "wechat-mini-bind-school");
        assertThrows(IllegalStateException.class,
                () -> controller.bindSchool("Bearer old-token", new BindSchoolController.BindSchoolRequest("SCHOOL-CODE", null)));
    }
}

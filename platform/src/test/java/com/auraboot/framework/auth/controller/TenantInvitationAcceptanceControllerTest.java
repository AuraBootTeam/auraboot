package com.auraboot.framework.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.dto.SessionTokenContext;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantInviteService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.user.service.UserApplicationPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantInvitationAcceptanceControllerTest {
    @Mock TenantInviteService tenantInviteService;
    @Mock TenantMemberService tenantMemberService;
    @Mock UserRoleService userRoleService;
    @Mock UserService userService;
    @Mock SessionManagementService sessionManagementService;
    @Mock JwtUtil jwtUtil;
    @Mock TenantService tenantService;
    @Mock UserApplicationPreferenceService userApplicationPreferenceService;
    @InjectMocks TenantInvitationAcceptanceController controller;

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
        user.setNickName("已有展示名");
        user.setSecurityVersion(3);
        when(userService.findByPid("user-pid")).thenReturn(user);
        TenantMember member = new TenantMember();
        member.setId(33L);
        lenient().when(tenantMemberService.findByTenantIdAndUserId(22L, 11L)).thenReturn(member);
        lenient().when(jwtUtil.generateTokenWithContext(any(CustomUserDetails.class), eq("user-pid"), any()))
                .thenReturn("new-token");
        lenient().when(jwtUtil.inheritSessionLifetime("new-token", "old-token")).thenReturn("scoped-token");
    }

    @Test
    void persistsTenantSessionBeforeReturningJwt() {
        var response = controller.acceptInvitation("Bearer old-token", new TenantInvitationAcceptanceController.InvitationAcceptanceRequest("SCHOOL-CODE", null));
        assertEquals("scoped-token", response.getData().get("jwt"));
        verify(sessionManagementService).createSession(11L, "scoped-token", null, "tenant-invitation-acceptance");
        ArgumentCaptor<CustomUserDetails> details = ArgumentCaptor.forClass(CustomUserDetails.class);
        verify(jwtUtil).generateTokenWithContext(details.capture(), eq("user-pid"), any());
        assertEquals("user-pid", details.getValue().getUserPid());
        verify(sessionManagementService).revokeSessionByToken("old-token");
    }

    @Test
    void sessionPersistenceFailureNeverReturnsJwt() {
        doThrow(new IllegalStateException("session store unavailable"))
                .when(sessionManagementService).createSession(11L, "scoped-token", null, "tenant-invitation-acceptance");
        assertThrows(IllegalStateException.class,
                () -> controller.acceptInvitation("Bearer old-token", new TenantInvitationAcceptanceController.InvitationAcceptanceRequest("SCHOOL-CODE", null)));
    }

    @Test
    void displayNameUpdatesNonUniqueProfileWithoutChangingLoginUsername() {
        User user = userService.findByPid("user-pid");
        user.setNickName("微信用户");
        user.setUserName(null);

        controller.acceptInvitation("Bearer old-token", new TenantInvitationAcceptanceController.InvitationAcceptanceRequest("SCHOOL-CODE", "高"));

        assertEquals("高", user.getNickName());
        assertNull(user.getUserName(), "a display name must not become a globally unique login username");
        verify(userService).update(user);

        controller.acceptInvitation("Bearer old-token", new TenantInvitationAcceptanceController.InvitationAcceptanceRequest("SCHOOL-CODE", "高"));
        verify(userService, times(1)).update(user);
    }

    @Test
    void existingCustomDisplayNameIsNotOverwrittenOnAnotherTenantJoin() {
        User user = userService.findByPid("user-pid");
        user.setNickName("已有展示名");
        controller.acceptInvitation("Bearer old-token", new TenantInvitationAcceptanceController.InvitationAcceptanceRequest("SCHOOL-CODE", "另一姓名"));
        assertEquals("已有展示名", user.getNickName());
        verify(userService, never()).update(any(User.class));
    }

    @Test
    void firstTenantJoinRequiresDisplayNameBeforeCreatingMembership() {
        User user = userService.findByPid("user-pid");
        user.setNickName("微信用户");
        assertThrows(com.auraboot.framework.exception.BusinessException.class,
                () -> controller.acceptInvitation("Bearer old-token", new TenantInvitationAcceptanceController.InvitationAcceptanceRequest("SCHOOL-CODE", " ")));
        verify(tenantMemberService, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test
    void acceptsLegacyRealNamePayloadAndRoute() throws Exception {
        var request = new ObjectMapper().readValue(
                "{\"inviteCode\":\"SCHOOL-CODE\",\"realName\":\"高老师\"}",
                TenantInvitationAcceptanceController.InvitationAcceptanceRequest.class);
        assertEquals("高老师", request.displayName());
        assertEquals("scoped-token", controller.acceptInvitation("Bearer old-token", request)
                .getData().get("jwt"));

        PostMapping mapping = TenantInvitationAcceptanceController.class
                .getDeclaredMethod("acceptInvitation", String.class,
                        TenantInvitationAcceptanceController.InvitationAcceptanceRequest.class)
                .getAnnotation(PostMapping.class);
        assertEquals(Set.of("/invitations/accept", "/bind-school"), Set.of(mapping.value()));
    }

    @Test
    void joinPreservesApplicationContextAndRemembersNewSchool() {
        when(jwtUtil.extractApplicationId("old-token")).thenReturn(8L);
        when(jwtUtil.extractLoginChannelId("old-token")).thenReturn(9L);
        Tenant tenant = new Tenant();
        tenant.setPid("school-public-pid");
        tenant.setDisplayName("向阳小学");
        when(tenantService.getById(22L)).thenReturn(tenant);

        controller.acceptInvitation("Bearer old-token",
                new TenantInvitationAcceptanceController.InvitationAcceptanceRequest("SCHOOL-CODE", null));

        ArgumentCaptor<SessionTokenContext> context = ArgumentCaptor.forClass(SessionTokenContext.class);
        verify(jwtUtil).generateTokenWithContext(any(CustomUserDetails.class), eq("user-pid"), context.capture());
        assertEquals(8L, context.getValue().applicationId());
        assertEquals(9L, context.getValue().loginChannelId());
        verify(userApplicationPreferenceService).setLastTenant(11L, 8L, "school-public-pid");
    }
}

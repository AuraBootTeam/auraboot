package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.FederatedLoginContext;
import com.auraboot.framework.auth.dto.WechatInvitationJoinRequest;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantInviteService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WechatInvitationJoinServiceTest {

    @Mock WechatMiniClient wechatMiniClient;
    @Mock WechatMiniIdentityService wechatMiniIdentityService;
    @Mock UserService userService;
    @Mock TenantInviteService tenantInviteService;
    @Mock TenantMemberService tenantMemberService;
    @Mock UserRoleService userRoleService;
    @Mock LoginCompletionHelper loginCompletionHelper;
    @InjectMocks WechatInvitationJoinService service;

    @Test
    void assignsOnlyServerSideInvitationRoles() {
        WechatInvitationJoinRequest request = new WechatInvitationJoinRequest();
        request.setCode("wx-code");
        request.setInviteCode("INVITE-CODE");

        Invitation invitation = new Invitation();
        invitation.setTenantId(22L);
        invitation.setInviterUserId(7L);
        invitation.setStatus("active");
        invitation.setRoleCodes("member, reviewer,member");
        when(tenantInviteService.findByInvitationCode("INVITE-CODE")).thenReturn(invitation);

        WechatMiniClient.WxSession wxSession = new WechatMiniClient.WxSession("openid", null);
        when(wechatMiniClient.code2Session("wx-code")).thenReturn(wxSession);
        User user = new User();
        user.setId(11L);
        when(wechatMiniIdentityService.resolveLoginUserBySession(wxSession)).thenReturn(user);
        TenantMember member = new TenantMember();
        member.setPid("member-pid");
        when(tenantMemberService.findByTenantIdAndUserId(22L, 11L)).thenReturn(member);
        when(userRoleService.assignRolesToMemberByRoleCodes(eq("member-pid"), any(), eq(22L), eq(7L))).thenReturn(true);

        AuthenticationResponse expected = new AuthenticationResponse("jwt", 11L, "user-pid", "user", 22L, "member");
        when(loginCompletionHelper.completeLogin(eq(user), any(FederatedLoginContext.class), eq("127.0.0.1"), eq("test")))
                .thenReturn(expected);

        AuthenticationResponse actual = service.join(request, "127.0.0.1", "test");

        assertSame(expected, actual);
        verify(userRoleService).assignRolesToMemberByRoleCodes("member-pid", List.of("member"), 22L, 7L);
        verify(userRoleService).assignRolesToMemberByRoleCodes("member-pid", List.of("reviewer"), 22L, 7L);
    }
}

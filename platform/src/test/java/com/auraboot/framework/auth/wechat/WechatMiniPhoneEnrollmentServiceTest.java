package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.*;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class WechatMiniPhoneEnrollmentServiceTest {
    final WechatMiniClient client=mock(WechatMiniClient.class);
    final WechatMiniProperties props=new WechatMiniProperties();
    final WechatMiniIdentityService identities=mock(WechatMiniIdentityService.class);
    final UserService users=mock(UserService.class);
    final TenantMemberService members=mock(TenantMemberService.class);
    final UserRoleService roles=mock(UserRoleService.class);
    final LoginCompletionHelper completion=mock(LoginCompletionHelper.class);
    final AuraPluginManager plugins=mock(AuraPluginManager.class);
    final DynamicDataService dynamic=mock(DynamicDataService.class);
    final MiniPhoneEnrollmentExtension extension=mock(MiniPhoneEnrollmentExtension.class);
    final WechatMiniPhoneEnrollmentService service=new WechatMiniPhoneEnrollmentService(client,props,identities,users,members,roles,completion,plugins,dynamic);
    final AuthStrategyRequest request=new AuthStrategyRequest();
    final WechatMiniClient.WxSession session=new WechatMiniClient.WxSession("verified-openid",null);
    final User user=new User();
    final TenantMember member=new TenantMember();
    @BeforeEach void setup(){
        props.setAppId("test-app");ReflectionTestUtils.setField(service,"enrollmentCode","supplier-srm");
        ReflectionTestUtils.setField(service,"tenantId",22L);ReflectionTestUtils.setField(service,"roleCode","supplier");
        request.setCode("login-code");request.setPhoneCode("phone-code");request.setEnrollmentCode("supplier-srm");
        user.setId(7L);user.setPid("user-pid");member.setPid("member-pid");member.setStatus("active");
        when(plugins.getExtensions(MiniPhoneEnrollmentExtension.class)).thenReturn(List.of(extension));
        when(extension.displayName(anyString(),any())).thenReturn("Directory Contact");
        when(extension.getEnrollmentCode()).thenReturn("supplier-srm");when(extension.getWriterCommandCode()).thenReturn("membership:bind");
        when(client.code2Session("login-code")).thenReturn(session);
        when(client.verifyPhoneGrant("phone-code","verified-openid")).thenReturn(new WechatMiniClient.VerifiedPhone("test-app","verified-openid","86","13700004884",1L));
    }
    @AfterEach void clear(){MetaContext.clear();}
    void memberAndRole(){when(members.findByTenantIdAndUserId(22L,7L)).thenReturn(member);when(roles.assignRolesToMemberByRoleCodes("member-pid",List.of("supplier"),22L,7L)).thenReturn(true);}
    @Test void firstLoginCreatesAccountMemberAndSupplierBeforeIssuingToken(){
        when(users.signUp(anyString(),anyString(),eq("Directory Contact"),isNull())).thenReturn(user);
        when(members.addMember(7L,22L,"active")).thenReturn(member);
        when(roles.assignRolesToMemberByRoleCodes("member-pid",List.of("supplier"),22L,7L)).thenReturn(true);
        var expected=new AuthenticationResponse("jwt",7L,"user-pid","supplier",22L,"member");
        when(completion.completeLogin(eq(user),any(FederatedLoginContext.class),isNull(),isNull())).thenReturn(expected);
        assertSame(expected,service.login(request));
        var order=inOrder(extension,users,identities,members,roles,completion);
        order.verify(extension).validate(eq("+8613700004884"),any(DataAccessor.class));
        order.verify(identities).resolveLoginUserBySession(session);
        order.verify(users).signUp(anyString(),anyString(),eq("Directory Contact"),isNull());
        order.verify(identities).attachIdentity(7L,session);
        order.verify(members).findByTenantIdAndUserId(22L,7L);
        order.verify(members).addMember(7L,22L,"active");
        order.verify(roles).assignRolesToMemberByRoleCodes("member-pid",List.of("supplier"),22L,7L);
        order.verify(extension).enroll(eq(22L),eq("user-pid"),eq("+8613700004884"),any());
        order.verify(completion).completeLogin(eq(user),any(FederatedLoginContext.class),isNull(),isNull());
        assertFalse(MetaContext.exists());
    }
    @Test void returningUserDoesNotCreateAnotherAccountOrMember(){
        when(identities.resolveLoginUserBySession(session)).thenReturn(user);memberAndRole();
        service.login(request);verifyNoInteractions(users);verify(members,never()).addMember(anyLong(),anyLong(),anyString());
        verify(extension).enroll(eq(22L),eq("user-pid"),eq("+8613700004884"),any());
    }
    @Test void directoryRejectionCreatesNoAccountOrSessionAndRestoresContext(){
        MetaContext.setContext(9L,8L,"old","old");
        doThrow(new SecurityException("PHONE_NOT_REGISTERED")).when(extension).validate(anyString(),any());
        assertThrows(com.auraboot.framework.exception.BusinessException.class,()->service.login(request));verifyNoInteractions(users,identities,members,roles,completion);
        assertEquals(9L,MetaContext.getCurrentTenantId());assertEquals(8L,MetaContext.getCurrentUserId());
    }
    @Test void mismatchedProviderGrantNeverTouchesDirectory(){
        when(client.verifyPhoneGrant(anyString(),anyString())).thenReturn(new WechatMiniClient.VerifiedPhone("other-app","verified-openid","86","13700004884",1));
        assertThrows(RuntimeException.class,()->service.login(request));verify(extension,never()).validate(anyString(),any());verifyNoInteractions(users,identities,members);
    }
    @Test void missingCodeAndPrivilegedRoleAreRejectedBeforeProvider(){
        request.setPhoneCode(null);assertThrows(RuntimeException.class,()->service.login(request));
        request.setPhoneCode("phone-code");ReflectionTestUtils.setField(service,"roleCode","admin");
        assertThrows(RuntimeException.class,()->service.login(request));verifyNoInteractions(client,users,identities);
    }
    @Test void inactiveMembershipAndRoleFailureCannotIssueSession(){
        when(identities.resolveLoginUserBySession(session)).thenReturn(user);when(members.findByTenantIdAndUserId(22L,7L)).thenReturn(member);
        member.setStatus("inactive");assertThrows(RuntimeException.class,()->service.login(request));verifyNoInteractions(roles,completion);
        member.setStatus("active");assertThrows(RuntimeException.class,()->service.login(request));verifyNoInteractions(completion);
    }
    @Test void supplierAssociationFailureNeverIssuesToken(){
        when(identities.resolveLoginUserBySession(session)).thenReturn(user);memberAndRole();
        doThrow(new SecurityException("MEMBERSHIP_CONFLICT")).when(extension).enroll(anyLong(),anyString(),anyString(),any());
        assertThrows(com.auraboot.framework.exception.BusinessException.class,()->service.login(request));verifyNoInteractions(completion);
    }
}

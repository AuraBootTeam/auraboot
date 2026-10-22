package com.auraboot.framework.auth.wechat;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.*;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.MiniPhoneEnrollmentExtension;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.DynamicDataAccessorImpl;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Opt-in directory enrollment; existing mini clients retain their separate login policy. */
@Service
@RequiredArgsConstructor
public class WechatMiniPhoneEnrollmentService {
    private final WechatMiniClient client;
    private final WechatMiniProperties miniProperties;
    private final WechatMiniIdentityService identities;
    private final UserService users;
    private final TenantMemberService members;
    private final UserRoleService roles;
    private final LoginCompletionHelper completion;
    private final AuraPluginManager plugins;
    private final DynamicDataService dynamic;
    @Value("${aura.wechat.mini.enrollment.code:}") private String enrollmentCode;
    @Value("${aura.wechat.mini.enrollment.tenant-id:0}") private long tenantId;
    @Value("${aura.wechat.mini.enrollment.role-code:}") private String roleCode;

    public boolean isConfigured() { return enrollmentCode!=null && !enrollmentCode.isBlank(); }

    @Transactional
    public AuthenticationResponse login(AuthStrategyRequest request) {
        if(enrollmentCode==null || enrollmentCode.isBlank() || !enrollmentCode.equals(request.getEnrollmentCode())
                || tenantId<=0 || roleCode==null || roleCode.isBlank()
                || List.of("admin","tenant_admin","super_admin").contains(roleCode)) throw rejected();
        if(request.getCode()==null || request.getCode().isBlank() || request.getPhoneCode()==null || request.getPhoneCode().isBlank()) throw rejected();
        var matches=plugins.getExtensions(MiniPhoneEnrollmentExtension.class).stream()
                .filter(extension->enrollmentCode.equals(extension.getEnrollmentCode())).toList();
        if(matches.size()!=1) throw rejected();
        var extension=matches.getFirst();
        var session=client.code2Session(request.getCode());
        var grant=client.verifyPhoneGrant(request.getPhoneCode(),session.openid());
        if(grant==null || !miniProperties.getAppId().equals(grant.appId()) || !session.openid().equals(grant.openid())
                || grant.countryCode()==null || !grant.countryCode().matches("[1-9][0-9]{0,3}")
                || grant.nationalNumber()==null || !grant.nationalNumber().matches("[0-9]{4,14}")
                || grant.countryCode().length()+grant.nationalNumber().length()>15) throw rejected();
        String phone="+"+grant.countryCode()+grant.nationalNumber();
        var prior=MetaContext.snapshot();
        try {
            MetaContext.setContext(tenantId,-1L,null,"system");
            return MetaContext.runWithCommandPermitScope("ALL",()->MetaContext.runWithAuthorizedCommandCode(extension.getWriterCommandCode(),()->{
                var data=new DynamicDataAccessorImpl(dynamic);
                extension.validate(phone,data);
                var user=identities.resolveLoginUserBySession(session);
                if(user==null) {
                    String name=extension.displayName(phone,data);
                    if(name==null || name.isBlank() || name.length()>128) throw rejected();
                    user=users.signUp("wx-"+UUID.randomUUID()+"@wx.wechat","Wx!8a"+UUID.randomUUID(),name.trim(),null);
                    identities.attachIdentity(user.getId(),session);
                }
                if(!user.isEnabled() || !user.isAccountNonLocked()) throw rejected();
                var member=members.findByTenantIdAndUserId(tenantId,user.getId());
                if(member==null) member=members.addMember(user.getId(),tenantId,"active");
                if(!"active".equalsIgnoreCase(member.getStatus()) || member.getPid()==null) throw rejected();
                if(!roles.assignRolesToMemberByRoleCodes(member.getPid(),List.of(roleCode),tenantId,user.getId())) throw rejected();
                extension.enroll(tenantId,user.getPid(),phone,data);
                var federated=new FederatedLoginContext();
                federated.setApplicationCode("business-mobile");federated.setLoginChannelCode("default-business-mobile");federated.setTenantId(tenantId);
                return completion.completeLogin(user,federated,request.getIpAddress(),request.getUserAgent());
            }));
        } catch(SecurityException admissionRejected) { throw rejected(); } finally { if(prior==null) MetaContext.clear(); else MetaContext.restore(prior); }
    }
    private static BusinessException rejected() {
        return new BusinessException(ResponseCode.CommonValidationFailed,"$i18n:auth.phone_enrollment.rejected");
    }
}

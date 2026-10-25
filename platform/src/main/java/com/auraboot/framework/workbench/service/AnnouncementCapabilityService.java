package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Current tenant/actor capabilities are advisory; every command still reauthorizes its intent. */
@Service
@RequiredArgsConstructor
public class AnnouncementCapabilityService {
    private final AnnouncementAccessService access;
    private final UserPermissionService permissions;
    private final AnnouncementTenantPolicyService policies;
    private final AnnouncementApprovalCatalog catalog;
    private final WorkflowCapabilityRegistry workflows;
    private final MultipartProperties uploads;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    @Value("${aura.notification.plugin-delivery.enabled:false}") private boolean deliveryEnabled;

    @Transactional(readOnly=true)
    public Map<String,Object> capabilities() {
        Long actor=access.actor("publish");var policy=policies.current();
        boolean submit=permissions.hasPermission(actor,"announcement.announcement.submit");
        boolean exempt=permissions.hasPermission(actor,"announcement.announcement.exempt");
        return Map.of("policyRevision",policy.revision(),"forceApproval",policy.forceApproval(),
            "modes",List.of(mode("exempt",deliveryEnabled && exempt && !policy.forceApproval(),
                    !deliveryEnabled ? "DELIVERY_DISABLED" : !exempt ? "PERMISSION_REQUIRED" : policy.forceApproval() ? "APPROVAL_REQUIRED" : "READY"),
                mode("approval",deliveryEnabled && submit && catalog.available(),
                    !deliveryEnabled ? "DELIVERY_DISABLED" : !submit ? "PERMISSION_REQUIRED" : !catalog.available() ? "APPROVAL_PROVIDER_UNAVAILABLE" : "READY")),
            "allTenantAllowed",permissions.hasPermission(actor,"announcement.announcement.publish_all"),
            "channels",List.of(mode("in_app",deliveryEnabled,deliveryEnabled ? "READY" : "DELIVERY_DISABLED"),
                mode("wechat_mini",false,"CHANNEL_NOT_CONFIGURED")),
            "attachments",Map.of("maxCount",AnnouncementPolicyService.MAX_ATTACHMENTS,
                "maxFileBytes",uploads.getMaxFileSize().toBytes(),"maxRequestBytes",uploads.getMaxRequestSize().toBytes(),
                "uploadAllowed",uploads.isEnabled() && permissions.hasPermission(actor,MetaPermission.SYS_FILE_UPLOAD)));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> approvalReadiness() {
        access.actor("publish");access.actor("submit");
        if(!catalog.available()) return unavailable("APPROVAL_PROVIDER_UNAVAILABLE");
        String definition=policies.current().defaultDefinitionPid();
        if(definition==null || definition.isBlank()) return unavailable("APPROVAL_DEFAULT_DEFINITION_REQUIRED");
        var result=workflows.execute(ApprovalContract.READINESS,new WorkflowCapability.WorkflowRequest(
            MetaContext.getCurrentTenantId(),MetaContext.getCurrentUserId(),Map.of("businessType","announcement","definitionPid",definition))).payload();
        if(Boolean.FALSE.equals(result.get("ready"))) {
            Object reason=result.get("reasonCode");
            if(!(reason instanceof String text) || !text.matches("APPROVAL_[A-Z_]{1,80}"))
                throw new IllegalStateException("Approval readiness reason is invalid");
            return unavailable(text);
        }
        var ref=json.convertValue(result.get("definition"),ApprovalContract.DefinitionRef.class);
        if(!Boolean.TRUE.equals(result.get("ready")) || ref==null
            || !definition.equals(ref.pid()) || !(result.get("definitionName") instanceof String name) || name.isBlank())
            throw new IllegalStateException("Approval readiness identity is invalid");
        return Map.of("ready",true,"reasonCode","READY","definitionPid",ref.pid(),"definitionVersion",ref.version(),"definitionName",name);
    }
    private static Map<String,Object> mode(String code,boolean ready,String reason) {
        return Map.of("code",code,"ready",ready,"reasonCode",reason);
    }
    private static Map<String,Object> unavailable(String reason) {return Map.of("ready",false,"reasonCode",reason);}
}

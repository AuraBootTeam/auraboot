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
        Map<String,Object> readiness=deliveryEnabled && submit ? defaultReadiness() : unavailable("PERMISSION_REQUIRED");
        return Map.of("policyRevision",policy.revision(),"forceApproval",policy.forceApproval(),
            "modes",List.of(mode("exempt",deliveryEnabled && exempt && !policy.forceApproval(),
                    !deliveryEnabled ? "DELIVERY_DISABLED" : !exempt ? "PERMISSION_REQUIRED" : policy.forceApproval() ? "APPROVAL_REQUIRED" : "READY"),
                mode("approval",deliveryEnabled && submit && Boolean.TRUE.equals(readiness.get("ready")),
                    !deliveryEnabled ? "DELIVERY_DISABLED" : !submit ? "PERMISSION_REQUIRED" : (String)readiness.get("reasonCode"))),
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
        return defaultReadiness();
    }

    private Map<String,Object> defaultReadiness() {
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
    @Transactional(readOnly=true)
    public List<Map<String,Object>> modeOptions() {
        var view=capabilities();
        @SuppressWarnings("unchecked") var modes=(List<Map<String,Object>>)view.get("modes");
        return modes.stream().map(item->{
            String code=(String)item.get("code"),reason=(String)item.get("reasonCode");
            var label=modeLabel(code,reason);
            return Map.<String,Object>of("value",code,"label",label,"disabled",!Boolean.TRUE.equals(item.get("ready")));
        }).toList();
    }

    @Transactional(readOnly=true)
    public List<AnnouncementAudienceService.Option> defaultDefinitionOptions() {
        Long actor=access.actor("publish");
        var readiness=permissions.hasPermission(actor,"announcement.announcement.submit")
            ? defaultReadiness() : unavailable("PERMISSION_REQUIRED");
        String name=Boolean.TRUE.equals(readiness.get("ready")) ? (String)readiness.get("definitionName") : null;
        var label=name==null ? reasonLabel((String)readiness.get("reasonCode"))
            : Map.of("zh-CN","租户默认流程："+name,"en-US","Tenant default: "+name);
        return List.of(new AnnouncementAudienceService.Option("tenant_default",label));
    }

    private static Map<String,String> modeLabel(String code,String reason) {
        String zh="exempt".equals(code) ? "免审发布" : "审批后发布";
        String en="exempt".equals(code) ? "Exempt publication" : "Publish after approval";
        if("READY".equals(reason)) return Map.of("zh-CN",zh,"en-US",en);
        var detail=reasonLabel(reason);
        return Map.of("zh-CN",zh+"（"+detail.get("zh-CN")+"）","en-US",en+" ("+detail.get("en-US")+")");
    }

    private static Map<String,String> reasonLabel(String reason) {
        return switch(reason) {
            case "DELIVERY_DISABLED" -> Map.of("zh-CN","通知投递未启用","en-US","Delivery is disabled");
            case "PERMISSION_REQUIRED" -> Map.of("zh-CN","需要相应权限","en-US","Permission required");
            case "APPROVAL_REQUIRED" -> Map.of("zh-CN","租户要求审批","en-US","Tenant requires approval");
            case "APPROVAL_PROVIDER_UNAVAILABLE" -> Map.of("zh-CN","未接入可用审批服务","en-US","Approval service unavailable");
            case "APPROVAL_DEFAULT_DEFINITION_REQUIRED" -> Map.of("zh-CN","请管理员配置默认流程","en-US","Administrator must configure a default workflow");
            default -> Map.of("zh-CN","默认审批流程当前不可用","en-US","Default approval workflow unavailable");
        };
    }

    private static Map<String,Object> mode(String code,boolean ready,String reason) {
        return Map.of("code",code,"ready",ready,"reasonCode",reason);
    }
    private static Map<String,Object> unavailable(String reason) {return Map.of("ready",false,"reasonCode",reason);}
}

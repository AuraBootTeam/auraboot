package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.plugin.extension.approval.*;
import com.auraboot.framework.workbench.mapper.AnnouncementApprovalMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** This business owner exposes persisted bindings; a binding read never authorizes publication. */
@Component
@RequiredArgsConstructor
public class AnnouncementApprovalSource implements ApprovalSubmissionSource {
    private final AnnouncementApprovalMapper store;
    private final AnnouncementAccessService access;
    private final IdentityDirectoryAccessor identities;
    private final AnnouncementApprovalBindings bindings;
    private final com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry workflow;
    private final com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper records;
    private final com.auraboot.framework.workbench.mapper.AnnouncementEstimateMapper estimates;
    private final AnnouncementCodec codec;
    public String businessType(){return "announcement";}

    public Optional<ApprovalContract.SubmittedApproval> findSubmitted(long tenant,long actor,String submission) {
        if(!Objects.equals(MetaContext.getCurrentTenantId(),tenant) || !Objects.equals(MetaContext.getCurrentUserId(),actor))
            throw new SecurityException("Announcement approval binding requires the trusted context");
        var row=store.read(tenant,submission);if(row==null) return Optional.empty();
        boolean hostRead=actor==0L && Set.of("integration-runtime","announcement-approval-worker").contains(MetaContext.getCurrentUserPid());
        // The host receipt/worker identity can read only this owner's existing mapping. No client flag or title is authority.
        if(!hostRead) {
            var membership=identities.findEnabledTenantMembership(tenant,MetaContext.getCurrentUserPid());
            if(membership==null) throw new org.springframework.security.access.AccessDeniedException("Active announcement membership required");
            if(!Objects.equals(row.get("actor_user_id"),actor)) {
                boolean withdrawing=Objects.equals(row.get("withdraw_actor_user_id"),actor) && "withdraw_requested".equals(row.get("state"));
                access.manage((String)row.get("announcement_pid"),withdrawing ? "withdraw" : "read",false);
            }
        }
        return Optional.of(bindings.read(row));
    }

    @Transactional
    public boolean canStart(long tenant,long actor,String submission) {
        var row=store.read(tenant,submission);if(row==null || !Objects.equals(row.get("actor_user_id"),actor)) return false;
        // Taking the business root lock serializes first dispatch with cancellation before any provider request is inserted.
        var record=access.manage((String)row.get("announcement_pid"),"submit",true);access.actor("publish");
        row=store.lock(tenant,submission);
        var member=identities.findEnabledTenantMembership(tenant,MetaContext.getCurrentUserPid());
        return member!=null && member.memberPid().equals(row.get("actor_member_pid")) && "pending_dispatch".equals(row.get("state"))
            && "pending_approval".equals(record.getStatus()) && row.get("version_pid").equals(record.getCurrentVersionPid());
    }
    public boolean canWithdraw(long tenant,long actor,String submission) {
        var row=store.read(tenant,submission);if(row==null || actor==0L) return false;
        var record=access.manage((String)row.get("announcement_pid"),"withdraw",false);
        return "pending_approval".equals(record.getStatus()) && row.get("version_pid").equals(record.getCurrentVersionPid());
    }
    public boolean canExecute(long tenant,long actor,String submission) {
        var row=store.read(tenant,submission);
        if(row==null || !Objects.equals(row.get("actor_user_id"),actor) || !Set.of("pending_dispatch","running").contains(row.get("state"))) return false;
        var member=identities.findEnabledTenantMembership(tenant,MetaContext.getCurrentUserPid());
        if(member==null || !member.memberPid().equals(row.get("actor_member_pid"))) return false;
        // This read deliberately does not take the business root after the provider lock.
        var record=access.manage((String)row.get("announcement_pid"),"submit",false);access.actor("publish");
        return "pending_approval".equals(record.getStatus()) && row.get("version_pid").equals(record.getCurrentVersionPid());
    }

    public Optional<ApprovalContract.BusinessPreview> preview(long tenant,long actor,String submission) {
        if(!Objects.equals(MetaContext.getCurrentTenantId(),tenant) || !Objects.equals(MetaContext.getCurrentUserId(),actor))
            throw new SecurityException("Announcement approval preview requires the trusted context");
        var row=store.read(tenant,submission);if(row==null) return Optional.empty();
        var member=identities.findEnabledTenantMembership(tenant,MetaContext.getCurrentUserPid());
        if(member==null) throw new org.springframework.security.access.AccessDeniedException("Active approval membership required");
        var binding=bindings.read(row);
        if(!Objects.equals(row.get("provider_id"),workflow.providerId(ApprovalContract.VIEWER)))
            throw new SecurityException("Approval preview provider differs from the pinned provider");
        var permission=workflow.execute(ApprovalContract.VIEWER,new com.auraboot.framework.plugin.extension.WorkflowCapability.WorkflowRequest(
            tenant,actor,Map.of("submissionId",submission))).payload();
        if(!Boolean.TRUE.equals(permission.get("allowed")) || !binding.snapshotHash().equals(permission.get("snapshotHash")))
            throw new org.springframework.security.access.AccessDeniedException("Approval preview is unavailable to this participant");
        if(Objects.equals(row.get("actor_user_id"),actor) && !member.memberPid().equals(row.get("actor_member_pid")))
            throw new org.springframework.security.access.AccessDeniedException("Applicant membership incarnation changed");
        String snapshot=records.versionSnapshot(tenant,binding.businessRef().versionPid());
        if(snapshot==null) throw new IllegalStateException("Submitted announcement version is missing");
        var frozen=codec.object(snapshot);
        if(!codec.hash(frozen).equals(binding.snapshotHash())) throw new SecurityException("Submitted announcement content hash changed");
        var estimate=estimates.read(tenant,binding.businessRef().versionPid());
        if(estimate==null) throw new IllegalStateException("Submitted audience estimate is missing");
        var values=new LinkedHashMap<String,Object>();values.put("title",frozen.get("title"));values.put("content",frozen.get("content"));
        values.put("estimatedCount",estimate.get("recipient_count"));
        values.put("estimatedAt",AnnouncementDeliveryPlanResolver.instant(estimate.get("resolved_at")).toString());
        values.put("important",Set.of("important","high","urgent").contains(frozen.get("importance")));
        var policy=frozen.get("policy") instanceof Map<?,?> value ? value : Map.of();
        values.put("scheduledAt",policy.get("scheduledAt"));values.put("expiresAt",frozen.get("expiresAt"));
        values.put("acknowledgementRequired",policy.get("acknowledgementRequired"));values.put("acknowledgementDays",policy.get("acknowledgementDays"));
        List<Map<String,Object>> attachments=new ArrayList<>();
        if(policy.get("attachmentMetadata") instanceof List<?> files) for(var item:files) {
            if(!(item instanceof Map<?,?> file) || !(file.get("fileId") instanceof String fileId)) throw new IllegalStateException("Invalid frozen approval attachment");
            attachments.add(Map.of("fileId",fileId,"name",file.get("name"),"size",file.get("size"),"type",file.get("type"),
                "url","/api/announcements/approvals/"+submission+"/attachments/"+fileId));
        }
        values.put("attachments",attachments);
        List<Map<String,Object>> fields=List.of(field("title","公告标题","Title","text"),field("estimatedCount","预计接收人数","Estimated recipients","number"),
            field("estimatedAt","预计名单生成时间","Audience estimated at","datetime"),field("important","重要公告","Important announcement","boolean"),
            field("scheduledAt","计划发布时间","Scheduled publication","datetime"),field("expiresAt","公告有效期","Expires at","datetime"),
            field("acknowledgementRequired","要求确认知悉","Acknowledgement required","boolean"),field("acknowledgementDays","发布后知悉期限（天）","Acknowledgement days after publication","number"),
            field("content","送审正文","Submitted content","richtext"),field("attachments","送审附件","Submitted attachments","fileAttachment"));
        var schema=Map.<String,Object>of("schemaVersion",4,"pageKey","approval_announcement_snapshot_v1","modelCode","ab_announcement","kind","form",
            "blocks",List.of(Map.of("id","submitted_content","blockType","form-section","title",Map.of("zh-CN","冻结的送审内容","en-US","Frozen submission"),"fields",fields)));
        return Optional.of(new ApprovalContract.BusinessPreview(submission,binding.snapshotHash(),schema,values,List.of()));
    }
    private static Map<String,Object> field(String code,String zh,String en,String type) {
        return Map.of("field",code,"label",Map.of("zh-CN",zh,"en-US",en),"component",type,"dataType",type,"readOnly",true,"colSpan",12);
    }
}

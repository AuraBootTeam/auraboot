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
}

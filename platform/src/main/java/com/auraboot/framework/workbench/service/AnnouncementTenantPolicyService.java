package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.workbench.mapper.AnnouncementApprovalMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Missing tenant configuration has explicit product defaults, without startup writes. */
@Service
@RequiredArgsConstructor
public class AnnouncementTenantPolicyService {
    private final AnnouncementApprovalMapper store;
    public record Policy(int revision,boolean forceApproval,String defaultDefinitionPid,int lateWindowMinutes) {}
    public Policy current() {
        var row=store.policy(MetaContext.getCurrentTenantId());
        if(row==null) return new Policy(0,false,null,30);
        return new Policy(((Number)row.get("revision")).intValue(),Boolean.TRUE.equals(row.get("force_approval")),
            (String)row.get("default_definition_pid"),((Number)row.get("late_window_minutes")).intValue());
    }
}

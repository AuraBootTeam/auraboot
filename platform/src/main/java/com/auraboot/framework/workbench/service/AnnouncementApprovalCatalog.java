package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** An absent provider has no selectable approval definitions; submission remains fail-closed. */
@Service
@RequiredArgsConstructor
public class AnnouncementApprovalCatalog {
    private final WorkflowCapabilityRegistry workflows;
    public boolean available(){return ApprovalContract.OPERATIONS.stream().allMatch(workflows::available);}
    public List<AnnouncementAudienceService.Option> choices() {
        if(!available()) return List.of();
        Long tenant=MetaContext.getCurrentTenantId(),actor=MetaContext.getCurrentUserId();
        Object items=workflows.execute("catalog.list",new WorkflowCapability.WorkflowRequest(tenant,actor,Map.of())).payload().get("items");
        if(!(items instanceof List<?> rows))throw new IllegalStateException("Workflow catalog contract is invalid");
        List<AnnouncementAudienceService.Option> choices=new ArrayList<>();
        for(Object value:rows) {
            if(!(value instanceof Map<?,?> row) || !(row.get("pid") instanceof String pid))throw new IllegalStateException("Workflow catalog identity is invalid");
            var readiness=workflows.execute(ApprovalContract.READINESS,new WorkflowCapability.WorkflowRequest(tenant,actor,
                Map.of("businessType","announcement","definitionPid",pid))).payload();
            if(Boolean.TRUE.equals(readiness.get("ready")))choices.add(new AnnouncementAudienceService.Option(pid,Objects.toString(row.get("process_name"),"")));
        }
        return List.copyOf(choices);
    }
}

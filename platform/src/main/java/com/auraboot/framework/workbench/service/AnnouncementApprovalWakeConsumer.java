package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract;
import com.auraboot.framework.plugin.extension.integration.*;
import com.auraboot.framework.workbench.mapper.AnnouncementApprovalMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

/** Terminal delivery is a wake-up hint. Only authoritative provider status can create a release authorization. */
@Component
@RequiredArgsConstructor
public class AnnouncementApprovalWakeConsumer implements ReliableEventConsumerExtension {
    private final AnnouncementApprovalMapper store;
    private final AnnouncementApprovalService approvals;
    private final ObjectMapper json;
    public String consumerCode(){return "core.announcement.approval-terminal.v1";}
    public Set<String> subscribedEventTypes(){return Set.of(ApprovalContract.TERMINAL_EVENT);}
    public void consume(IntegrationEventEnvelope event) {
        if(!Objects.equals(MetaContext.getCurrentTenantId(),event.tenantId())) throw new SecurityException("Approval wake-up tenant differs");
        var result=json.convertValue(event.payload(),ApprovalContract.TerminalResult.class);
        // Other applications own their own submissions and consume the same neutral event independently.
        if(!"announcement".equals(result.businessRef().type())) return;
        var row=store.read(event.tenantId(),result.submissionId());
        if(row==null) throw new SecurityException("Approval terminal has no persisted announcement submission");
        approvals.verify(approvals.binding(row),row,result);
        store.wake(event.tenantId(),result.submissionId());
    }
}

package com.auraboot.framework.workbench.service;

import com.auraboot.framework.plugin.extension.approval.ApprovalContract.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Shared immutable binding checks for dispatch, terminal reconciliation and publication. */
@Service
@RequiredArgsConstructor
public class AnnouncementApprovalBindings {
    private final ObjectMapper json;
    private final AnnouncementCodec codec;
    public SubmittedApproval read(Map<String,Object> row) {
        SubmittedApproval binding;
        try {binding=json.readValue((String)row.get("binding_json"),SubmittedApproval.class);}
        catch(JsonProcessingException failure){throw new IllegalStateException("Invalid announcement approval binding",failure);}
        if(!codec.hash(binding).equals(row.get("binding_hash")) || !binding.submissionId().equals(row.get("pid"))
            || !"announcement".equals(binding.businessRef().type()) || !binding.businessRef().recordPid().equals(row.get("announcement_pid"))
            || !binding.businessRef().versionPid().equals(row.get("version_pid")) || !binding.applicantUserPid().equals(row.get("applicant_pid")))
            throw new SecurityException("Announcement approval binding differs from its persisted owner");
        return binding;
    }
    public void verify(SubmittedApproval binding,Map<String,Object> row,TerminalResult result) {
        long revision=row.get("provider_revision") instanceof Number n ? n.longValue() : 0L;
        if(result==null || !binding.submissionId().equals(result.submissionId()) || !binding.businessRef().equals(result.businessRef())
            || !binding.snapshotHash().equals(result.snapshotHash()) || !binding.definition().equals(result.definition())
            || (row.get("instance_pid")!=null && !Objects.equals(row.get("instance_pid"),result.instancePid()))
            || result.stateRevision()<revision) throw new SecurityException("Approval terminal differs from the frozen business binding");
    }
}

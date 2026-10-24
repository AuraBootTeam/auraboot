package com.auraboot.framework.workbench.service;

import com.auraboot.framework.plugin.extension.approval.ApprovalContract;
import com.auraboot.framework.workbench.mapper.AnnouncementApprovalMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** A published approval basis must reference the exact immutable result accepted by the business owner. */
@Service
@RequiredArgsConstructor
public class AnnouncementApprovalProofService {
    private final AnnouncementApprovalMapper store;
    private final ObjectMapper json;
    private final AnnouncementApprovalBindings bindings;
    public void requireValid(Long tenant,String submission,String version,String snapshotHash) {
        var row=store.read(tenant,submission);
        if(row==null || !version.equals(row.get("version_pid")) || !"approved".equals(row.get("state")) || row.get("result_json")==null)
            throw new SecurityException("Announcement has no explicit approved result");
        try {
            var binding=bindings.read(row);
            var result=json.readValue((String)row.get("result_json"),ApprovalContract.TerminalResult.class);
            bindings.verify(binding,row,result);
            if(!binding.businessRef().versionPid().equals(version) || !binding.snapshotHash().equals(snapshotHash)
                || !binding.submissionId().equals(result.submissionId()) || !binding.businessRef().equals(result.businessRef())
                || !binding.snapshotHash().equals(result.snapshotHash()) || !binding.definition().equals(result.definition())
                || !"approved".equals(result.terminalState()) || !Objects.equals(row.get("terminal_result_pid"),result.resultId()))
                throw new SecurityException("Announcement approved result binding differs");
        } catch(JsonProcessingException failure) {throw new IllegalStateException("Invalid persisted approval proof",failure);}
    }
}

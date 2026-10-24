package com.auraboot.framework.workbench.service;

import com.auraboot.framework.plugin.extension.approval.ApprovalContract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class AnnouncementApprovalBindingsTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final AnnouncementCodec codec=new AnnouncementCodec(json);
    private final AnnouncementApprovalBindings verifier=new AnnouncementApprovalBindings(json,codec);
    private final SubmittedApproval binding=new SubmittedApproval("submission","operation",new BusinessRef("announcement","notice","version"),
        "announcement:version","a".repeat(64),"announcement.v1",new DefinitionRef("definition",3,"b".repeat(64)),StrictPolicy.required(),"author","Policy",Instant.parse("2030-01-01T00:00:00Z"));
    private Map<String,Object> row(){
        Map<String,Object> row=new HashMap<>();row.put("pid","submission");row.put("announcement_pid","notice");row.put("version_pid","version");
        row.put("applicant_pid","author");row.put("binding_json",codec.encode(binding));row.put("binding_hash",codec.hash(binding));
        row.put("instance_pid","instance");row.put("provider_revision",2L);return row;
    }
    private TerminalResult result(String version,String hash,DefinitionRef definition,String instance,long revision){
        return new TerminalResult("submission",new BusinessRef("announcement","notice",version),hash,instance,definition,"approved","result",revision,Instant.parse("2030-01-02T00:00:00Z"),"HUMAN_QUORUM_APPROVED");
    }
    @Test void exactFrozenBindingAndExplicitResultAreAccepted(){
        assertThat(verifier.read(row())).isEqualTo(binding);
        verifier.verify(binding,row(),result("version",binding.snapshotHash(),binding.definition(),"instance",3));
    }
    @Test void aPublicSubmissionIdCannotBeReboundToAnotherBusinessOwner(){
        for(String field:List.of("pid","announcement_pid","version_pid","applicant_pid","binding_hash")) {
            var row=row();row.put(field,"different");assertThatThrownBy(()->verifier.read(row)).isInstanceOf(SecurityException.class);
        }
    }
    @Test void versionSnapshotDefinitionInstanceAndRevisionAreAllIndependentFences(){
        var results=List.of(result("other",binding.snapshotHash(),binding.definition(),"instance",3),
            result("version","c".repeat(64),binding.definition(),"instance",3),
            result("version",binding.snapshotHash(),new DefinitionRef("definition",4,"d".repeat(64)),"instance",3),
            result("version",binding.snapshotHash(),binding.definition(),"other-instance",3),
            result("version",binding.snapshotHash(),binding.definition(),"instance",1));
        for(var result:results) assertThatThrownBy(()->verifier.verify(binding,row(),result)).isInstanceOf(SecurityException.class);
    }
    @Test void incidentalEngineCompletionIsNotAnApprovalTerminal(){
        assertThatThrownBy(()->new TerminalResult("submission",binding.businessRef(),binding.snapshotHash(),"instance",binding.definition(),"completed","result",3,Instant.now(),"PROCESS_ENDED"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void withdrawalBeforeEngineStartHasAnExplicitNullableInstanceContract(){
        assertThat(new TerminalResult("submission",binding.businessRef(),binding.snapshotHash(),null,binding.definition(),"withdrawn","result",1,Instant.now(),"APPLICANT_WITHDRAWN_BEFORE_START").instancePid()).isNull();
        assertThatThrownBy(()->new TerminalResult("submission",binding.businessRef(),binding.snapshotHash(),null,binding.definition(),"approved","result",1,Instant.now(),"HUMAN_QUORUM_APPROVED"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}

package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelPublishWorkflowReplaySupportTest {
    private final WorkflowCapabilityRegistry registry=mock(WorkflowCapabilityRegistry.class);
    private final ModelPublishWorkflowReplaySupport adapter=new ModelPublishWorkflowReplaySupport(registry,new ObjectMapper());
    @BeforeEach void context() { MetaContext.setContext(7L,11L,null,"test");when(registry.available("model-publish.replay")).thenReturn(true); }
    @AfterEach void clear() { MetaContext.clear(); }
    private ModelPublishReplayStepDTO step(String type) { return ModelPublishReplayStepDTO.builder().consumerType(type).sourcePid("source-7").sourceCode("process-7").metadata(Map.of("nodeId","task-7")).build(); }
    private WorkflowCapability.WorkflowResult result(String status,boolean executed) {
        return new WorkflowCapability.WorkflowResult(Map.of("status",status,"automated",true,"executed",executed,
                "matched",false,"message","provider result","outputs",Map.of("failClosed",true),"errors",List.of("BPM_RULE_BINDING_FAIL_CLOSED")));
    }
    @Test void missingCapabilityIsExplicitlyUnavailable() {
        when(registry.available("model-publish.replay")).thenReturn(false);
        var r=adapter.replay(step("WORKFLOW_PROCESS"),null);
        assertThat(r.getStatus()).isEqualTo("BPM_UNAVAILABLE");assertThat(r.getExecuted()).isFalse();
        verify(registry,never()).execute(any(),any());
    }
    @Test void dispatchPreservesTenantActorAndSample() {
        var request=new MetaModelPublishReplayRequest();request.setExecuteAutomated(true);
        request.setSampleContext(Map.of("record",Map.of("data",Map.of("amount",2400))));
        when(registry.execute(eq("model-publish.replay"),any())).thenAnswer(call->{
            WorkflowCapability.WorkflowRequest outgoing=call.getArgument(1);
            assertThat(outgoing.tenantId()).isEqualTo(7L);assertThat(outgoing.actorUserId()).isEqualTo(11L);
            assertThat(((Map<?,?>)outgoing.payload().get("step")).get("consumerType")).isEqualTo("SLA_RULE");
            assertThat(((Map<?,?>)outgoing.payload().get("request")).get("sampleContext")).isEqualTo(request.getSampleContext());
            return result("EXECUTED",true);
        });
        var source=step("SLA_RULE");var r=adapter.replay(source,request);
        assertThat(r.getStep()).isSameAs(source);assertThat(r.getStatus()).isEqualTo("EXECUTED");assertThat(r.getExecuted()).isTrue();
    }
    @Test void providerFailClosedRemainsFailed() {
        when(registry.execute(any(),any())).thenReturn(result("FAILED",false));
        var r=adapter.replay(step("WORKFLOW_PROCESS"),null);
        assertThat(r.getStatus()).isEqualTo("FAILED");assertThat(r.getExecuted()).isFalse();
        assertThat(r.getErrors()).containsExactly("BPM_RULE_BINDING_FAIL_CLOSED");assertThat(r.getOutputs()).containsEntry("failClosed",true);
    }
    @Test void contradictoryProviderCannotReportSuccess() {
        when(registry.execute(any(),any())).thenReturn(result("EXECUTED",false));
        var r=adapter.replay(step("WORKFLOW_PROCESS"),null);
        assertThat(r.getStatus()).isEqualTo("FAILED");assertThat(r.getExecuted()).isFalse();
        assertThat(r.getErrors()).containsExactly("WORKFLOW_REPLAY_FAILED");
    }
    @Test void providerExceptionIsAFailedConsumer() {
        when(registry.execute(any(),any())).thenThrow(new IllegalStateException("provider unavailable"));
        var r=adapter.replay(step("SLA_RULE"),null);
        assertThat(r.getStatus()).isEqualTo("FAILED");assertThat(r.getExecuted()).isFalse();
    }
    @Test void governanceActuallyInvokesProductPortForBothConsumerTypes() {
        var governance=new ModelPublishGovernanceSupport(null,null,null,null,null,null,null,null,null,adapter,null);
        when(registry.execute(any(),any())).thenReturn(result("EXECUTED",true));
        for(String type:List.of("WORKFLOW_PROCESS","SLA_RULE")) {
            var r=governance.replayPublishStep(null,step(type),null);
            assertThat(r.getStatus()).isEqualTo("EXECUTED");assertThat(r.getExecuted()).isTrue();
        }
        verify(registry,times(2)).execute(eq("model-publish.replay"),any());
    }
}

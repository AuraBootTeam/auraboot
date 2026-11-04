package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.RecordActionScopeAccessor;
import com.auraboot.framework.plugin.pf4j.CommandRecordActionScopeAccessorFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IdempotencyCachedReadTest {
    static final String PID="01M5Z215Q89MM18DCZ00000001";
    static Map<String,Object> params() {return Map.of("invocationAuthorization","handler-with-result-reads",
            "cachedResultReadReferences",List.of(Map.of("model","fixture_plan","path","readonlyPlan.planPid")));}
    static CommandPipelineContext context(Map<String,Object> parameters) {
        var request=new CommandExecuteRequest();request.setClientRequestId("original-request");request.setTargetRecordPid(PID);request.setExpectedVersion(1);
        return CommandPipelineContext.builder().commandCode("fixture:freeze").request(request).tenantId(7L).userId(42L)
                .execConfig(Map.of("handlerParams",parameters)).build();
    }
    static IdempotencyPhase phase(IdempotencyService service,RecordActionScopeAccessor reads,CommandRecordActionScopeAccessorFactory.InvocationScope scope) {
        var phase=new IdempotencyPhase(service);var factory=mock(CommandRecordActionScopeAccessorFactory.class);
        when(factory.forInvocation(any())).thenReturn(scope);when(scope.accessor()).thenReturn(reads);
        ReflectionTestUtils.setField(phase,"recordScopeFactory",factory);return phase;
    }
    static IdempotencyService cached(Map<String,Object> result) {
        var service=mock(IdempotencyService.class);when(service.claimScopedIdempotency(anyString(),anyString(),anyMap(),eq(7L))).thenReturn(result);return service;
    }
    @Test void cachedPlanIsCheckedBeforeThePipelineCanShortCircuit() {
        var result=Map.<String,Object>of("readonlyPlan",Map.of("planPid",PID));var service=cached(result);
        var reads=mock(RecordActionScopeAccessor.class);when(reads.permits("fixture_plan",PID,"read")).thenReturn(true);
        var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);var phase=phase(service,reads,scope);var c=context(params());
        phase.execute(c);assertThat(c.getShortCircuitResult().getData()).isEqualTo(result);assertThat(c.getShortCircuitResult().isIdempotentReplay()).isTrue();
        verify(reads).permits("fixture_plan",PID,"read");verify(scope).close();
    }
    @Test void revocationRejectsTheExistingCachedOutcomeWithoutReplacingIt() {
        var service=cached(Map.of("readonlyPlan",Map.of("planPid",PID)));var reads=mock(RecordActionScopeAccessor.class);
        var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);var phase=phase(service,reads,scope);var c=context(params());
        assertThatThrownBy(()->phase.execute(c)).isInstanceOf(AccessDeniedException.class);assertThat(c.getShortCircuitResult()).isNull();verify(scope).close();
        verify(service).claimScopedIdempotency(eq("original-request"),eq("fixture:freeze"),anyMap(),eq(7L));verifyNoMoreInteractions(service);
    }
    @Test void malformedReferencesAndMissingHostCapabilityNeverReturnCachedData() {
        for(Map<String,Object> result:List.of(Map.<String,Object>of(),Map.<String,Object>of("readonlyPlan",Map.of("planPid",17)),Map.<String,Object>of("readonlyPlan",Map.of("planPid","bad")))) {
            var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);var reads=mock(RecordActionScopeAccessor.class);var c=context(params());
            assertThatThrownBy(()->phase(cached(result),reads,scope).execute(c)).isInstanceOf(AccessDeniedException.class);
            assertThat(c.getShortCircuitResult()).isNull();verifyNoInteractions(reads);verify(scope).close();
        }
        var c=context(params());assertThatThrownBy(()->new IdempotencyPhase(cached(Map.of("readonlyPlan",Map.of("planPid",PID)))).execute(c)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void strictDeclarationCannotSilentlyDowngradeAndLegacyCommandsKeepTheirBehavior() {
        for(Map<String,Object> invalid:List.of(Map.<String,Object>of("invocationAuthorization","handler-with-result-reads"),
                Map.<String,Object>of("invocationAuthorization","handler","cachedResultReadReferences",List.of(Map.of("model","fixture_plan","path","readonlyPlan.planPid"))),
                Map.<String,Object>of("invocationAuthorization","handler-with-result-reads","cachedResultReadReferences",List.of(Map.of("model","fixture_plan","path","readonlyPlan[*].planPid"))))) {
            var service=mock(IdempotencyService.class);assertThatThrownBy(()->new IdempotencyPhase(service).execute(context(invalid))).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(service);
        }
        var result=Map.<String,Object>of("oldResult",true);var c=context(Map.of());new IdempotencyPhase(cached(result)).execute(c);assertThat(c.getShortCircuitResult().getData()).isEqualTo(result);
    }
    @Test void aCacheMissStillRunsTheOriginalWriterInsteadOfFabricatingAResult() {
        var c=context(params());var service=cached(null);var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);
        phase(service,mock(RecordActionScopeAccessor.class),scope).execute(c);assertThat(c.getShortCircuitResult()).isNull();
        verifyNoInteractions(scope);
    }
    static Map<String,Object> collectionParams() {
        return Map.of("invocationAuthorization","handler-with-result-reads","cachedResultReadReferences",List.of(
                Map.of("model","fixture_task","path","run.taskPids","many",true,"maxItems",1000,"countPath","run.nodeCount")));
    }
    @Test void boundedCompleteCollectionRechecksEveryCurrentTaskAndRejectsAnyRevocation() {
        String other="01M5Z215Q89MM18DCZ00000002";
        var result=Map.<String,Object>of("run",Map.of("taskPids",List.of(PID,other),"nodeCount",2));
        var reads=mock(RecordActionScopeAccessor.class);when(reads.permits("fixture_task",PID,"read")).thenReturn(true);
        when(reads.permits("fixture_task",other,"read")).thenReturn(true);
        var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);var phase=phase(cached(result),reads,scope);
        var first=context(collectionParams());phase.execute(first);assertThat(first.getShortCircuitResult().getData()).isEqualTo(result);
        when(reads.permits("fixture_task",other,"read")).thenReturn(false);
        var revoked=context(collectionParams());assertThatThrownBy(()->phase.execute(revoked)).isInstanceOf(AccessDeniedException.class);
        assertThat(revoked.getShortCircuitResult()).isNull();verify(reads,times(2)).permits("fixture_task",other,"read");
    }
    @Test void collectionRejectsMissingLastTaskDuplicatesWrongTypesAndEmptySetsBeforePermissionChecks() {
        for(Object value:List.of(List.of(PID),List.of(PID,PID),List.of(),List.of(PID,17),List.of(List.of(PID)),"[\""+PID+"\"]")) {
            var result=Map.<String,Object>of("run",Map.of("taskPids",value,"nodeCount",2));
            var reads=mock(RecordActionScopeAccessor.class);var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);
            var c=context(collectionParams());assertThatThrownBy(()->phase(cached(result),reads,scope).execute(c)).isInstanceOf(AccessDeniedException.class);
            assertThat(c.getShortCircuitResult()).isNull();verifyNoInteractions(reads);
        }
    }
    @Test void malformedCollectionDeclarationsFailBeforeClaimingAnyRequest() {
        var valid=new java.util.LinkedHashMap<String,Object>(Map.of("model","fixture_task","path","run.taskPids","many",true,"maxItems",1000,"countPath","run.nodeCount"));
        for(String variant:List.of("count","max","shape","wildcard","duplicate")) {
            var declaration=new java.util.LinkedHashMap<>(valid);
            switch(variant){case "count" -> declaration.remove("countPath");case "max" -> declaration.put("maxItems",1001);case "shape" -> declaration.put("many",false);case "duplicate" -> declaration.put("maxItems",999);default -> declaration.put("path","run.tasks[*].pid");}
            var descriptors=variant.equals("duplicate")?List.of(valid,declaration):List.of(declaration);
            var parameters=Map.<String,Object>of("invocationAuthorization","handler-with-result-reads","cachedResultReadReferences",descriptors);
            var service=mock(IdempotencyService.class);var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);
            assertThatThrownBy(()->phase(service,mock(RecordActionScopeAccessor.class),scope).execute(context(parameters))).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(service);
        }
    }
    @Test void collectionCountMustBeExactPositiveIntegerAndCannotExceedDeclaredBound() {
        for(Object count:List.of(0,-1,1,2.5,"2",1001)) {
            var result=Map.<String,Object>of("run",Map.of("taskPids",List.of(PID,"01M5Z215Q89MM18DCZ00000002"),"nodeCount",count));
            var reads=mock(RecordActionScopeAccessor.class);var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);
            var c=context(collectionParams());assertThatThrownBy(()->phase(cached(result),reads,scope).execute(c)).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(reads);
        }
        var ids=java.util.stream.IntStream.range(0,1001).mapToObj(i->PID.substring(0,PID.length()-8)+String.format("%08d",i)).toList();
        var reads=mock(RecordActionScopeAccessor.class);var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);
        assertThatThrownBy(()->phase(cached(Map.of("run",Map.of("taskPids",ids,"nodeCount",1001))),reads,scope).execute(context(collectionParams()))).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(reads);
    }
    @Test void aggregateCollectionBudgetIsValidatedBeforeTheFirstPermissionDecision() {
        var refs=new java.util.ArrayList<Map<String,Object>>();var run=new java.util.LinkedHashMap<String,Object>();run.put("count",1000);
        var ids=java.util.stream.IntStream.range(0,1000).mapToObj(i->PID.substring(0,PID.length()-8)+String.format("%08d",i)).toList();
        for(int i=0;i<5;i++){refs.add(Map.of("model","fixture_task","path","run.tasks"+i,"many",true,"maxItems",1000,"countPath","run.count"));run.put("tasks"+i,ids);}
        var parameters=Map.<String,Object>of("invocationAuthorization","handler-with-result-reads","cachedResultReadReferences",refs);
        var reads=mock(RecordActionScopeAccessor.class);var scope=mock(CommandRecordActionScopeAccessorFactory.InvocationScope.class);
        var c=context(parameters);assertThatThrownBy(()->phase(cached(Map.of("run",run)),reads,scope).execute(c)).isInstanceOf(AccessDeniedException.class);
        assertThat(c.getShortCircuitResult()).isNull();verifyNoInteractions(reads);verify(scope).close();
    }
}

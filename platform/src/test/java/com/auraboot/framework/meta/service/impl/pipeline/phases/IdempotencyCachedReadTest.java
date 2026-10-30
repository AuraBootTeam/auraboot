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
}

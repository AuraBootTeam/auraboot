package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.*;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RelatedUpdateCommandDataAccessorTest {
    final DynamicDataService data=mock(DynamicDataService.class);
    final RecordReadAccessor reads=mock(RecordReadAccessor.class);
    final CommandHandlerExtension handler=mock(CommandHandlerExtension.class);
    RelatedUpdateCommandDataAccessor accessor() {
        when(reads.getById("receipt","root")).thenReturn(Map.of("pid","root","tenant_id",1L));
        when(reads.getById("journal","request")).thenReturn(Map.of("pid","request","tenant_id",1L,"row_version",3L));
        return new RelatedUpdateCommandDataAccessor(data,1L,"receipt:submit","receipt","root","actor",handler,reads);
    }
    void command(Runnable body) {
        MetaContext.setContext(1L,2L,"actor","Buyer");
        MetaContext.runWithCommandPermitPlan("ALL",7L,"receipt","root",()->
                MetaContext.runWithAuthorizedCommandCode("receipt:submit",body));
    }
    Map<String,Object> changes(){return Map.of("status","enqueued","_expectedVersion",3L);}
    @AfterEach void clear(){MetaContext.clear();}
    @Test void explicitDecisionPinsNativeUpdateToExactVersionAndRestoresRoot() {
        var bridge=accessor();when(handler.authorizeRelatedUpdate(any())).thenAnswer(i->{
            var c=(CommandHandlerExtension.RelatedUpdateAuthorizationContext)i.getArgument(0);
            assertThat(c.root().recordId()).isEqualTo("root");assertThat(c.root().currentUserPid()).isEqualTo("actor");
            assertThat(c.recordId()).isEqualTo("request");assertThat(c.changes()).isEqualTo(changes());
            assertThatThrownBy(()->c.changes().put("status","confirmed")).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(()->c.record().put("tenant_id",2L)).isInstanceOf(UnsupportedOperationException.class);return true;
        });
        when(data.update(eq("journal"),eq("request"),anyMap())).thenAnswer(i->{
            assertThat(MetaContext.getCommandPermitScopeFor("journal")).isEqualTo("TARGET");
            assertThat(MetaContext.getCommandPermitTargetFor("journal")).isEqualTo("request");
            assertThat(MetaContext.getCommandExpectedVersion("journal","request")).isEqualTo(3L);
            assertThat(MetaContext.getAuthorizedCommandCode()).isEqualTo("receipt:submit");return Map.of("pid","request");
        });
        command(()->{bridge.update("journal","request",changes());assertThat(MetaContext.getCommandPermitScopeFor("receipt")).isEqualTo("ALL");});
        verify(data).update("journal","request",changes());assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }
    @Test void absentOriginalAuthorityNeverLoadsPrivilegedRecords() {
        var bridge=accessor();MetaContext.setContext(1L,2L,"actor","Buyer");
        assertThatThrownBy(()->bridge.update("journal","request",changes())).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(reads,handler,data);
    }
    @Test void changedIdentityOrCommandCannotInvokeHandler() {
        var bridge=accessor();command(()->{
            MetaContext.runWithAuthorizedCommandCode("other:submit",()->assertThatThrownBy(()->bridge.update("journal","request",changes())).isInstanceOf(AccessDeniedException.class));
            MetaContext.runWithCommandPermitPlan("TARGET",7L,"receipt","other",()->assertThatThrownBy(()->bridge.update("journal","request",changes())).isInstanceOf(AccessDeniedException.class));
        });verifyNoInteractions(reads,handler,data);
    }
    @Test void foreignRecordTenantAndPidFailClosed() {
        var bridge=accessor();when(reads.getById("journal","request")).thenReturn(Map.of("pid","request","tenant_id",2L,"row_version",3L));
        command(()->assertThatThrownBy(()->bridge.update("journal","request",changes())).isInstanceOf(AccessDeniedException.class));
        when(reads.getById("journal","request")).thenReturn(Map.of("pid","other","tenant_id",1L,"row_version",3L));
        command(()->assertThatThrownBy(()->bridge.update("journal","request",changes())).isInstanceOf(AccessDeniedException.class));verifyNoInteractions(handler,data);
    }
    @Test void missingStaleAndFractionalVersionsNeverReachWriter() {
        var bridge=accessor();command(()->{
            assertThatThrownBy(()->bridge.update("journal","request",Map.of("status","enqueued"))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->bridge.update("journal","request",Map.of("_expectedVersion",2L))).isInstanceOf(com.auraboot.framework.meta.exception.RecordVersionConflictException.class);
            assertThatThrownBy(()->bridge.update("journal","request",Map.of("_expectedVersion",2.5))).isInstanceOf(ArithmeticException.class);
        });verifyNoInteractions(handler,data);
    }
    @Test void denialAndNativeConflictNeverLeakRelatedScope() {
        var bridge=accessor();command(()->assertThatThrownBy(()->bridge.update("journal","request",changes())).isInstanceOf(AccessDeniedException.class));
        when(handler.authorizeRelatedUpdate(any())).thenReturn(true);var failure=new com.auraboot.framework.exception.ConflictException("Changed");
        when(data.update(eq("journal"),eq("request"),anyMap())).thenThrow(failure);
        command(()->{assertThatThrownBy(()->bridge.update("journal","request",changes())).isSameAs(failure);assertThat(MetaContext.getCommandPermitScopeFor("receipt")).isEqualTo("ALL");});
    }
    @Test void originalSelfScopeMustStillIncludeTheRootRecord() {
        var bridge=accessor();when(reads.getById("receipt","root")).thenReturn(Map.of("pid","root","tenant_id",1L,"created_by",999L));
        command(()->MetaContext.runWithCommandPermitPlan("SELF",7L,"receipt","root",()->
                assertThatThrownBy(()->bridge.update("journal","request",changes())).isInstanceOf(AccessDeniedException.class)));
        verify(reads,never()).getById("journal","request");verifyNoInteractions(handler,data);
    }
    @Test void olderHandlerDeniesByDefault() {
        var old=new CommandHandlerExtension(){public String getCommandType(){return "old";}public Object execute(CommandContext c){return null;}};
        assertThat(old.authorizeRelatedUpdate(null)).isFalse();
    }
}

package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.*;
import java.util.Map;
import java.util.List;
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
    @AfterEach void clear(){MetaContext.clear();org.springframework.transaction.support.TransactionSynchronizationManager.clear();}
    @Test void relatedCasRequiresTransactionAndHandlerAndRetainsNativeVersionAndCompareValue() {
        var bridge=accessor();var claim=new CompareAndSetUpdate("request",8L,Map.of("revision",8L));
        command(()->assertThatThrownBy(()->bridge.compareAndSetBatch("journal","revision",List.of(claim))).isInstanceOf(IllegalStateException.class));
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        command(()->assertThatThrownBy(()->bridge.compareAndSetBatch("journal","revision",List.of(claim))).isInstanceOf(AccessDeniedException.class));
        verifyNoInteractions(data);
        when(handler.authorizeRelatedUpdate(any())).thenAnswer(i->{
            CommandHandlerExtension.RelatedUpdateAuthorizationContext c=i.getArgument(0);
            assertThat(c.changes()).isEqualTo(Map.of("revision",8L,"_expectedVersion",3L));return true;
        });
        doAnswer(i->{
            assertThat(MetaContext.getCommandPermitTargetFor("journal")).isEqualTo("request");
            assertThat(MetaContext.getCommandExpectedVersion("journal","request")).isEqualTo(3L);return null;
        }).when(data).compareAndSetBatch("journal","revision",List.of(claim));
        command(()->{bridge.compareAndSetBatch("journal","revision",List.of(claim));
            assertThat(MetaContext.getCommandPermitTargetFor("receipt")).isEqualTo("root");});
        verify(data).compareAndSetBatch("journal","revision",List.of(claim));
    }
    @Test void relatedCasValidatesEntireBatchBeforeNativeLocksAndRejectsForeignDuplicateOrInjectedVersion() {
        var bridge=accessor();org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        when(handler.authorizeRelatedUpdate(any())).thenReturn(true);
        when(reads.getById("journal","foreign")).thenReturn(Map.of("pid","foreign","tenant_id",2L,"row_version",1L));
        var claim=new CompareAndSetUpdate("request",8L,Map.of("revision",8L));
        command(()->{
            assertThatThrownBy(()->bridge.compareAndSetBatch("journal","revision",List.of(claim,new CompareAndSetUpdate("foreign",1,Map.of("revision",1))))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->bridge.compareAndSetBatch("journal","revision",List.of(claim,claim))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(()->bridge.compareAndSetBatch("journal","revision",List.of(new CompareAndSetUpdate("request",8,Map.of("revision",8,"_expectedVersion",3))))).isInstanceOf(IllegalArgumentException.class);
        });verifyNoInteractions(data);
    }
    @Test void relatedCasUsesStableLockOrderAndRestoresAuthorityAfterNativeConflict() {
        var bridge=accessor();org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        when(reads.getById("journal","a")).thenReturn(Map.of("pid","a","tenant_id",1L,"row_version",4L));
        when(handler.authorizeRelatedUpdate(any())).thenReturn(true);
        var a=new CompareAndSetUpdate("a",1,Map.of("revision",1));
        var z=new CompareAndSetUpdate("request",8,Map.of("revision",8));
        var failure=new com.auraboot.framework.exception.ConflictException("Changed");
        doThrow(failure).when(data).compareAndSetBatch("journal","revision",List.of(z));
        command(()->{
            assertThatThrownBy(()->bridge.compareAndSetBatch("journal","revision",List.of(z,a))).isSameAs(failure);
            assertThat(MetaContext.getCommandPermitTargetFor("receipt")).isEqualTo("root");
            assertThat(MetaContext.getCommandExpectedVersion("receipt","root")).isEqualTo(7L);
        });
        var order=inOrder(data);order.verify(data).compareAndSetBatch("journal","revision",List.of(a));
        order.verify(data).compareAndSetBatch("journal","revision",List.of(z));
    }
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
    @Test void sameModelChildUpdateRetainsItsOwnHandlerDecisionAndRestoresTheRootTarget() {
        var bridge=accessor();when(reads.getById("receipt","child")).thenReturn(Map.of("pid","child","tenant_id",1L,"row_version",3L));
        command(()->assertThatThrownBy(()->bridge.update("receipt","child",changes())).isInstanceOf(AccessDeniedException.class));
        when(handler.authorizeRelatedUpdate(any())).thenReturn(true);
        when(data.update(eq("receipt"),eq("child"),anyMap())).thenAnswer(i->{
            assertThat(MetaContext.getCommandPermitTargetFor("receipt")).isEqualTo("child");
            assertThat(MetaContext.getCommandExpectedVersion("receipt","child")).isEqualTo(3L);return Map.of("pid","child");
        });
        command(()->{bridge.update("receipt","child",changes());assertThat(MetaContext.getCommandPermitTargetFor("receipt")).isEqualTo("root");});
        verify(data).update("receipt","child",changes());
    }
    @Test void explicitCombinedDeleteRequiresTheHandlerAndPinsTheSameModelChild() {
        accessor();var bridge=new RelatedUpdateCommandDataAccessor(data,1L,"receipt:submit","receipt","root","actor",handler,reads,true);
        when(reads.getById("receipt","child")).thenReturn(Map.of("pid","child","tenant_id",1L,"row_version",3L));
        command(()->assertThatThrownBy(()->bridge.delete("receipt","child")).isInstanceOf(AccessDeniedException.class));
        verify(data,never()).delete(anyString(),anyString());
        when(handler.authorizeRelatedDelete(any())).thenAnswer(i->{
            CommandHandlerExtension.RelatedDeleteAuthorizationContext c=i.getArgument(0);
            assertThat(c.root().recordId()).isEqualTo("root");assertThat(c.recordId()).isEqualTo("child");return true;
        });
        doAnswer(i->{assertThat(MetaContext.getCommandPermitTargetFor("receipt")).isEqualTo("child");
            assertThat(MetaContext.getCommandExpectedVersion("receipt","child")).isEqualTo(3L);return null;}).when(data).delete("receipt","child");
        command(()->{bridge.delete("receipt","child");assertThat(MetaContext.getCommandPermitTargetFor("receipt")).isEqualTo("root");});
        verify(data).delete("receipt","child");
    }
    @Test void combinedDeleteRejectsMissingAuthorityForeignRowsAndRestoresAfterNativeFailure() {
        accessor();var bridge=new RelatedUpdateCommandDataAccessor(data,1L,"receipt:submit","receipt","root","actor",handler,reads,true);
        MetaContext.setContext(1L,2L,"actor","Buyer");
        assertThatThrownBy(()->bridge.delete("journal","request")).isInstanceOf(AccessDeniedException.class);
        when(reads.getById("journal","request")).thenReturn(Map.of("pid","request","tenant_id",2L,"row_version",3L));
        command(()->assertThatThrownBy(()->bridge.delete("journal","request")).isInstanceOf(AccessDeniedException.class));
        verifyNoInteractions(handler,data);
        when(reads.getById("journal","request")).thenReturn(Map.of("pid","request","tenant_id",1L,"row_version",3L));
        when(handler.authorizeRelatedDelete(any())).thenReturn(true);var failure=new com.auraboot.framework.exception.ConflictException("Changed");
        doThrow(failure).when(data).delete("journal","request");
        command(()->{assertThatThrownBy(()->bridge.delete("journal","request")).isSameAs(failure);
            assertThat(MetaContext.getCommandPermitTargetFor("receipt")).isEqualTo("root");});
    }
}

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

/** Hermetic checks of the exact-resource authorization and native deletion boundary. */
class RelatedCommandDataAccessorTest {
    private final DynamicDataService nativeData=mock(DynamicDataService.class);
    private final RecordReadAccessor reads=mock(RecordReadAccessor.class);
    private final CommandHandlerExtension handler=mock(CommandHandlerExtension.class);
    private RelatedCommandDataAccessor accessor() {
        when(reads.getById("quote","root")).thenReturn(Map.of("pid","root","tenant_id",1L));
        when(reads.getById("line","child")).thenReturn(Map.of("pid","child","tenant_id",1L,"row_version",3L));
        return new RelatedCommandDataAccessor(nativeData,1L,"quote:save","quote","root","actor",handler,reads);
    }
    private void command(Runnable action) {
        MetaContext.setContext(1L,2L,"actor","Supplier");
        MetaContext.runWithCommandPermitPlan("TARGET",7L,"quote","root",()->
                MetaContext.runWithAuthorizedCommandCode("quote:save",action));
    }
    @AfterEach void clear(){MetaContext.clear();}
    @Test void relatedDeleteUsesItsOwnExactPidAndVersionAndRestoresTheRootPlan() {
        var data=accessor();when(handler.authorizeRelatedDelete(any())).thenAnswer(i->{
            var c=(CommandHandlerExtension.RelatedDeleteAuthorizationContext)i.getArgument(0);
            assertThat(c.root().recordId()).isEqualTo("root");assertThat(c.root().currentUserPid()).isEqualTo("actor");
            assertThat(c.modelCode()).isEqualTo("line");assertThat(c.recordId()).isEqualTo("child");
            assertThatThrownBy(()->c.record().put("tenant_id",2L)).isInstanceOf(UnsupportedOperationException.class);
            return true;
        });
        doAnswer(i->{
            assertThat(MetaContext.getCommandPermitScopeFor("line")).isEqualTo("TARGET");
            assertThat(MetaContext.getCommandPermitTargetFor("line")).isEqualTo("child");
            assertThat(MetaContext.getCommandExpectedVersion("line","child")).isEqualTo(3L);
            assertThat(MetaContext.getCommandPermitScopeFor("quote")).isNull();
            assertThat(MetaContext.getAuthorizedCommandCode()).isEqualTo("quote:save");return null;
        }).when(nativeData).delete("line","child");
        command(()->{data.delete("line","child");
            assertThat(MetaContext.getCommandPermitTargetFor("quote")).isEqualTo("root");
            assertThat(MetaContext.getCommandExpectedVersion("quote","root")).isEqualTo(7L);
        });
        verify(nativeData).delete("line","child");verifyNoMoreInteractions(nativeData);
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }
    @Test void absentRootDecisionNeverLoadsPrivilegedRecordsOrInvokesNativeDelete() {
        var data=accessor();MetaContext.setContext(1L,2L,"actor","Supplier");
        assertThatThrownBy(()->data.delete("line","child")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(reads,handler,nativeData);
    }
    @Test void anotherTenantOrPidCannotReachTheHandlerOrWriter() {
        var data=accessor();when(reads.getById("line","child")).thenReturn(Map.of("pid","child","tenant_id",2L,"row_version",3L));
        command(()->assertThatThrownBy(()->data.delete("line","child")).isInstanceOf(AccessDeniedException.class));
        when(reads.getById("line","child")).thenReturn(Map.of("pid","other","tenant_id",1L,"row_version",3L));
        command(()->assertThatThrownBy(()->data.delete("line","child")).isInstanceOf(AccessDeniedException.class));
        verifyNoInteractions(handler,nativeData);
    }
    @Test void handlerDenialAndMissingVersionFailBeforeNativePersistence() {
        var data=accessor();command(()->assertThatThrownBy(()->data.delete("line","child")).isInstanceOf(AccessDeniedException.class));
        when(handler.authorizeRelatedDelete(any())).thenReturn(true);
        when(reads.getById("line","child")).thenReturn(Map.of("pid","child","tenant_id",1L));
        command(()->assertThatThrownBy(()->data.delete("line","child")).isInstanceOf(IllegalStateException.class));
        verifyNoInteractions(nativeData);
    }
    @Test void nativeConflictStillPropagatesAndCannotLeakTheRelatedScope() {
        var data=accessor();when(handler.authorizeRelatedDelete(any())).thenReturn(true);
        var conflict=new com.auraboot.framework.exception.ConflictException("Concurrent child change");
        doThrow(conflict).when(nativeData).delete("line","child");
        command(()->{assertThatThrownBy(()->data.delete("line","child")).isSameAs(conflict);
            assertThat(MetaContext.getCommandPermitTargetFor("quote")).isEqualTo("root");
            assertThat(MetaContext.getCommandExpectedVersion("quote","root")).isEqualTo(7L);
        });
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }
    @Test void olderHandlerCannotAuthorizeARelatedDeleteByDefault() {
        var old=new CommandHandlerExtension(){public String getCommandType(){return "old:command";}
            public Object execute(CommandContext c){return null;}};
        assertThat(old.authorizeRelatedDelete(null)).isFalse();
    }
}

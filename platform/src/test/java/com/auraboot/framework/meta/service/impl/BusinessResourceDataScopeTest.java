package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.Action;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class BusinessResourceDataScopeTest {
    final AtomicBoolean rejected=new AtomicBoolean();
    void verify(){if(rejected.get())throw new AccessDeniedException("Poisoned original frame");}
    <T> T loan(Action action,String model,String pid,Object input,java.util.function.Supplier<T> body){
        return BusinessResourceDataScope.withLoan(action,model,pid,input,this::verify,()->rejected.set(true),body);
    }
    @AfterEach void clear(){MetaContext.clear();}
    @Test void exactReadIsSingleUseAndCannotReadAnotherRow(){
        assertThatThrownBy(()->loan(Action.READ,"line","a",null,()->{
            BusinessResourceDataScope.read("line","a");BusinessResourceDataScope.read("line","b");return null;
        })).isInstanceOf(AccessDeniedException.class);assertThat(rejected).isTrue();
    }
    @Test void theSameReadCannotBeRepeatedByANestedService(){
        assertThatThrownBy(()->loan(Action.READ,"line","a",null,()->{
            BusinessResourceDataScope.read("line","a");BusinessResourceDataScope.read("line","a");return null;
        })).isInstanceOf(AccessDeniedException.class);
    }
    @Test void nativeUpdateKeepsItsOwnBeforeAndAfterReads(){
        loan(Action.UPDATE,"line","a",Map.of("qty",3),()->{
            BusinessResourceDataScope.update("line","a",Map.of("qty",3));
            BusinessResourceDataScope.read("line","a");BusinessResourceDataScope.read("line","a");return null;
        });assertThat(rejected).isFalse();
    }
    @Test void aSecondMutationCannotBorrowTheFirstMutationPermit(){
        assertThatThrownBy(()->loan(Action.UPDATE,"line","a",Map.of("qty",3),()->{
            BusinessResourceDataScope.update("line","a",Map.of("qty",3));
            BusinessResourceDataScope.update("line","a",Map.of("qty",3));return null;
        })).isInstanceOf(AccessDeniedException.class);
    }
    @Test void nativeCreationMayReadOnlyItsRegisteredGeneratedResult(){
        MetaContext.setContext(7L,1L,"actor","Actor");
        loan(Action.CREATE,"ledger",null,Map.of("qty",3),()->{
            BusinessResourceDataScope.create("ledger",Map.of("qty",3));BusinessResourceDataScope.created("ledger","generated");
            return BusinessResourceDataScope.readback("ledger","generated",()->{
                assertThat(MetaContext.getCommandPermitTargetFor("ledger")).isEqualTo("generated");
                assertThat(MetaContext.getCommandPermitScopeFor("other")).isNull();
                assertThat(BusinessResourceDataScope.matchesReadbackPermit("ledger",null)).isTrue();
                BusinessResourceDataScope.read("ledger","generated");return null;
            });
        });assertThat(rejected).isFalse();
    }
    @Test void creationCannotReadAnArbitraryExistingRow(){
        assertThatThrownBy(()->loan(Action.CREATE,"ledger",null,Map.of("qty",3),()->{
            BusinessResourceDataScope.create("ledger",Map.of("qty",3));BusinessResourceDataScope.created("ledger","generated");
            BusinessResourceDataScope.read("ledger","old");return null;
        })).isInstanceOf(AccessDeniedException.class);
    }
    @Test void payloadOrModelReplacementIsRejectedBeforeNativePersistence(){
        assertThatThrownBy(()->loan(Action.UPDATE,"line","a",Map.of("qty",3),()->{
            BusinessResourceDataScope.update("other","a",Map.of("qty",3));return null;
        })).isInstanceOf(AccessDeniedException.class);
    }
    @Test void customAndBulkApisHaveNoImplicitAuthority(){
        assertThatThrownBy(()->loan(Action.QUERY,"line",null,Map.of("parent","root"),()->{
            BusinessResourceDataScope.unsupported();return null;
        })).isInstanceOf(AccessDeniedException.class);
    }
    @Test void anExplicitNestedOwnerLoanRestoresItsOriginalParent(){
        Object query=new Object();
        loan(Action.QUERY,"line",null,query,()->{
            BusinessResourceDataScope.query("line",query);
            loan(Action.READ,"reference","ref",null,()->{BusinessResourceDataScope.read("reference","ref");return null;});
            return null;
        });assertThat(rejected).isFalse();
    }
    @Test void failedLoanCannotLeakIntoOrdinaryLaterCalls(){
        assertThatThrownBy(()->loan(Action.READ,"line","a",null,()->{BusinessResourceDataScope.read("other","a");return null;}))
                .isInstanceOf(AccessDeniedException.class);
        BusinessResourceDataScope.read("ordinary","later");BusinessResourceDataScope.unsupported();
    }
    @Test void nativeDeleteMayReadOnlyItsOwnSnapshotAndCannotRepeatDelete() {
        assertThatThrownBy(()->loan(Action.DELETE,"line","a",null,()->{
            BusinessResourceDataScope.delete("line","a"); BusinessResourceDataScope.read("line","a");
            BusinessResourceDataScope.delete("line","a"); return null;
        })).isInstanceOf(AccessDeniedException.class); assertThat(rejected).isTrue();
    }
    @Test void nativeDeleteCannotReadOrDeleteAnotherRow() {
        assertThatThrownBy(()->loan(Action.DELETE,"line","a",null,()->{
            BusinessResourceDataScope.delete("line","a"); BusinessResourceDataScope.read("line","b"); return null;
        })).isInstanceOf(AccessDeniedException.class); assertThat(rejected).isTrue();
    }
    @Test void nativeDeleteCannotBorrowAnUpdateLoan() {
        assertThatThrownBy(()->loan(Action.UPDATE,"line","a",Map.of("qty",3),()->{
            BusinessResourceDataScope.delete("line","a"); return null;
        })).isInstanceOf(AccessDeniedException.class);
    }

}

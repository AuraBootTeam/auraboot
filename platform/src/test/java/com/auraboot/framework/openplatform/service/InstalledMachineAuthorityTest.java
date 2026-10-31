package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import org.springframework.security.access.AccessDeniedException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import static org.mockito.Mockito.*;
import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationBinding;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Hermetic regressions with the same machine context used by the HTTP filter. */
class InstalledMachineAuthorityTest {
    final InstalledIntegrationBinding binding=new InstalledIntegrationBinding(1L,"app","inst","source","event","durable",1,Map.of());
    final MachinePrincipal principal=new MachinePrincipal(1L,"inst","Integration",Set.of(),"app","inst",null,null);
    final String config="""
        {"handlerParams":{"installedMachineData":{"ledger":{"read":["pid","name"],"create":["name"],"update":["name"]}}}}
        """;

    @Test void verifiedServerContractSuppliesReadAuthorityAndRestoresTheRequest() {
        try(var machine=MachineDataAccessContext.begin(principal)) {
            InstalledIntegrationExecution.run("accept",config,binding,false,this,()->{
                var grant=MachineDataAccessContext.requireDecision("ledger");
                assertThat(grant.readableFields()).containsExactlyInAnyOrder("pid","name");
                assertThat(grant.actions()).containsExactly("read");
                assertThatThrownBy(()->grant.requireAction("update")).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(()->MachineDataAccessContext.requireDecision("other")).isInstanceOf(AccessDeniedException.class);
                return null;
            });
            assertThatThrownBy(()->MachineDataAccessContext.requireDecision("ledger")).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test void contractCannotAuthorizeADifferentInstallation() {
        try(var machine=MachineDataAccessContext.begin(new MachinePrincipal(1L,"other","Other",Set.of(),"app","other",null,null))) {
            assertThatThrownBy(()->InstalledIntegrationExecution.run("accept",config,binding,false,this,()->null))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test void malformedOrWildcardContractsAreRejectedBeforeWork() {
        for(String invalid:List.of("[]","{\"ledger\":{\"read\":[\"*\"]}}","{\"ledger\":{\"read\":[\"pid\"],\"delete\":[\"pid\"]}}")) {
            String source="{\"handlerParams\":{\"installedMachineData\":"+invalid+"}}";
            try(var machine=MachineDataAccessContext.begin(principal)) {
                assertThatThrownBy(()->InstalledIntegrationExecution.run("accept",source,binding,false,this,()->{
                    throw new AssertionError("Invalid contract executed");
                })).isInstanceOf(AccessDeniedException.class);
            }
        }
    }

    @Test void missingContractGrantsNoDataAndDoesNotReuseOuterDecisions() {
        try(var machine=MachineDataAccessContext.begin(principal);
            var outer=MachineDataAccessContext.authorize(Map.of("other",new MachineDataAccessContext.Decision(1L,"inst","other",Set.of("read"),Set.of("pid"),Set.of(),List.of())))) {
            InstalledIntegrationExecution.run("accept","{}",binding,false,this,()->{
                assertThatThrownBy(()->MachineDataAccessContext.requireDecision("other")).isInstanceOf(AccessDeniedException.class);
                return null;
            });
            assertThat(MachineDataAccessContext.requireDecision("other")).isNotNull();
        }
    }
    void verified(String target,boolean query,java.util.function.Consumer<InstalledMachineDataAccessor> work) {
        MetaContext.setContext(1L,0L,"installation:inst","Integration");
        try(var machine=MachineDataAccessContext.begin(principal)) {
            InstalledIntegrationExecution.run("accept",config,binding,query,this,target,()->{
                InstalledIntegrationExecution.verify("accept",1L,config,Map.of("installedIntegration",Map.of()));
                InstalledIntegrationExecution.verifyHandler("accept",this,List.of());
                return MetaContext.runWithCommandPermitPlan("ALL",7L,"ledger",target,()->
                    MetaContext.runWithAuthorizedCommandCode("accept",()->{
                        work.accept(new InstalledMachineDataAccessor(nativeData));return null;
                    }));
            });
        } finally {MetaContext.clear();}
    }
    final DynamicDataService nativeData=mock(DynamicDataService.class);

    @Test void createIssuesOnlyDeclaredFieldsDuringTheNativeOperation() {
        when(nativeData.create(eq("ledger"),anyMap())).thenAnswer(call->{
            var grant=MachineDataAccessContext.requireDecision("ledger");
            assertThat(grant.actions()).containsExactly("create");
            assertThat(grant.writableFields()).containsExactly("name");
            return Map.of("pid","new","name","value");
        });
        verified(null,false,data->{
            assertThat(data.create("ledger",Map.of("name","value"))).containsEntry("pid","new");
            assertThat(MachineDataAccessContext.requireDecision("ledger").actions()).containsExactly("read");
            assertThatThrownBy(()->data.create("ledger",Map.of("secret","value"))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->data.create("other",Map.of("name","value"))).isInstanceOf(AccessDeniedException.class);
        });
        verify(nativeData,times(1)).create(anyString(),anyMap());
    }

    @Test void updateBindsTheExactNativeTargetAndVersion() {
        when(nativeData.update(eq("ledger"),eq("root"),anyMap())).thenAnswer(call->{
            var grant=MachineDataAccessContext.requireDecision("ledger");
            assertThat(grant.actions()).containsExactly("update");
            assertThat(grant.constraints()).containsExactly(new MachineDataAccessContext.Constraint("pid","pid",List.of("root")));
            assertThat(MachineDataAccessContext.expectedVersion("ledger","root")).isEqualTo(7L);
            return Map.of("pid","root");
        });
        verified("root",false,data->{
            data.update("ledger","root",Map.of("name","new"));
            assertThat(MachineDataAccessContext.expectedVersion("ledger","root")).isNull();
            assertThatThrownBy(()->data.update("ledger","other",Map.of("name","new"))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->data.compareAndSet("ledger","root","secret","old",Map.of("name","new"))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->data.delete("ledger","root")).isInstanceOf(AccessDeniedException.class);
        });
        verify(nativeData,times(1)).update(anyString(),anyString(),anyMap());
    }

    @Test void queryAndEscapedAccessorsCannotMutate() {
        final InstalledMachineDataAccessor[] escaped=new InstalledMachineDataAccessor[1];
        verified(null,true,data->{
            escaped[0]=data;
            assertThatThrownBy(()->data.create("ledger",Map.of("name","new"))).isInstanceOf(AccessDeniedException.class);
        });
        try(var machine=MachineDataAccessContext.begin(principal)) {
            assertThatThrownBy(()->escaped[0].create("ledger",Map.of("name","new"))).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(nativeData);
    }

    @Test void unverifiedHandlerCannotConsumeTheMutationContract() {
        try(var machine=MachineDataAccessContext.begin(principal)) {
            InstalledIntegrationExecution.run("accept",config,binding,false,this,()->{
                assertThatThrownBy(()->new InstalledMachineDataAccessor(nativeData).create("ledger",Map.of("name","new")))
                    .isInstanceOf(AccessDeniedException.class);
                return null;
            });
        }
        verifyNoInteractions(nativeData);
    }

    @Test void onlyManagedBindingsEnableReferenceClosureAndLegacyInvocationCannotInheritIt() {
        String managed=config.replace("\"installedMachineData\":", "\"installedIntegration\":{\"sourceCode\":\"source\",\"bindingLookup\":{\"version\":1}},\"installedMachineData\":");
        try(var machine=MachineDataAccessContext.begin(principal)) {
            InstalledIntegrationExecution.run("accept",managed,binding,false,this,()->{
                Object scope=MachineDataAccessContext.requiredReferenceReadScope();
                assertThat(scope).isNotNull();
                assertThatThrownBy(()->MachineDataAccessContext.requireDecision("undeclared_target")).isInstanceOf(AccessDeniedException.class);
                assertThatThrownBy(()->InstalledIntegrationExecution.run("legacy",config,binding,false,this,()->null))
                        .isExactlyInstanceOf(SecurityException.class).hasMessage("Nested installed integration invocation denied");
                assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isSameAs(scope);
                return null;
            });
            assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isNull();
            var outerGrant=new MachineDataAccessContext.Decision(1L,"inst","outer",Set.of("read"),Set.of("pid"),Set.of(),List.of());
            try(var outer=MachineDataAccessContext.authorizeRequiredReferenceReads(Map.of("outer",outerGrant))) {
                Object outerScope=MachineDataAccessContext.requiredReferenceReadScope();
                InstalledIntegrationExecution.run("legacy",config,binding,false,this,()->{
                    assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isNull();return null;
                });
                assertThat(MachineDataAccessContext.requiredReferenceReadScope()).isSameAs(outerScope);
            }
        }
    }

}

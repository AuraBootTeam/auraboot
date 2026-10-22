package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.*;
import com.auraboot.framework.plugin.extension.integration.InstalledIntegrationBinding;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic host boundary tests; native scope/SQL behavior has separate service regressions. */
class InstalledRelatedWriteAccessorTest {
    final String command="requests:process";
    final DynamicDataService nativeData=mock(DynamicDataService.class);
    final TenantProjectionAccessor reads=mock(TenantProjectionAccessor.class);
    final CommandHandlerExtension handler=mock(CommandHandlerExtension.class);
    final InstalledIntegrationBinding binding=new InstalledIntegrationBinding(1L,"app","inst","source","event","durable",1,Map.of());
    Map<String,Object> settings;
    String sourceConfig="config";

    void process(Consumer<DataAccessor> work) { process(false,"root",handler,true,work); }
    void process(boolean query,String target,CommandHandlerExtension selected,boolean policy,Consumer<DataAccessor> work) {
        MetaContext.setContext(1L,0L,"installation:inst","Integration");
        when(reads.getById("ledger","root")).thenReturn(row("root",1L,7L));
        when(reads.getById("child","line")).thenReturn(row("line",1L,3L));
        InstalledIntegrationExecution.run(command,sourceConfig,binding,query,handler,target,()->{
            InstalledIntegrationExecution.verify(command,1L,sourceConfig,Map.of("installedIntegration",Map.of()));
            InstalledIntegrationExecution.verifyHandler(command,selected,List.of());
            return MetaContext.runWithCommandPermitPlan("ALL",7L,"ledger",target,()->
                MetaContext.runWithAuthorizedCommandCode(command,()->{
                    settings=new HashMap<>();
                    if(policy) settings.put("installedRelatedWriteAuthorization",true);
                    settings.put(CommandHandlerExtension.TENANT_PROJECTION_ACCESSOR_KEY,reads);
                    settings.put(CommandHandlerExtension.DATA_ACCESSOR_KEY,new com.auraboot.framework.plugin.pf4j.DynamicDataAccessorImpl(nativeData));
                    InstalledIntegrationExecution.install(command,1L,settings);
                    InstalledIntegrationExecution.installRelatedWriter(settings,nativeData,selected,"ledger",target);
                    work.accept((DataAccessor)settings.get(CommandHandlerExtension.DATA_ACCESSOR_KEY));
                    return null;
                }));
        });
    }
    static Map<String,Object> row(String pid,Long tenant,Object version) {
        Map<String,Object> result=new HashMap<>(Map.of("pid",pid,"tenant_id",tenant));
        if(version!=null) result.put("row_version",version);
        return result;
    }
    void allow() {when(handler.authorizeInstalledRelatedWrite(any())).thenReturn(true);}
    void childScope() {
        assertThat(MetaContext.getCommandPermitScopeFor("child")).isEqualTo("TARGET");
        assertThat(MetaContext.getCommandPermitTargetFor("child")).isEqualTo("line");
        assertThat(MetaContext.getCommandExpectedVersion("child","line")).isEqualTo(3L);
        assertThat(MetaContext.getCommandPermitScopeFor("ledger")).isNull();
        assertThat(MetaContext.getAuthorizedCommandCode()).isEqualTo(command);
        assertThat(MetaContext.getCurrentUserId()).isZero();
    }
    @AfterEach void clear(){MetaContext.clear();}

    @Test void actualHandlerPhaseInstallsTheBridgeForItsVerifiedProcess() {
        var registry=mock(com.auraboot.framework.plugin.pf4j.ExtensionRegistry.class);
        var metadata=mock(com.auraboot.framework.meta.service.MetaModelService.class);
        var phase=new com.auraboot.framework.meta.service.impl.pipeline.phases.HandlerPhase(
                mock(org.springframework.context.ApplicationContext.class),registry,new com.fasterxml.jackson.databind.ObjectMapper(),
                nativeData,mock(com.auraboot.framework.meta.mapper.DynamicDataMapper.class),metadata,
                mock(com.auraboot.framework.meta.service.impl.pipeline.RecordSnapshotReader.class),
                mock(com.auraboot.framework.meta.service.DataPermissionEngine.class),mock(com.auraboot.framework.meta.service.DataDomainService.class));
        var commandDefinition=new com.auraboot.framework.meta.entity.CommandDefinition();
        commandDefinition.setCode(command);commandDefinition.setModelCode("ledger");
        var request=new com.auraboot.framework.meta.dto.CommandExecuteRequest();
        request.setTargetRecordId("root");request.setPayload(Map.of("requestPid","root"));
        var ctx=com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.builder()
                .commandCode(command).command(commandDefinition).request(request).tenantId(1L).userId(0L)
                .payload(new HashMap<>(request.getPayload())).execConfig(new HashMap<>(Map.of("type","custom","handler",command,
                    "handlerParams",Map.of("installedIntegration",Map.of(),"installedRelatedWriteAuthorization",true,"tenantProjectionRead",true))))
                .rulesByType(new HashMap<>()).fieldMapResults(new HashMap<>()).handlerResults(new HashMap<>()).build();
        when(registry.getCommandHandler(command)).thenReturn(Optional.of(handler));
        when(registry.getSecondaryCommandHandlers(command)).thenReturn(List.of());
        when(metadata.getModelDefinition("ledger")).thenReturn(Optional.empty());
        try {when(handler.execute(any())).thenAnswer(i->{
            var context=(CommandHandlerExtension.CommandContext)i.getArgument(0);
            assertThat(context.dataAccessor()).isInstanceOf(InstalledRelatedWriteAccessor.class);
            assertThat(context.installedIntegration()).isSameAs(binding);
            assertThat(context.currentUserPid()).isEqualTo("installation:inst");
            return Map.of("ok",true);
        });} catch(Exception impossible) {throw new AssertionError(impossible);}
        MetaContext.setContext(1L,0L,"installation:inst","Integration");
        InstalledIntegrationExecution.run(command,"config",binding,false,handler,"root",()->{
            InstalledIntegrationExecution.verify(command,1L,sourceConfig,Map.of("installedIntegration",Map.of()));
            MetaContext.runWithCommandPermitPlan("ALL",7L,"ledger","root",()->
                MetaContext.runWithAuthorizedCommandCode(command,()->phase.execute(ctx)));
            return null;
        });
        assertThat(ctx.getHandlerResults()).containsEntry("ok",true);
    }

    @Test void updateUsesExactChildTargetVersionAndRestoresRoot() {
        allow();when(nativeData.update(eq("child"),eq("line"),anyMap())).thenAnswer(i->{childScope();return row("line",1L,4L);});
        process(data->{data.update("child","line",Map.of("name","new","_expectedVersion",3L));
            assertThat(MetaContext.getCommandExpectedVersion("ledger","root")).isEqualTo(7L);});
        verify(nativeData).update(eq("child"),eq("line"),anyMap());
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }
    @Test void scalarAndMapCasKeepCallerCompareValueAndNativeResult() {
        allow();when(nativeData.compareAndSet("child","line","revision",4,5)).thenAnswer(i->{childScope();return false;});
        when(nativeData.compareAndSet("child","line","revision",4,Map.of("revision",5))).thenAnswer(i->{childScope();return true;});
        process(data->{assertThat(data.compareAndSet("child","line","revision",4,5)).isFalse();
            assertThat(data.compareAndSet("child","line","revision",4,Map.of("revision",5))).isTrue();});
        verify(handler,times(2)).authorizeInstalledRelatedWrite(argThat(c->c.operation().equals("compareAndSet")));
    }
    @Test void singletonBatchDelegatesWholeNativeBatch() {
        allow();var batch=List.of(new CompareAndSetUpdate("line",4,Map.of("revision",4)));
        doAnswer(i->{childScope();return null;}).when(nativeData).compareAndSetBatch("child","revision",batch);
        process(data->data.compareAndSetBatch("child","revision",batch));
        verify(handler).authorizeInstalledRelatedWrite(argThat(c->c.operation().equals("compareAndSetBatch")));
        verify(nativeData).compareAndSetBatch("child","revision",batch);
    }
    @Test void multipleOrEmptyBatchNeverReachesPersistence() {
        process(data->{assertThatThrownBy(()->data.compareAndSetBatch("child","revision",List.of())).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->data.compareAndSetBatch("child","revision",List.of(new CompareAndSetUpdate("line",1,Map.of("revision",1)),new CompareAndSetUpdate("other",1,Map.of("revision",1))))).isInstanceOf(SecurityException.class);});
        verifyNoInteractions(nativeData,handler);
    }
    @Test void oldHandlersAndUndeclaredModelsDenyBeforeWrite() {
        process(data->assertThatThrownBy(()->data.update("child","line",Map.of("x",1))).isInstanceOf(SecurityException.class));
        var old=new CommandHandlerExtension(){public String getCommandType(){return "old";}public Object execute(CommandContext c){return null;}};
        assertThat(old.authorizeInstalledRelatedWrite(null)).isFalse();
        verifyNoInteractions(nativeData);
    }
    @Test void wrongTenantPidAndRootFailBeforeHook() {
        process(data->{for(var invalid:List.of(row("line",2L,3L),row("other",1L,3L))) {
            when(reads.getById("child","line")).thenReturn(invalid);
            assertThatThrownBy(()->data.update("child","line",Map.of("x",1))).isInstanceOf(SecurityException.class);
        }
        when(reads.getById("ledger","root")).thenReturn(row("root",2L,7L));
        assertThatThrownBy(()->data.update("child","line",Map.of("x",1))).isInstanceOf(SecurityException.class);});
        verifyNoInteractions(handler,nativeData);
    }
    @Test void missingFractionalAndNegativeVersionsFailBeforeHook() {
        process(data->{for(Object version:Arrays.asList(null,-1L,1.5,"3")) {
            when(reads.getById("child","line")).thenReturn(row("line",1L,version));
            assertThatThrownBy(()->data.update("child","line",Map.of("x",1))).isInstanceOf(SecurityException.class);
        }});verifyNoInteractions(handler,nativeData);
    }
    @Test void staleCallerVersionCannotBeReplacedWithFreshVersion() {
        process(data->assertThatThrownBy(()->data.update("child","line",Map.of("_expectedVersion",2L,"x",1)))
            .isInstanceOf(com.auraboot.framework.meta.exception.RecordVersionConflictException.class));
        verifyNoInteractions(handler,nativeData);
    }
    @Test void nativeFailureRestoresRootPlan() {
        allow();var failure=new IllegalStateException("conflict");when(nativeData.update(anyString(),anyString(),anyMap())).thenThrow(failure);
        process(data->{assertThatThrownBy(()->data.update("child","line",Map.of("x",1))).isSameAs(failure);
            assertThat(MetaContext.getCommandExpectedVersion("ledger","root")).isEqualTo(7L);});
    }
    @Test void realCreateAndTryCreateProvenanceIsInvocationLocal() {
        when(nativeData.create("child",Map.of("x",1))).thenReturn(row("line",1L,3L));
        allow();process(data->{data.create("child",Map.of("x",1));data.update("child","line",Map.of("x",2));});
        verify(handler).authorizeInstalledRelatedWrite(argThat(c->c.createdInInvocation() && c.binding()==binding
            && c.root().recordId().equals("root") && c.root().records()==reads));
        reset(handler);allow();process(data->{data.tryCreate("child",Map.of("x",1));data.update("child","line",Map.of("x",2));});
        verify(handler).authorizeInstalledRelatedWrite(argThat(CommandHandlerExtension.InstalledRelatedWriteAuthorizationContext::createdInInvocation));
        reset(handler);allow();process(data->data.update("child","line",Map.of("x",2)));
        verify(handler).authorizeInstalledRelatedWrite(argThat(c->!c.createdInInvocation()));
    }
    @Test void retainedAdapterAndOrdinaryUserZeroCannotReuseProof() {
        allow();DataAccessor[] retained={null};process(data->retained[0]=data);
        assertThatThrownBy(()->retained[0].update("child","line",Map.of("x",1))).isInstanceOf(SecurityException.class);
        MetaContext.setContext(1L,0L,"installation:inst","Integration");
        assertThatThrownBy(()->InstalledIntegrationExecution.installRelatedWriter(new HashMap<>(Map.of("installedRelatedWriteAuthorization",true)),nativeData,handler,"ledger","root")).isInstanceOf(SecurityException.class);
        verifyNoInteractions(nativeData);
    }
    @Test void queryAcceptAndWrongHandlerCannotInstallBridge() {
        assertThatThrownBy(()->process(true,"root",handler,true,d->{})).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->process(false,null,handler,true,d->{})).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->process(false,"root",mock(CommandHandlerExtension.class),true,d->{})).isInstanceOf(SecurityException.class);
        verifyNoInteractions(nativeData);
    }
    @Test void missingPolicyKeepsOriginalAccessorAndRootUsesOriginalPath() {
        process(false,"root",handler,false,data->assertThat(data).isNotInstanceOf(InstalledRelatedWriteAccessor.class));
        process(data->data.update("ledger","root",Map.of("state","completed")));
        verify(nativeData).update("ledger","root",Map.of("state","completed"));verifyNoInteractions(handler);
    }
    @Test void modelAllowlistIsDecidedByTheActualHandlerAndCannotBePayloadGranted() {
        when(handler.authorizeInstalledRelatedWrite(any())).thenAnswer(i->
                ((CommandHandlerExtension.InstalledRelatedWriteAuthorizationContext)i.getArgument(0)).modelCode().equals("child"));
        process(data->{when(reads.getById("foreignModel","line")).thenReturn(row("line",1L,3L));
            assertThatThrownBy(()->data.update("foreignModel","line",Map.of("installedRelatedWriteAuthorization",true)))
                .isInstanceOf(SecurityException.class);});
        verifyNoInteractions(nativeData);
    }
    @Test void privateInvocationAloneCannotReplaceVerifiedCommandAndHandlerProof() {
        MetaContext.setContext(1L,0L,"installation:inst","Integration");
        InstalledIntegrationExecution.run(command,"config",binding,false,handler,"root",()->{
            Map<String,Object> values=new HashMap<>(Map.of("installedRelatedWriteAuthorization",true,
                    CommandHandlerExtension.TENANT_PROJECTION_ACCESSOR_KEY,reads));
            InstalledIntegrationExecution.install(command,1L,values);
            MetaContext.runWithCommandPermitPlan("ALL",7L,"ledger","root",()->
                MetaContext.runWithAuthorizedCommandCode(command,()->assertThatThrownBy(()->
                    InstalledIntegrationExecution.installRelatedWriter(values,nativeData,handler,"ledger","root"))
                    .isInstanceOf(SecurityException.class)));
            assertThatThrownBy(()->InstalledIntegrationExecution.verify(command,1L,"changed",Map.of("installedIntegration",Map.of())))
                .isInstanceOf(SecurityException.class);
            return null;
        });verifyNoInteractions(nativeData,handler,reads);
    }

    @Test void handlerCannotMutateRecordOrSwitchIdentityForTheWrite() {
        when(handler.authorizeInstalledRelatedWrite(any())).thenAnswer(i->{
            var c=(CommandHandlerExtension.InstalledRelatedWriteAuthorizationContext)i.getArgument(0);
            assertThatThrownBy(()->c.record().put("pid","other")).isInstanceOf(UnsupportedOperationException.class);
            MetaContext.setContext(2L,0L,"installation:inst","Integration");return true;});
        process(data->assertThatThrownBy(()->data.update("child","line",Map.of("x",1))).isInstanceOf(SecurityException.class));
        verifyNoInteractions(nativeData);
    }
    @Test void relatedMachineWriteKeepsLineageAndNativeVersionChecks() {
        sourceConfig="""
            {"handlerParams":{"installedMachineData":{
              "ledger":{"read":["pid","name"]},
              "child":{"read":["pid","name"],"create":["name"],"update":["name"]}}}}
            """;
        allow();
        when(nativeData.update(eq("child"),eq("line"),anyMap())).thenAnswer(call->{
            childScope();
            var grant=com.auraboot.framework.application.security.MachineDataAccessContext.requireDecision("child");
            assertThat(grant.actions()).containsExactly("update");
            assertThat(grant.constraints()).containsExactly(new com.auraboot.framework.application.security.MachineDataAccessContext.Constraint("pid","pid",List.of("line")));
            assertThat(com.auraboot.framework.application.security.MachineDataAccessContext.expectedVersion("child","line")).isEqualTo(3L);
            return row("line",1L,4L);
        });
        when(nativeData.create(eq("child"),anyMap())).thenAnswer(call->{
            assertThat(com.auraboot.framework.application.security.MachineDataAccessContext.requireDecision("child").actions()).containsExactly("create");
            return row("created",1L,1L);
        });
        var principal=new com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal(1L,"inst","Integration",Set.of(),"app","inst",null,null);
        try(var machine=com.auraboot.framework.application.security.MachineDataAccessContext.begin(principal)) {
            process(data->{
                data.create("child",Map.of("name","new"));
                data.update("child","line",Map.of("name","new"));
                assertThat(com.auraboot.framework.application.security.MachineDataAccessContext.requireDecision("child").actions()).containsExactly("read");
                when(handler.authorizeInstalledRelatedWrite(any())).thenReturn(false);
                assertThatThrownBy(()->data.update("child","line",Map.of("name","denied"))).isInstanceOf(SecurityException.class);
            });
        }
        verify(nativeData,times(1)).update(anyString(),anyString(),anyMap());
    }

    @Test void repeatedRootWritesReloadThePhysicalVersionAndRejectAnotherRoot() {
        when(nativeData.update(eq("ledger"),eq("root"),anyMap())).thenAnswer(call->{
            assertThat(MetaContext.getCommandExpectedVersion("ledger","root")).isEqualTo(8L);
            return row("root",1L,9L);
        });
        process(data->{
            when(reads.getById("ledger","root")).thenReturn(row("root",1L,8L));
            data.update("ledger","root",Map.of("name","second operation"));
            assertThatThrownBy(()->data.update("ledger","other-root",Map.of("name","denied"))).isInstanceOf(SecurityException.class);
            assertThatThrownBy(()->data.update("ledger","root",Map.of("name","stale","_expectedVersion",7L)))
                .isInstanceOf(com.auraboot.framework.meta.exception.RecordVersionConflictException.class);
        });
        verify(nativeData,times(1)).update(anyString(),anyString(),anyMap());
    }

}

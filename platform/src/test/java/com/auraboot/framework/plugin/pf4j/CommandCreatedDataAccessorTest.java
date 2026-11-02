package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.FieldWriterGuard;
import com.auraboot.framework.meta.service.impl.ModelMutationGuard;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandCreatedDataAccessorTest {
    final DynamicDataService data=mock(DynamicDataService.class);
    final MetaModelService models=mock(MetaModelService.class);
    final Object resourceKey=new Object(), resource=new Object();
    CommandPipelineContext context;
    ModelDefinition model;
    @BeforeEach void setup() {
        MetaContext.setContext(1L,2L,"actor","Developer"); MetaContext.setMemberId(3L);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(resourceKey,resource);
        var command=new CommandDefinition(); command.setCode("app:start");command.setModelCode("plan");command.setTargetModels("[\"plan\",\"run\"]");
        var request=new CommandExecuteRequest();request.setTargetRecordId("plan-1");
        context=CommandPipelineContext.builder().tenantId(1L).userId(2L).commandCode("app:start").command(command).request(request)
            .execConfig(Map.of("handlerParams",Map.of("commandCreatedModels",List.of("run"),"dslPersistence",false,
                "invocationAuthorization","handler-with-result-reads","targetRecordAction","read"))).build();
        context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("app.start"));context.setTargetRecordReadable(true);
        context.setPermitPlan(new CommandPermitPlan(CommandPermitPlan.Decision.PERMIT,null,null,"plan-1",null,null));
        context.setHandlerInvocationAuthorization(new CommandPipelineContext.HandlerInvocationAuthorization("app:start","plan","plan-1","read"));
        model=ModelDefinition.builder().code("run").commandOnlyCreate(true).fields(List.of(
            FieldDefinition.builder().code("name").allowedWriterCommands(List.of("app:start")).build()))
            .extension(Map.of("commandWriters",Map.of("create",List.of("app:start"),"update",List.of("app:start"),"delete",List.of("app:start")))).build();
        when(models.getModelDefinition("run")).thenReturn(Optional.of(model));
    }
    @AfterEach void clear() {
        TransactionSynchronizationManager.unbindResourceIfPossible(resourceKey);
        TransactionSynchronizationManager.clear();MetaContext.clear();
    }
    CommandCreatedDataAccessor open() {return CommandCreatedDataAccessor.open(data,models,context,new ObjectMapper());}
    @Test void onlyInsertProvenanceIsVisibleAndAllOrdinaryAccessStaysUnprivileged() {
        when(data.create(eq("run"),anyMap())).thenAnswer(i->{
            assertThat(MetaContext.isCommandCreateWriter("run","app:start")).isTrue();
            assertThat(MetaContext.isCommandCreateWriter("plan","app:start")).isFalse();
            assertThat(MetaContext.hasCommandPermitScope()).isFalse();assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
            ModelMutationGuard.assertCreateAllowed(model);FieldWriterGuard.assertCreateAllowed(model,Map.of("name","Run"));
            assertThatThrownBy(()->ModelMutationGuard.assertMutable(model,"update")).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->ModelMutationGuard.assertDeleteAllowed(model)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->FieldWriterGuard.assertFieldsAllowed(model,List.of("name"))).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->FieldWriterGuard.assertUpdateAllowed(model,Map.of("name","new"),Map.of("name","old"))).isInstanceOf(RuntimeException.class);
            return Map.of("pid","run-1");
        });
        try(var accessor=open()) {
            assertThat(accessor.create("run",Map.of("name","Run"))).containsEntry("pid","run-1");
            assertThat(MetaContext.isCommandCreateWriter("run","app:start")).isFalse();
            accessor.getById("run","sibling");accessor.update("plan","plan-1",Map.of("name","new"));
        }
        verify(data).getById("run","sibling");verify(data).update("plan","plan-1",Map.of("name","new"));
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }
    @Test void directGenericCreationStillLacksProvenance() {
        assertThatThrownBy(()->ModelMutationGuard.assertCreateAllowed(model)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->FieldWriterGuard.assertCreateAllowed(model,Map.of("name","Run"))).isInstanceOf(RuntimeException.class);
    }
    @Test void noOptInLeavesOriginalAccessorSelectionUnchanged() {context.setExecConfig(Map.of());assertThat(open()).isNull();}
    @Test void missingInvocationCannotCreate() {context.setHandlerInvocationAuthorization(null);assertThatThrownBy(this::open).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(data);}
    @Test void wrongInvocationTargetAndActionFailClosed() {
        context.setHandlerInvocationAuthorization(new CommandPipelineContext.HandlerInvocationAuthorization("app:start","plan","other","read"));
        assertThatThrownBy(this::open).isInstanceOf(AccessDeniedException.class);
        context.setHandlerInvocationAuthorization(new CommandPipelineContext.HandlerInvocationAuthorization("app:start","plan","plan-1","update"));
        assertThatThrownBy(this::open).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(data);
    }
    @Test void declaredModelsMustAlsoBeCommandTargets() {context.getCommand().setTargetModels("[\"plan\"]");assertThatThrownBy(this::open).isInstanceOf(AccessDeniedException.class);}
    @Test void wrongModelAndMissingExactModelPolicyNeverReachInsert() {
        try(var accessor=open()) {
            assertThatThrownBy(()->accessor.create("plan",Map.of("name","X"))).isInstanceOf(AccessDeniedException.class);
            model.setExtension(Map.of("commandWriters",Map.of("create",List.of("other:start"))));
            assertThatThrownBy(()->accessor.create("run",Map.of("name","X"))).isInstanceOf(AccessDeniedException.class);
        }verifyNoInteractions(data);
    }
    @Test void identityChangeInvalidatesCapturedCapability() {
        try(var accessor=open()) {MetaContext.setMemberId(9L);assertThatThrownBy(()->accessor.create("run",Map.of("name","X"))).isInstanceOf(AccessDeniedException.class);}
        verifyNoInteractions(data);
    }
    @Test void changedPhysicalTransactionResourceInvalidatesCapability() {
        try(var accessor=open()) {
            TransactionSynchronizationManager.unbindResource(resourceKey);TransactionSynchronizationManager.bindResource(resourceKey,new Object());
            assertThatThrownBy(()->accessor.create("run",Map.of("name","X"))).isInstanceOf(AccessDeniedException.class);
        }verifyNoInteractions(data);
    }
    @Test void closedCapabilityCannotEscapeHandler() {var accessor=open();accessor.close();assertThatThrownBy(()->accessor.create("run",Map.of("name","X"))).isInstanceOf(AccessDeniedException.class);verifyNoInteractions(data);}
    @Test void failedInsertRestoresProvenanceAndPreservesFailure() {
        var failure=new IllegalStateException("Native insert failed");when(data.create(eq("run"),anyMap())).thenThrow(failure);
        try(var accessor=open()) {assertThatThrownBy(()->accessor.tryCreate("run",Map.of("name","X"))).isSameAs(failure);}
        assertThat(MetaContext.isCommandCreateWriter("run","app:start")).isFalse();assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }
    @Test void snapshotPropagationDropsCreateProvenanceAndRestoresOriginalFrame() {
        var snapshot=MetaContext.snapshot();
        MetaContext.runWithCommandCreateWriter("run","app:start",()->true,()->{
            MetaContext.runWithSnapshot(snapshot,()->assertThat(MetaContext.isCommandCreateWriter("run","app:start")).isFalse());
            assertThat(MetaContext.isCommandCreateWriter("run","app:start")).isTrue();return null;
        });assertThat(MetaContext.isCommandCreateWriter("run","app:start")).isFalse();
    }

    @Test void synchronousCallbackCannotRetainCreateAuthorityAcrossIdentityOrTransactionChanges() {
        when(data.create(eq("run"),anyMap())).thenAnswer(i->{
            MetaContext.setMemberId(9L);
            assertThatThrownBy(()->ModelMutationGuard.assertCreateAllowed(model)).isInstanceOf(AccessDeniedException.class);
            MetaContext.setMemberId(3L);
            TransactionSynchronizationManager.unbindResource(resourceKey);TransactionSynchronizationManager.bindResource(resourceKey,new Object());
            assertThatThrownBy(()->FieldWriterGuard.assertCreateAllowed(model,Map.of("name","X"))).isInstanceOf(AccessDeniedException.class);
            TransactionSynchronizationManager.unbindResource(resourceKey);TransactionSynchronizationManager.bindResource(resourceKey,resource);
            ModelMutationGuard.assertCreateAllowed(model);return Map.of("pid","run-1");
        });
        try(var accessor=open()) {accessor.create("run",Map.of("name","X"));}
        assertThat(MetaContext.isCommandCreateWriter("run","app:start")).isFalse();
    }
}

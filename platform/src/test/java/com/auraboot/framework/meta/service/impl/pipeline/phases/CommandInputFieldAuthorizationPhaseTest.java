package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandInputFieldAuthorizationPhaseTest {
    final DataPermissionEngine policies = mock(DataPermissionEngine.class);
    final com.auraboot.framework.meta.service.MetaModelService metadata = mock(com.auraboot.framework.meta.service.MetaModelService.class);
    final com.auraboot.framework.permission.service.FieldPermissionService fields = mock(com.auraboot.framework.permission.service.FieldPermissionService.class);
    final CommandInputFieldAuthorizationPhase phase = new CommandInputFieldAuthorizationPhase(policies, metadata, fields);
    final CommandPipelineContext context = CommandPipelineContext.builder()
            .request(new com.auraboot.framework.meta.dto.CommandExecuteRequest()).build();

    @BeforeEach void prepare() {
        MetaContext.setContext(7L,20L,"actor","Actor"); MetaContext.setMemberId(30L);
        var command = new CommandDefinition(); command.setModelCode("orders"); context.setCommand(command);
        lenient().when(policies.getNonWritableFields(7L,"orders",20L)).thenReturn(Set.of("cost"));
    }
    @AfterEach void clear() { MetaContext.clear(); }
    @Test void declaredCommandAuthorityCannotOverrideInputFieldRestrictions() {
        context.setPayload(new HashMap<>(Map.of("cost",99,"description","unchanged")));
        assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan("TARGET",1L,"orders","root",
                () -> { phase.execute(context); return null; })).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        assertThat(context.getPayload()).containsEntry("cost",99).containsEntry("description","unchanged");
    }
    @Test void nullCannotBeUsedToClearARestrictedField() {
        var payload = new HashMap<String,Object>(); payload.put("cost",null); context.setPayload(payload);
        assertThatThrownBy(() -> phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    @Test void unrelatedInputsRemainUsableAndAreNotMutated() {
        context.setPayload(Map.of("description","new")); phase.execute(context);
        assertThat(context.getPayload()).containsExactly(Map.entry("description","new"));
    }
    @Test void emptyInputDoesNotCheckDerivedInternalWrites() {
        context.setPayload(Map.of()); phase.execute(context); verifyNoInteractions(policies);
    }

    private void dsl(String model) {
        lenient().when(policies.getNonWritableFields(7L,"orders",20L)).thenReturn(Set.of());
        lenient().when(metadata.getModelFields(model)).thenReturn(List.of(
                com.auraboot.framework.meta.dto.FieldDefinition.builder().code("cost").columnName("cost_column").build(),
                com.auraboot.framework.meta.dto.FieldDefinition.builder().code("description").columnName("description").build()));
        lenient().when(fields.getFieldPermissions(30L,model)).thenReturn(
                new com.auraboot.framework.permission.engine.model.FieldPermissionSet(Set.of("cost","description"),Set.of("description"),Set.of()));
    }
    @Test void implicitDslWriteChecksPresentNullBeforeReplay() {
        dsl("orders"); context.setExecConfig(Map.of("type","update"));
        var payload = new HashMap<String,Object>(); payload.put("cost",null); context.setPayload(payload);
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    @Test void dslWriteCannotUseAColumnAliasToBypassTheField() {
        dsl("orders"); context.setExecConfig(Map.of("type","create","inputFields",List.of("cost_column")));
        context.setPayload(Map.of("cost_column",99));
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    @Test void crossModelBindingChecksTheTargetFieldRatherThanTheInputName() {
        dsl("bills"); context.getCommand().setModelCode(null);
        var mapping = new com.auraboot.framework.meta.entity.BindingRule();
        mapping.setSourceField("amount"); mapping.setTargetModel("bills"); mapping.setTargetField("cost");
        context.setRulesByType(Map.of("field_map",List.of(mapping))); context.setPayload(Map.of("amount",99));
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    @Test void readOnlyFilterInputIsNotAnEditAttempt() {
        when(policies.getNonWritableFields(7L,"orders",20L)).thenReturn(Set.of());
        context.setExecConfig(Map.of("type","query","inputFields",List.of("cost")));
        context.setPayload(Map.of("cost",99)); phase.execute(context);
        verifyNoInteractions(metadata,fields);
    }
    @Test void derivedTransitionFieldsDoNotRequireUserEditRights() {
        dsl("orders");
        context.setExecConfig(Map.of("type","state_transition","inputFields",List.of("description"),
                "stateField","cost","toState","approved"));
        context.setPayload(Map.of("description","approved by operator")); phase.execute(context);
        assertThat(context.getPayload()).containsExactly(Map.entry("description","approved by operator"));
    }

    @Test void submittedAutoSetOverrideIsCheckedEvenOutsideExplicitInputFields() {
        dsl("orders"); context.captureSubmittedInput(Map.of("cost",99,"description","text"));
        context.setPayload(Map.of("cost",99,"description","text"));
        context.setExecConfig(Map.of("type","create","inputFields",List.of("description"),
                "autoSetFields",Map.of("cost",Map.of("strategy","default_value","value",0,"preserveInput",true))));
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    @Test void ownerInjectedKeysAreNotReclassifiedAsSubmittedFields() {
        dsl("orders"); context.captureSubmittedInput(Map.of("description","text"));
        context.setPayload(Map.of("cost",99,"description","text")); context.setExecConfig(Map.of("type","create"));
        phase.execute(context);
        assertThat(context.submittedInputKeys()).containsExactly("description");
    }
    @Test void removingANormalizedInputCannotEraseItsOriginalEditAttempt() {
        dsl("orders"); context.captureSubmittedInput(Map.of("cost",99));
        context.setPayload(Map.of()); context.setExecConfig(Map.of("type","update"));
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    private void completeFields(Set<String> allowed) {
        dsl("orders");
        when(fields.getFieldPermissions(30L,"orders")).thenReturn(
                com.auraboot.framework.permission.engine.model.FieldPermissionSet.allAllowed(Set.of("cost","description")));
        context.setExecConfig(Map.of("type","update"));
        context.getRequest().setTargetRecordId("root");
        var fieldSet=new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Fields(false,allowed,allowed);
        context.setBusinessGrantDecision(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision(
                com.auraboot.framework.permission.engine.model.PermissionResult.allow(List.of()),7L,20L,30L,"orders","root",
                List.of(new com.auraboot.framework.permission.engine.model.BusinessGrantDecision.Grant(1L,2L,"orders.update",fieldSet))));
    }

    @Test void matchedGrantFieldsAreEnforcedBeforeReplayIncludingColumnAliases() {
        completeFields(Set.of("description"));context.setPayload(Map.of("cost_column",99));
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        completeFields(Set.of("cost"));phase.execute(context);
        assertThat(context.getPayload()).containsEntry("cost_column",99);
    }

    @Test void completeDecisionCannotBeReusedForAnotherActorOrTarget() {
        completeFields(Set.of("description"));context.setPayload(Map.of("description","text"));
        context.getRequest().setTargetRecordId("other");
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        context.getRequest().setTargetRecordId("root");MetaContext.setMemberId(null);
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }

    @Test void restrictedGrantCannotAssumeAnArbitraryHandlerInputIsNotAWritableField() {
        completeFields(Set.of("description"));context.setPayload(Map.of("description","text"));
        context.setExecConfig(Map.of("type","handler"));
        assertThatThrownBy(()->phase.execute(context)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }

}

package com.auraboot.framework.meta.security;

import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NativeBusinessTransitionContractTest {
    final ExtensionRegistry registry = mock(ExtensionRegistry.class);
    CommandDefinition command() {
        var c = new CommandDefinition(); c.setCode("bom:cancel_task"); c.setModelCode("bom_conversion_task_pcba"); return c;
    }
    Map<String,Object> config() {
        return new HashMap<>(Map.of("type","state_transition", "permissions",List.of("bom.convert.execute"),
                "handlerParams",Map.of("targetRecordAuthorization","business","targetRecordAction","update"),
                "options",Map.of("targetVersionPolicy","current_state"), "stateField","bom_task_status",
                "fromStates",List.of("pending","matching"), "toState","cancelled",
                "autoSetFields",Map.of("bom_task_completed_at",Map.of("strategy","current_datetime"))));
    }
    @Test void boundedLifecycleTransitionUsesTheUpdateBusinessActionWithoutAnotherPermission() {
        var contract=NativeBusinessCommandContract.describe(command(),config(),Map.of(),registry);
        assertThat(contract.action()).isEqualTo("update");
        assertThat(contract.permissions()).containsExactly("bom.convert.execute");
    }
    @Test void aTransitionMayHaveNoDerivedFieldsBesidesItsFixedState() {
        var cfg=config(); cfg.remove("autoSetFields");
        assertThat(NativeBusinessCommandContract.describe(command(),cfg,Map.of(),registry).action()).isEqualTo("update");
    }
    @Test void arbitraryRulesHandlersInputsAndMutationKeysCannotAcquireThisContract() {
        for (var extra : List.of(Map.entry("inputFields", (Object)List.of("bom_task_status")),
                Map.entry("stateTransitionRules", (Object)List.of()), Map.entry("handler", (Object)"another:handler"),
                Map.entry("sideEffects", (Object)List.of()), Map.entry("preActions", (Object)List.of()))) {
            var cfg=config(); cfg.put(extra.getKey(),extra.getValue());
            assertThatThrownBy(() -> NativeBusinessCommandContract.describe(command(),cfg,Map.of(),registry))
                    .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        }
        var reserved=config();reserved.put("autoSetFields",Map.of("created_by",Map.of("strategy","fixed_value","value",99)));
        assertThatThrownBy(() -> NativeBusinessCommandContract.describe(command(),reserved,Map.of(),registry)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        var cfg=config();cfg.put("autoSetFields",Map.of("note",Map.of("strategy","expression","expression","unsafe()")));
        var bad=cfg;
        assertThatThrownBy(() -> NativeBusinessCommandContract.describe(command(),bad,Map.of(),registry)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
    @Test void malformedOrUnboundedLifecycleDefinitionsAreRefused() {
        for (var extra : List.of(Map.entry("stateField",(Object)"tenant_id"),Map.entry("fromStates",(Object)List.of()),
                Map.entry("fromStates",(Object)List.of("pending","pending")),Map.entry("fromStates",(Object)List.of("cancelled")),
                Map.entry("options",(Object)Map.of("targetVersionPolicy","unsupported")))) {
            var cfg=config();cfg.put(extra.getKey(),extra.getValue());
            assertThatThrownBy(() -> NativeBusinessCommandContract.describe(command(),cfg,Map.of(),registry)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        }
    }
    @Test void stateErrorNeverDisclosesTheCurrentValueAndCompletedIntentIsOnlyReplayEligible() {
        assertThatThrownBy(() -> NativeBusinessCommandContract.validateTransitionState(config(),Map.of("bom_task_status","private_state"),true))
                .hasMessage("$i18n:command.transition.not_allowed");
        NativeBusinessCommandContract.validateTransitionState(config(),Map.of("bom_task_status","cancelled"),true);
        assertThatThrownBy(() -> NativeBusinessCommandContract.validateTransitionState(config(),Map.of("bom_task_status","cancelled"),false))
                .isInstanceOf(com.auraboot.framework.exception.ValidationException.class);
    }
    @Test void changingTheFixedTransitionDefinitionChangesTheIdempotencyIntent() {
        var req=new com.auraboot.framework.meta.dto.CommandExecuteRequest();req.setTargetRecordId("task");req.setPayload(Map.of());
        var ctx=com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.builder().request(req).command(command()).commandCode("bom:cancel_task")
                .tenantId(7L).userId(20L).payload(Map.of()).execConfig(config()).build();
        var first=com.auraboot.framework.meta.service.impl.pipeline.CommandIdempotencyIntent.snapshot(ctx);
        var changed=config();changed.put("toState","other_state");ctx.setExecConfig(changed);
        assertThat(com.auraboot.framework.meta.service.impl.pipeline.CommandIdempotencyIntent.snapshot(ctx)).isNotEqualTo(first);
    }
    @Test void clientDataOrAnOperationOverrideCannotReplaceTheFixedIntent() {
        var req=new com.auraboot.framework.meta.dto.CommandExecuteRequest();req.setTargetRecordId("task");req.setPayload(Map.of());
        var ctx=com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.builder().request(req).command(command()).commandCode("bom:cancel_task")
                .tenantId(7L).userId(20L).payload(Map.of()).execConfig(config()).build();
        ctx.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized("bom.convert.execute"));
        ctx.captureSubmittedInput(Map.of("bom_task_status","completed"));
        assertThatThrownBy(() -> NativeBusinessCommandContract.require(ctx,registry)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        req.setOperationType("DELETE");
        var other=com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.builder().request(req).command(command()).commandCode("bom:cancel_task")
                .tenantId(7L).userId(20L).payload(Map.of()).execConfig(config()).build();
        other.setAuthorizationVerdict(com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict.authorized("bom.convert.execute"));
        assertThatThrownBy(() -> NativeBusinessCommandContract.require(other,registry)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
    }
}

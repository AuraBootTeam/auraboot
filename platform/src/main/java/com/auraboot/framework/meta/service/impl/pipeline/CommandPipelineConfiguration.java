package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.service.impl.pipeline.phases.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configures the command pipeline phase ordering.
 * Pre-guard phases run before the concurrency lock; guarded phases run inside it.
 */
@Configuration
public class CommandPipelineConfiguration {

    @Bean
    public CommandPipeline commandPipeline(
            // Pre-guard phases (Order 100-400)
            LoadPhase loadPhase,
            CommandAuthorizationPhase commandAuthorizationPhase,
            CommandTargetScopePhase commandTargetScopePhase,
            SchemaValidatePhase schemaValidatePhase,
            EntitlementPhase entitlementPhase,
            // Guarded phases (Order 500-1400)
            SodCheckPhase sodCheckPhase,
            IdempotencyPhase idempotencyPhase,
            CommandTargetVersionLockPhase commandTargetVersionLockPhase,
            PermitPlanAssemblyPhase permitPlanAssemblyPhase,
            StateCheckPhase stateCheckPhase,
            AssertPhase assertPhase,
            PreActionsPhase preActionsPhase,
            PreInvariantPhase preInvariantPhase,
            AutoSetPhase autoSetPhase,
            FieldMapPhase fieldMapPhase,
            ComputedFieldsPhase computedFieldsPhase,
            HandlerPhase handlerPhase,
            PostExecutionPhase postExecutionPhase,
            CompletionPhase completionPhase) {

        List<CommandPhase> preGuardPhases = List.of(
                loadPhase,
                commandAuthorizationPhase,
                commandTargetScopePhase,
                schemaValidatePhase,
                entitlementPhase
        );

        List<CommandPhase> guardedPhases = List.of(
                sodCheckPhase,
                // Replay is allowed only after every enforcing authorization gate, including the
                // target-row BOLA gate. The atomic database claim then fences handler execution; a
                // non-replay locks/rechecks the target version.
                idempotencyPhase,
                commandTargetVersionLockPhase,
                // Assemble the permit plan right after the last authorization gate (SoD) and before
                // any invariant/mutation phase — the boundary's whole decision is known here (§11.15).
                permitPlanAssemblyPhase,
                stateCheckPhase,
                assertPhase,
                preActionsPhase,
                preInvariantPhase,
                autoSetPhase,
                fieldMapPhase,
                computedFieldsPhase,
                handlerPhase,
                postExecutionPhase,
                completionPhase
        );

        return new CommandPipeline(preGuardPhases, guardedPhases);
    }
}

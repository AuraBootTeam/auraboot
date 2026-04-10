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
            SchemaValidatePhase schemaValidatePhase,
            IdempotencyPhase idempotencyPhase,
            EntitlementPhase entitlementPhase,
            // Guarded phases (Order 500-1400)
            SodCheckPhase sodCheckPhase,
            StateCheckPhase stateCheckPhase,
            AssertPhase assertPhase,
            PreInvariantPhase preInvariantPhase,
            AutoSetPhase autoSetPhase,
            FieldMapPhase fieldMapPhase,
            ComputedFieldsPhase computedFieldsPhase,
            HandlerPhase handlerPhase,
            PostExecutionPhase postExecutionPhase,
            CompletionPhase completionPhase) {

        List<CommandPhase> preGuardPhases = List.of(
                loadPhase,
                schemaValidatePhase,
                idempotencyPhase,
                entitlementPhase
        );

        List<CommandPhase> guardedPhases = List.of(
                sodCheckPhase,
                stateCheckPhase,
                assertPhase,
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

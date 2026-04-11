package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.dto.CommandExecuteResult;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Orchestrates the execution of an ordered list of {@link CommandPhase}s.
 * Handles phase timing, skip logic, and short-circuit (e.g., idempotent replay).
 *
 * @author AuraBoot Team
 * @since 8.0.0
 */
@Slf4j
@Component
public class CommandPipeline {

    private final List<CommandPhase> preGuardPhases;
    private final List<CommandPhase> guardedPhases;

    /**
     * @param preGuardPhases  phases that run BEFORE the concurrency guard (Load, Validate, Idempotency, Entitlement)
     * @param guardedPhases   phases that run INSIDE the concurrency guard (SOD through Audit)
     */
    public CommandPipeline(
            List<CommandPhase> preGuardPhases,
            List<CommandPhase> guardedPhases) {
        this.preGuardPhases = preGuardPhases;
        this.guardedPhases = guardedPhases;

        log.info("CommandPipeline initialized: {} pre-guard phases, {} guarded phases",
                preGuardPhases.size(), guardedPhases.size());
        preGuardPhases.forEach(p -> log.debug("  pre-guard: {}", p.name()));
        guardedPhases.forEach(p -> log.debug("  guarded: {}", p.name()));
    }

    /**
     * Execute all pre-guard phases. After this, the caller should set up
     * concurrency guard and call {@link #executeGuardedPhases(CommandPipelineContext)}.
     *
     * @return the short-circuit result if any phase triggered early return, otherwise null
     */
    @Observed(name = "command.pipeline.pre_guard", contextualName = "command-pipeline-pre-guard")
    public CommandExecuteResult executePreGuardPhases(CommandPipelineContext ctx) {
        for (CommandPhase phase : preGuardPhases) {
            executePhase(phase, ctx);
            if (ctx.isShortCircuited()) {
                return ctx.getShortCircuitResult();
            }
        }
        return null;
    }

    /**
     * Execute all guarded phases (inside the concurrency lock).
     *
     * @return the final result
     */
    @Observed(name = "command.pipeline.guarded", contextualName = "command-pipeline-guarded")
    public CommandExecuteResult executeGuardedPhases(CommandPipelineContext ctx) {
        for (CommandPhase phase : guardedPhases) {
            executePhase(phase, ctx);
            if (ctx.isShortCircuited()) {
                return ctx.getShortCircuitResult();
            }
        }

        // Build final result
        ctx.transitionTo("completed");
        var resultData = new java.util.HashMap<>(ctx.getFieldMapResults());
        resultData.putAll(ctx.getHandlerResults());
        mergeEffectiveRecordId(resultData, ctx.getRequest());

        return CommandExecuteResult.builder()
                .commandCode(ctx.getCommandCode())
                .phaseReached(ctx.getCurrentPhase())
                .data(resultData)
                .executionTimeMs(System.currentTimeMillis() - ctx.getStartTime())
                .idempotentReplay(false)
                .build();
    }

    private void executePhase(CommandPhase phase, CommandPipelineContext ctx) {
        if (phase.shouldSkip(ctx)) {
            log.debug("Skipping phase: {}", phase.name());
            return;
        }
        ctx.transitionTo(phase.name());
        phase.execute(ctx);
    }

    private void mergeEffectiveRecordId(java.util.Map<String, Object> resultData,
                                         com.auraboot.framework.meta.dto.CommandExecuteRequest request) {
        if (resultData == null || resultData.containsKey("recordId") || request == null) {
            return;
        }
        String recordId = request.getTargetRecordId();
        if (recordId != null && !recordId.isBlank()) {
            resultData.put("recordId", recordId);
        }
    }
}

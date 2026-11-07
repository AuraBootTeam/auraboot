package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.application.tenant.MetaContext;
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
        CommandPermitPlan existingPlan = authoritativePlan(ctx);
        if (existingPlan != null) {
            return executeUnderPermitPlan(ctx, existingPlan, 0);
        }

        for (int index = 0; index < guardedPhases.size(); index++) {
            CommandPhase phase = guardedPhases.get(index);
            executePhase(phase, ctx);
            if (ctx.isShortCircuited()) {
                return ctx.getShortCircuitResult();
            }
            CommandPermitPlan assembledPlan = authoritativePlan(ctx);
            if (assembledPlan != null) {
                return executeUnderPermitPlan(ctx, assembledPlan, index + 1);
            }
        }

        return buildFinalResult(ctx);
    }

    /** Reuse current authorization gates for delivery, without cache claims, locks or mutation phases. */
    public void authorizeDeferredHandler(CommandPipelineContext ctx, String handlerCode, String modelCode) {
        ctx.setDeferredHandlerAuthorization(true);
        boolean loaded = false;
        for (CommandPhase phase : preGuardPhases) {
            executePhase(phase, ctx);
            if (phase instanceof com.auraboot.framework.meta.service.impl.pipeline.phases.LoadPhase) {
                loaded = true;
                requireDeferredDefinition(ctx, handlerCode, modelCode);
            }
            if (ctx.isShortCircuited()) throw deferredDenied();
        }
        if (!loaded || ctx.getRequest().getTargetRecordId() != null
                && !ctx.getRequest().getTargetRecordId().isBlank()
                && !Boolean.TRUE.equals(ctx.getTargetRecordReadable())) throw deferredDenied();
        int claimIndex = -1;
        for (int index = 0; index < guardedPhases.size(); index++) {
            if (guardedPhases.get(index) instanceof com.auraboot.framework.meta.service.impl.pipeline.phases.IdempotencyPhase) {
                claimIndex = index;
                break;
            }
        }
        if (claimIndex < 0) throw deferredDenied();
        for (int index = 0; index < claimIndex; index++) {
            executePhase(guardedPhases.get(index), ctx);
            if (ctx.isShortCircuited()) throw deferredDenied();
        }
        var assembly = guardedPhases.stream().filter(phase -> phase instanceof
                com.auraboot.framework.meta.service.impl.pipeline.phases.PermitPlanAssemblyPhase).toList();
        if (assembly.size() != 1) throw deferredDenied();
        executePhase(assembly.getFirst(), ctx);
        if (ctx.getAuthorizationVerdict() == null || !ctx.getAuthorizationVerdict().isAuthorized()
                || ctx.getPermitPlan() == null || !ctx.getPermitPlan().isPermitted()) throw deferredDenied();
        Integer expected = ctx.getRequest().getExpectedVersion();
        if (expected != null && ctx.getTargetRecordVersion() != null
                && expected.longValue() != ctx.getTargetRecordVersion())
            throw new com.auraboot.framework.exception.CasVersionConflictException("Deferred target version changed", java.util.Map.of("errorCode", "CAS_VERSION_CONFLICT"));
    }

    private void requireDeferredDefinition(CommandPipelineContext ctx, String handlerCode, String modelCode) {
        var config = ctx.getExecConfig();
        Object raw = config == null ? null : config.get("handlerParams");
        if (ctx.getCommand() == null || config == null || !"custom".equals(config.get("type"))
                || !java.util.Objects.equals(ctx.getCommandCode(), ctx.getCommand().getCode())
                || !java.util.Objects.equals(modelCode, ctx.getCommand().getModelCode())
                || !java.util.Objects.equals(handlerCode, config.getOrDefault("handler", ctx.getCommandCode()))
                || raw != null && !(raw instanceof java.util.Map<?, ?>)
                // Business resource loans are transaction-local; no implicit cross-queue delegation.
                || raw instanceof java.util.Map<?, ?> params && "business".equals(params.get("targetRecordAuthorization")))
            throw deferredDenied();
    }

    private static org.springframework.security.access.AccessDeniedException deferredDenied() {
        return new org.springframework.security.access.AccessDeniedException("Deferred command requires current boundary authorization");
    }

    private CommandExecuteResult executeUnderPermitPlan(
            CommandPipelineContext ctx, CommandPermitPlan plan, int startIndex) {
        String targetModel = ctx.getCommand() == null ? null : ctx.getCommand().getModelCode();
        String targetRecordId = ctx.getRequest() == null ? null : ctx.getRequest().getTargetRecordId();
        return MetaContext.runWithCommandPermitPlan(
                plan.scope().name(),
                plan.expectedVersion(),
                targetModel,
                targetRecordId,
                () -> MetaContext.runWithAuthorizedCommandCode(
                        ctx.getCommandCode(),
                        () -> executeRemainingGuardedPhases(ctx, startIndex)));
    }

    private CommandExecuteResult executeRemainingGuardedPhases(
            CommandPipelineContext ctx, int startIndex) {
        for (int index = startIndex; index < guardedPhases.size(); index++) {
            executePhase(guardedPhases.get(index), ctx);
            if (ctx.isShortCircuited()) {
                return ctx.getShortCircuitResult();
            }
        }
        return buildFinalResult(ctx);
    }

    private CommandPermitPlan authoritativePlan(CommandPipelineContext ctx) {
        CommandPermitPlan plan = ctx.getPermitPlan();
        return plan != null && plan.isPermitted() && plan.scope() != null ? plan : null;
    }

    private CommandExecuteResult buildFinalResult(CommandPipelineContext ctx) {
        ctx.transitionTo("completed");
        var resultData = ctx.resultData();

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

}

package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.meta.service.impl.CommandEffectExecutor;
import com.auraboot.framework.meta.service.impl.CommandExecutorDelegate;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Effect, domain event, webhook/api-call (after-commit), post-invariant, idempotency record, audit.
 */
@Slf4j
@Component
@Order(1400)
@RequiredArgsConstructor
public class CompletionPhase implements CommandPhase {

    private final CommandEffectExecutor effectExecutor;
    private final InvariantEngine invariantEngine;
    private final CommandExecutorDelegate delegate;

    @Override public String name() { return "completion"; }

    @Override
    public void execute(CommandPipelineContext ctx) {
        // EFFECT phase
        var effectRules = ctx.getRulesByType().getOrDefault("effect", Collections.emptyList());
        effectExecutor.executeEffectPhase(effectRules, ctx.getCommand(), ctx.getPayload(),
                ctx.getFieldMapResults(), ctx.getTenantId(), ctx.getUserId(),
                ctx.getRequest(), ctx.getTargetState());

        // DOMAIN_EVENT phase
        delegate.publishDomainEvent(ctx.getCommand(), ctx.getRequest(), ctx.getPayload(),
                ctx.getTenantId(), ctx.getUserId(), ctx.getBeforeSnapshot());

        // API_CALL and WEBHOOK — schedule for after-commit
        List<BindingRule> apiCallRules = ctx.getRulesByType().getOrDefault("api_call", Collections.emptyList());
        List<BindingRule> webhookRules = ctx.getRulesByType().getOrDefault("webhook", Collections.emptyList());
        if (!apiCallRules.isEmpty() || !webhookRules.isEmpty()) {
            final var payload = new HashMap<>(ctx.getPayload());
            final var handlerResults = new HashMap<>(ctx.getHandlerResults());
            final var command = ctx.getCommand();
            final var tenantId = ctx.getTenantId();

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (!apiCallRules.isEmpty()) {
                            delegate.executeApiCallPhase(apiCallRules, payload, handlerResults);
                        }
                        if (!webhookRules.isEmpty()) {
                            delegate.executeWebhookPhase(webhookRules, command, payload, handlerResults, tenantId);
                        }
                    } catch (Exception e) {
                        log.warn("After-commit API_CALL/WEBHOOK execution failed: {}", e.getMessage());
                    }
                }
            });
        }

        // POST_INVARIANT
        invariantEngine.evaluatePostInvariants(
                ctx.getTenantId(), ctx.getCommand().getCode(), ctx.getCommand().getModelCode(),
                ctx.getPayload(), ctx.getRequest() != null ? ctx.getRequest().getTargetRecordId() : null,
                ctx.getTargetState());

        // Save idempotency record
        if (StringUtils.hasText(ctx.getRequest().getClientRequestId())) {
            var resultData = new HashMap<>(ctx.getFieldMapResults());
            resultData.putAll(ctx.getHandlerResults());
            delegate.saveIdempotencyRecord(ctx.getRequest().getClientRequestId(),
                    ctx.getCommandCode(), ctx.getPayload(), resultData, ctx.getTenantId());
        }

        // Audit log (after-commit)
        final long execTimeMs = System.currentTimeMillis() - ctx.getStartTime();
        ctx.getPhaseTimings().put(ctx.getCurrentPhase(), System.currentTimeMillis() - ctx.getCurrentPhaseStart());
        final var auditTimings = new LinkedHashMap<>(ctx.getPhaseTimings());
        final var auditPayload = new HashMap<>(ctx.getPayload());
        final var auditResult = new HashMap<>(ctx.getFieldMapResults());
        auditResult.putAll(ctx.getHandlerResults());
        final var tenantId = ctx.getTenantId();
        final var commandCode = ctx.getCommandCode();
        final var commandPid = ctx.getCommand().getPid();
        final var userId = ctx.getUserId();
        final var phase = ctx.getCurrentPhase();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                delegate.saveAuditLog(tenantId, commandCode, commandPid, userId,
                        auditPayload, auditResult, true, null, execTimeMs, phase, auditTimings);
            }
        });

        log.info("Command {} executed successfully in {}ms", ctx.getCommandCode(), execTimeMs);
    }
}

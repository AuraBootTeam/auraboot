package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.auraboot.framework.meta.service.impl.CommandEffectExecutor;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.auraboot.module.meta.event.DomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final DomainEventPublisher domainEventPublisher;
    private final IdempotencyService idempotencyService;
    private final ApiConnectorService apiConnectorService;
    private final WebhookDispatcher webhookDispatcher;
    private final ObjectMapper objectMapper;

    @Override public String name() { return "completion"; }

    @Override
    public void execute(CommandPipelineContext ctx) {
        boolean dryRun = ctx.getRequest() != null && ctx.getRequest().isDryRun();

        // EFFECT phase (PR-62 R2-N2): effectExecutor.executeEffectPhase writes
        // to ab_outbox and the event store via MyBatis mappers — both inherit
        // the caller's transaction (no REQUIRES_NEW, no @Async, no external
        // messaging). Under dry-run the outer transaction is already marked
        // rollback-only (CommandExecutorImpl:140) so these writes are
        // discarded by Postgres at rollback time. We intentionally LEAVE the
        // call active so the dry-run exercises the exact rollback envelope
        // that production relies on. If a future refactor introduces any
        // write that escapes the outer transaction (Kafka publish, WebSocket
        // emit, HTTP call, REQUIRES_NEW, @Async), this call MUST be gated
        // behind `if (!dryRun)` — see learning-loop.md "Plugin handler
        // dry-run contract".
        var effectRules = ctx.getRulesByType().getOrDefault("effect", Collections.emptyList());
        if (dryRun) {
            log.info("Dry-run: effect phase ran under rollback envelope for command {} ({} effect rules)",
                    ctx.getCommand().getCode(), effectRules.size());
        }
        effectExecutor.executeEffectPhase(effectRules, ctx.getCommand(), ctx.getPayload(),
                ctx.getFieldMapResults(), ctx.getTenantId(), ctx.getUserId(),
                ctx.getRequest(), ctx.getTargetState());

        // DOMAIN_EVENT phase — skipped under dry-run (PR-56 C4). The event
        // bus fan-out triggers AFTER_COMMIT listeners (AuditTrailEventListener
        // writes to ab_audit_trail via its own REQUIRES_NEW transaction);
        // setRollbackOnly already suppresses AFTER_COMMIT, but we avoid
        // even publishing the event to keep any @EventListener that runs
        // IN_COMMIT / immediately (non-transactional) from firing.
        if (!dryRun) {
            publishDomainEvent(ctx);
        } else {
            log.info("Dry-run: skipped domain event publication for command {}", ctx.getCommand().getCode());
        }

        // API_CALL and WEBHOOK — schedule for after-commit.
        // Under dry-run we don't even register the afterCommit callback:
        // the tx is rollback-only so it would never fire anyway, but
        // declining registration makes the intent explicit.
        List<BindingRule> apiCallRules = ctx.getRulesByType().getOrDefault("api_call", Collections.emptyList());
        List<BindingRule> webhookRules = ctx.getRulesByType().getOrDefault("webhook", Collections.emptyList());
        if (dryRun) {
            if (!apiCallRules.isEmpty() || !webhookRules.isEmpty()) {
                log.info("Dry-run: skipped api_call/webhook registration (api={}, webhook={}) for command {}",
                        apiCallRules.size(), webhookRules.size(), ctx.getCommand().getCode());
            }
        } else if (!apiCallRules.isEmpty() || !webhookRules.isEmpty()) {
            final var payload = new HashMap<>(ctx.getPayload());
            final var handlerResults = new HashMap<>(ctx.getHandlerResults());
            final var command = ctx.getCommand();
            final var tenantId = ctx.getTenantId();

            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        if (!apiCallRules.isEmpty()) {
                            executeApiCallPhase(apiCallRules, payload, handlerResults);
                        }
                        if (!webhookRules.isEmpty()) {
                            executeWebhookPhase(webhookRules, command, payload, handlerResults, tenantId);
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
            idempotencyService.recordOutcome(ctx.getRequest().getClientRequestId(),
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

        // PR-56 C4: skip audit log registration under dry-run. The outer
        // transaction is rollback-only so afterCommit would not fire, but
        // we also don't want to churn TransactionSynchronizationManager
        // state when we know the success path is synthetic.
        if (!dryRun) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    effectExecutor.saveAuditLog(tenantId, commandCode, commandPid, userId,
                            auditPayload, auditResult, true, null, execTimeMs, phase, auditTimings);
                }
            });
        } else {
            log.info("Dry-run: skipped audit log registration for command {}", commandCode);
        }

        log.info("Command {} executed successfully in {}ms{}", ctx.getCommandCode(), execTimeMs,
                dryRun ? " (dry-run)" : "");
    }

    // ==================== Inlined delegate methods ====================

    private void publishDomainEvent(CommandPipelineContext ctx) {
        try {
            String recordId = ctx.getRequest() != null ? ctx.getRequest().getTargetRecordId() : null;
            String actorName = MetaContext.exists() ? MetaContext.getCurrentUsername() : null;
            Map<String, Object> extraMeta = null;
            if (ctx.getBeforeSnapshot() != null) {
                extraMeta = Map.of("beforeSnapshot", ctx.getBeforeSnapshot());
            }
            domainEventPublisher.publishCommandCompleted(
                    ctx.getCommand().getCode(),
                    ctx.getRequest() != null ? ctx.getRequest().getOperationType() : "unknown",
                    ctx.getTenantId(), recordId, ctx.getCommand().getModelCode(), ctx.getPayload(),
                    ctx.getUserId(), actorName, extraMeta);
        } catch (Exception e) {
            log.warn("Failed to publish domain event for command {}: {}", ctx.getCommand().getCode(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeApiCallPhase(List<BindingRule> apiCallRules,
                                                     Map<String, Object> payload,
                                                     Map<String, Object> handlerResults) {
        Map<String, Object> apiResults = new HashMap<>();
        for (BindingRule rule : apiCallRules) {
            if (rule.getEnabled() != null && !rule.getEnabled()) {
                continue;
            }
            try {
                Map<String, Object> config = objectMapper.readValue(rule.getConfig(), Map.class);
                String connectorPid = (String) config.get("connectorPid");
                String endpointCode = (String) config.get("endpointCode");

                Map<String, Object> params = new HashMap<>(payload);
                params.putAll(handlerResults);

                Map<String, Object> result = apiConnectorService.invoke(connectorPid, endpointCode, params);
                if (result != null) {
                    apiResults.putAll(result);
                }
                log.debug("API_CALL rule executed: connector={}, endpoint={}", connectorPid, endpointCode);
            } catch (Exception e) {
                log.warn("API_CALL rule execution failed: {}", e.getMessage());
            }
        }
        return apiResults;
    }

    private void executeWebhookPhase(List<BindingRule> webhookRules,
                                      com.auraboot.framework.meta.entity.CommandDefinition command,
                                      Map<String, Object> payload,
                                      Map<String, Object> results,
                                      Long tenantId) {
        for (BindingRule rule : webhookRules) {
            if (rule.getEnabled() != null && !rule.getEnabled()) {
                continue;
            }
            try {
                String eventType = StringUtils.hasText(rule.getEventType())
                        ? rule.getEventType()
                        : command.getCode();

                Map<String, Object> webhookPayload = new HashMap<>();
                webhookPayload.put("commandCode", command.getCode());
                webhookPayload.put("modelCode", command.getModelCode());
                webhookPayload.put("payload", payload);
                webhookPayload.put("result", results);

                webhookDispatcher.dispatch(eventType, webhookPayload, tenantId);
                log.debug("WEBHOOK rule dispatched: eventType={}", eventType);
            } catch (Exception e) {
                log.warn("WEBHOOK rule dispatch failed: {}", e.getMessage());
            }
        }
    }
}

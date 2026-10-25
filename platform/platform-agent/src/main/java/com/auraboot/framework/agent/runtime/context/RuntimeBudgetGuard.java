package com.auraboot.framework.agent.runtime.context;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;

import java.time.Clock;
import java.time.Instant;

/**
 * Enforces the immutable runtime limits pinned in a {@link ContextEnvelope}.
 *
 * <p>Accounting is deliberately local to one turn/tool loop. Provider usage is
 * still written to the durable usage ledger, while this guard is the
 * synchronous fail-closed boundary that prevents another model/tool step after
 * a limit is crossed.
 */
public final class RuntimeBudgetGuard {

    private final ContextEnvelope envelope;
    private final LlmProvider provider;
    private final String model;
    private final Clock clock;
    private long consumedTokens;
    private long consumedCostMicros;

    public RuntimeBudgetGuard(
            ContextEnvelope envelope,
            LlmProvider provider,
            String model) {
        this(envelope, provider, model, Clock.systemUTC());
    }

    RuntimeBudgetGuard(
            ContextEnvelope envelope,
            LlmProvider provider,
            String model,
            Clock clock) {
        this.envelope = envelope;
        this.provider = provider;
        this.model = model;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public static RuntimeBudgetGuard current(LlmProvider provider, String model) {
        return new RuntimeBudgetGuard(
                ContextEnvelopeContext.current().orElse(null),
                provider,
                model);
    }

    public void beforeStep(int zeroBasedStep) {
        checkDeadline();
        if (envelope != null
                && envelope.maxSteps() != null
                && zeroBasedStep >= envelope.maxSteps()) {
            throw exceeded(
                    "runtime_max_steps_exceeded",
                    "Runtime step budget exhausted");
        }
        checkAccumulatedBudgets();
    }

    public void checkDeadline() {
        if (envelope != null
                && envelope.deadlineAt() != null
                && !Instant.now(clock).isBefore(envelope.deadlineAt())) {
            throw exceeded(
                    "runtime_deadline_exceeded",
                    "Runtime deadline exceeded");
        }
    }

    public void record(LlmChatResponse response) {
        if (response == null) {
            checkDeadline();
            return;
        }
        int inputTokens = Math.max(0, response.getInputTokens());
        int outputTokens = Math.max(0, response.getOutputTokens());
        consumedTokens = Math.addExact(
                consumedTokens,
                Math.addExact((long) inputTokens, outputTokens));
        if (provider != null) {
            double estimatedCost = provider.estimateCost(
                    model,
                    inputTokens,
                    outputTokens,
                    Math.max(0, response.getCacheCreationInputTokens()),
                    Math.max(0, response.getCacheReadInputTokens()));
            if (Double.isFinite(estimatedCost) && estimatedCost > 0d) {
                long micros = (long) Math.ceil(estimatedCost * 1_000_000d);
                consumedCostMicros = Math.addExact(consumedCostMicros, micros);
            }
        }
        checkDeadline();
        checkAccumulatedBudgets();
    }

    public long consumedTokens() {
        return consumedTokens;
    }

    public long consumedCostMicros() {
        return consumedCostMicros;
    }

    private void checkAccumulatedBudgets() {
        if (envelope == null) {
            return;
        }
        if (envelope.tokenBudget() != null
                && consumedTokens > envelope.tokenBudget()) {
            throw exceeded(
                    "runtime_token_budget_exceeded",
                    "Runtime token budget exceeded");
        }
        if (envelope.costBudgetMicros() != null
                && consumedCostMicros > envelope.costBudgetMicros()) {
            throw exceeded(
                    "runtime_cost_budget_exceeded",
                    "Runtime cost budget exceeded");
        }
    }

    private RuntimeBudgetExceededException exceeded(String reasonCode, String message) {
        return new RuntimeBudgetExceededException(
                reasonCode,
                message,
                consumedTokens,
                consumedCostMicros);
    }

    public static final class RuntimeBudgetExceededException extends RuntimeException {
        private final String reasonCode;
        private final long consumedTokens;
        private final long consumedCostMicros;

        RuntimeBudgetExceededException(
                String reasonCode,
                String message,
                long consumedTokens,
                long consumedCostMicros) {
            super(message);
            this.reasonCode = reasonCode;
            this.consumedTokens = consumedTokens;
            this.consumedCostMicros = consumedCostMicros;
        }

        public String reasonCode() {
            return reasonCode;
        }

        public long consumedTokens() {
            return consumedTokens;
        }

        public long consumedCostMicros() {
            return consumedCostMicros;
        }
    }
}

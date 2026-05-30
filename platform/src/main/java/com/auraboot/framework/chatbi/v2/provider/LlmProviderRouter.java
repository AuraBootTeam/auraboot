package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 3-level LLM fallback router. PRD 17 §10.
 *
 * <p>Routing tiers, in order:
 *
 * <ol>
 *   <li><b>Primary</b> — {@link AnthropicLlmProvider} when wired</li>
 *   <li><b>Secondary</b> — {@link OpenAiLlmProvider} when wired</li>
 *   <li><b>Tertiary</b> — {@code keyword-parser} via the v1 path (caller's
 *       responsibility; router returns {@link IntentResult#empty()} to
 *       signal the downgrade)</li>
 * </ol>
 *
 * <p>Each provider tier owns a tiny in-process circuit breaker keyed by
 * provider name: {@value #FAIL_THRESHOLD} consecutive failures within
 * {@value #WINDOW_SECONDS}s opens the breaker for {@value #OPEN_SECONDS}s,
 * during which the router skips that tier. A successful call resets the
 * counter immediately.
 *
 * <p>Why hand-rolled instead of resilience4j: the surface is tiny (2 providers,
 * 1 metric), depending on resilience4j just for a counter would add a
 * 1.5MB transitive set and obscure the actual policy.
 */
@Slf4j
@Component
public class LlmProviderRouter {

    static final int FAIL_THRESHOLD = 5;
    static final long WINDOW_SECONDS = 30L;
    static final long OPEN_SECONDS = 30L;
    static final String PRIMARY_KEY = "anthropic";
    static final String SECONDARY_KEY = "openai";

    private final ObjectProvider<AnthropicLlmProvider> primary;
    private final ObjectProvider<OpenAiLlmProvider> secondary;
    private final ConcurrentHashMap<String, Breaker> breakers = new ConcurrentHashMap<>();

    public LlmProviderRouter(ObjectProvider<AnthropicLlmProvider> primary,
                             ObjectProvider<OpenAiLlmProvider> secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    /**
     * Translate, walking the fallback chain. Each step is recorded in
     * {@link RouteOutcome#attempts} so the caller can audit fallback usage.
     */
    public RouteOutcome translate(String nlQuery,
                                  SemanticMetaResponse catalog,
                                  ConversationContext ctx) {
        List<Attempt> attempts = new ArrayList<>(3);

        IntentResult primaryResult = tryProvider(PRIMARY_KEY, primary.getIfAvailable(),
                nlQuery, catalog, ctx, attempts);
        if (isAcceptable(primaryResult)) {
            return new RouteOutcome(primaryResult, PRIMARY_KEY, attempts);
        }

        IntentResult secondaryResult = tryProvider(SECONDARY_KEY, secondary.getIfAvailable(),
                nlQuery, catalog, ctx, attempts);
        if (isAcceptable(secondaryResult)) {
            return new RouteOutcome(secondaryResult, SECONDARY_KEY, attempts);
        }

        // Tertiary: caller falls back to keyword v1 path on empty.
        attempts.add(new Attempt("keyword-v1", Outcome.DOWNGRADED, "no llm responded"));
        return new RouteOutcome(IntentResult.empty(), "keyword-v1", attempts);
    }

    private IntentResult tryProvider(String key,
                                     LlmProvider provider,
                                     String nlQuery,
                                     SemanticMetaResponse catalog,
                                     ConversationContext ctx,
                                     List<Attempt> attempts) {
        if (provider == null) {
            attempts.add(new Attempt(key, Outcome.UNAVAILABLE, "no bean"));
            return IntentResult.empty();
        }
        Breaker b = breakers.computeIfAbsent(key, k -> new Breaker());
        if (b.isOpen()) {
            attempts.add(new Attempt(key, Outcome.CIRCUIT_OPEN, "skipped"));
            return IntentResult.empty();
        }
        try {
            IntentResult r = provider.translate(nlQuery, catalog, ctx);
            if (r == null) {
                b.recordFailure();
                attempts.add(new Attempt(key, Outcome.FAILED, "null result"));
                return IntentResult.empty();
            }
            if (isAcceptable(r)) {
                b.recordSuccess();
                attempts.add(new Attempt(key, Outcome.SUCCESS, null));
            } else {
                // empty result counts as a soft failure — provider returned without throwing
                // but produced nothing usable. Don't increment breaker on this, because the
                // most common cause is missing tenant config, not a wire failure.
                attempts.add(new Attempt(key, Outcome.EMPTY, "confidence 0"));
            }
            return r;
        } catch (Exception e) {
            // Defensive — the LlmProvider contract says never-throw, but enforce here.
            b.recordFailure();
            attempts.add(new Attempt(key, Outcome.FAILED, e.getClass().getSimpleName()));
            log.warn("Provider {} threw despite never-throw contract: {}", key, e.getMessage());
            return IntentResult.empty();
        }
    }

    private static boolean isAcceptable(IntentResult r) {
        if (r == null) return false;
        // confidence>0 OR needsClarification means provider produced a real
        // signal. Confidence==0 with empty disambiguation is treated as "no
        // opinion" and we move on.
        if (r.confidence() > 0.0) return true;
        return r.needsClarification() && r.disambiguation() != null;
    }

    // -- circuit breaker --------------------------------------------------

    /** Test seam — flush all breakers. */
    public void resetBreakers() {
        breakers.clear();
    }

    static final class Breaker {
        private final AtomicInteger failuresInWindow = new AtomicInteger(0);
        private volatile Instant windowStart = Instant.EPOCH;
        private volatile Instant openedAt = Instant.EPOCH;

        synchronized boolean isOpen() {
            Instant now = Instant.now();
            if (now.isBefore(openedAt.plus(Duration.ofSeconds(OPEN_SECONDS)))
                    && !openedAt.equals(Instant.EPOCH)) {
                return true;
            }
            // Closed → reset opened marker.
            openedAt = Instant.EPOCH;
            return false;
        }

        synchronized void recordSuccess() {
            failuresInWindow.set(0);
            windowStart = Instant.EPOCH;
        }

        synchronized void recordFailure() {
            Instant now = Instant.now();
            if (windowStart.equals(Instant.EPOCH)
                    || now.isAfter(windowStart.plus(Duration.ofSeconds(WINDOW_SECONDS)))) {
                windowStart = now;
                failuresInWindow.set(1);
                return;
            }
            int count = failuresInWindow.incrementAndGet();
            if (count >= FAIL_THRESHOLD) {
                openedAt = now;
                log.warn("LLM router circuit opened (failures={})", count);
            }
        }
    }

    // -- public DTOs ------------------------------------------------------

    public enum Outcome {
        SUCCESS,        // returned usable IntentResult
        EMPTY,          // returned empty (no opinion / missing config)
        FAILED,         // wire / parse failure
        CIRCUIT_OPEN,   // skipped — breaker open
        UNAVAILABLE,    // bean not wired
        DOWNGRADED      // caller must fall back to v1 keyword path
    }

    public record Attempt(String provider, Outcome outcome, String detail) {}

    public record RouteOutcome(IntentResult result, String winner, List<Attempt> attempts) {}
}

package com.auraboot.framework.conversation;

import com.auraboot.framework.aurabot.service.RagContextProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived bridge between chat-time retrieval and the terminal-turn observation seam.
 *
 * <p>Retrieval happens inside the AuraBot/named-agent adapters, while
 * {@link TurnCompletionObservationListener} runs after the conversation chokepoint has
 * finalized the outcome. {@link TurnContext} is immutable and intentionally does not
 * carry growing telemetry payloads, so this registry correlates the two stages by the
 * stable turn pid. Entries are removed on terminal observation and TTL-cleaned if a JVM
 * crash/interrupted turn never reaches that seam.
 */
@Component
public class TurnEvalTelemetryRegistry {

    private static final int MAX_TEXT_CHARS = 4_000;
    private static final Duration MAX_AGE = Duration.ofHours(2);

    private final ConcurrentHashMap<String, State> byTurn = new ConcurrentHashMap<>();

    public void recordInput(String turnId, String input) {
        update(turnId, state -> new State(
                truncate(input), state.traceId(), state.retrieval(), Instant.now()));
    }

    public void recordTrace(String turnId, String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return;
        }
        update(turnId, state -> new State(
                state.input(), traceId, state.retrieval(), Instant.now()));
    }

    public void recordRetrieval(String turnId,
                                RagContextProvider.RetrievalDiagnostics retrieval) {
        if (retrieval == null) {
            return;
        }
        update(turnId, state -> new State(
                state.input(), state.traceId(), retrieval, Instant.now()));
    }

    /** Remove and return the completed turn's telemetry. */
    public Snapshot take(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            return null;
        }
        State state = byTurn.remove(turnId);
        return state == null
                ? null
                : new Snapshot(state.input(), state.traceId(), state.retrieval());
    }

    @Scheduled(fixedRate = 600_000)
    public void cleanupStale() {
        Instant cutoff = Instant.now().minus(MAX_AGE);
        byTurn.entrySet().removeIf(entry -> entry.getValue().touchedAt().isBefore(cutoff));
    }

    private void update(String turnId, java.util.function.Function<State, State> update) {
        if (turnId == null || turnId.isBlank()) {
            return;
        }
        byTurn.compute(turnId, (ignored, current) ->
                update.apply(current == null ? State.empty() : current));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_TEXT_CHARS
                ? value
                : value.substring(0, MAX_TEXT_CHARS) + "…";
    }

    public record Snapshot(String input,
                           String traceId,
                           RagContextProvider.RetrievalDiagnostics retrieval) {
    }

    private record State(String input,
                         String traceId,
                         RagContextProvider.RetrievalDiagnostics retrieval,
                         Instant touchedAt) {
        private static State empty() {
            return new State(null, null, null, Instant.now());
        }
    }
}

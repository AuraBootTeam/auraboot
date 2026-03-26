package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.event.AgentEvent;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentObservationService {

    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final double FAILURE_RATE_THRESHOLD = 0.5;
    private static final int FAILURE_RATE_WINDOW = 10;

    private final DynamicDataMapper dynamicDataMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** Track recent tool call outcomes per agent: agentId -> circular buffer of success/fail. */
    private final ConcurrentHashMap<String, ToolCallTracker> trackers = new ConcurrentHashMap<>();

    public void publish(Long tenantId, String agentEventType, String agentId,
                        String modelCode, String recordId, Map<String, Object> detail) {
        AgentEvent event = new AgentEvent(tenantId, agentEventType, agentId, modelCode, recordId, detail);
        eventPublisher.publishEvent(event);
    }

    @Async
    @EventListener
    public void onAgentEvent(AgentEvent event) {
        try {
            Map<String, Object> row = new HashMap<>();
            row.put("pid", UniqueIdGenerator.generate());
            row.put("tenant_id", event.getTenantId());
            row.put("observation_type", mapToObservationType(event.getAgentEventType()));
            row.put("source_type", event.getModelCode() != null ? event.getModelCode().toLowerCase() : "system");
            row.put("source_id", event.getRecordId());
            row.put("obs_agent_id", event.getAgentId());
            row.put("obs_title", event.getAgentEventType() + ": " + (event.getRecordId() != null ? event.getRecordId() : "system"));
            row.put("detail", event.getPayload() != null ? objectMapper.writeValueAsString(event.getPayload()) : null);
            row.put("severity", mapToSeverity(event.getAgentEventType()));
            row.put("created_at", Instant.now());
            row.put("updated_at", Instant.now());

            dynamicDataMapper.insert("ab_agent_observation", row);
            log.debug("Observation logged: type={}, agent={}, record={}", event.getAgentEventType(), event.getAgentId(), event.getRecordId());
        } catch (Exception e) {
            log.error("Failed to log observation: {}", e.getMessage(), e);
        }
    }

    private String mapToObservationType(String agentEventType) {
        if (agentEventType.contains("failed") || agentEventType.contains("error")) return "error";
        if (agentEventType.contains("cost") || agentEventType.contains("token")) return "cost";
        if (agentEventType.contains("alert")) return "alert";
        return "activity";
    }

    private String mapToSeverity(String agentEventType) {
        if (agentEventType.contains("failed")) return "error";
        if (agentEventType.contains("alert")) return "warn";
        return "info";
    }

    // ==================== Anomaly Detection ====================

    /**
     * Record a tool call outcome for anomaly tracking.
     * Returns true if an anomaly is detected (caller should pause the run).
     */
    public boolean recordToolCallOutcome(Long tenantId, String agentId, String runPid,
                                         String toolCode, boolean success, String errorMessage) {
        ToolCallTracker tracker = trackers.computeIfAbsent(agentId, k -> new ToolCallTracker());
        tracker.record(success);

        // Check: consecutive failures
        if (tracker.getConsecutiveFailures() >= MAX_CONSECUTIVE_FAILURES) {
            publishAlert(tenantId, agentId, runPid, "consecutive_failures",
                    "Agent " + agentId + " has " + tracker.getConsecutiveFailures() +
                            " consecutive tool call failures. Last error: " + errorMessage);
            return true;
        }

        // Check: failure rate in sliding window
        if (tracker.getTotalCalls() >= FAILURE_RATE_WINDOW) {
            double failureRate = tracker.getFailureRate();
            if (failureRate > FAILURE_RATE_THRESHOLD) {
                publishAlert(tenantId, agentId, runPid, "high_failure_rate",
                        "Agent " + agentId + " failure rate is " +
                                String.format("%.0f%%", failureRate * 100) +
                                " over last " + FAILURE_RATE_WINDOW + " calls.");
                return true;
            }
        }

        return false;
    }

    /**
     * Check if an agent run has exceeded its cost budget.
     * Returns true if cost anomaly detected.
     */
    public boolean checkCostAnomaly(Long tenantId, String agentId, String runPid,
                                    double currentCost, double budgetLimit) {
        if (budgetLimit <= 0) return false;
        double ratio = currentCost / budgetLimit;
        if (ratio > 2.0) {
            publishAlert(tenantId, agentId, runPid, "cost_exceeded_2x",
                    "Agent " + agentId + " cost ($" + String.format("%.4f", currentCost) +
                            ") exceeds 2x budget ($" + String.format("%.4f", budgetLimit) + ").");
            return true;
        }
        if (ratio > 0.8) {
            publish(tenantId, "cost_warning", agentId, null, runPid, Map.of(
                    "currentCost", currentCost, "budgetLimit", budgetLimit,
                    "usagePercent", String.format("%.0f%%", ratio * 100)));
        }
        return false;
    }

    /**
     * Clear tracker state for an agent (call when run completes).
     */
    public void clearTracker(String agentId) {
        trackers.remove(agentId);
    }

    private void publishAlert(Long tenantId, String agentId, String runPid,
                              String alertType, String message) {
        log.warn("Agent anomaly detected: type={}, agent={}, run={}, message={}",
                alertType, agentId, runPid, message);
        publish(tenantId, "alert_" + alertType, agentId, null, runPid,
                Map.of("alertType", alertType, "message", message,
                        "detectedAt", Instant.now().toString()));
    }

    /**
     * Scheduled cleanup: detect stale agent runs that have been in anomaly state.
     * Also cleans up old trackers for agents that haven't had activity in a while.
     */
    @Scheduled(fixedRate = 600_000) // every 10 minutes
    public void cleanupStaleTrackers() {
        // Remove trackers that have been idle (no calls in window)
        trackers.entrySet().removeIf(entry ->
                entry.getValue().getTotalCalls() == 0 ||
                        entry.getValue().getLastCallTime().isBefore(Instant.now().minus(java.time.Duration.ofHours(1))));
    }

    // ==================== Tool Call Tracker ====================

    /**
     * Sliding-window tracker for tool call success/failure rates.
     */
    static class ToolCallTracker {
        private final boolean[] outcomes = new boolean[FAILURE_RATE_WINDOW];
        private int index = 0;
        private int totalCalls = 0;
        private int consecutiveFailures = 0;
        private Instant lastCallTime = Instant.now();

        synchronized void record(boolean success) {
            outcomes[index % outcomes.length] = success;
            index++;
            totalCalls++;
            lastCallTime = Instant.now();

            if (success) {
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
            }
        }

        synchronized int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        synchronized int getTotalCalls() {
            return totalCalls;
        }

        synchronized Instant getLastCallTime() {
            return lastCallTime;
        }

        synchronized double getFailureRate() {
            int window = Math.min(totalCalls, outcomes.length);
            if (window == 0) return 0;
            int failures = 0;
            int start = Math.max(0, index - window);
            for (int i = start; i < index; i++) {
                if (!outcomes[i % outcomes.length]) failures++;
            }
            return (double) failures / window;
        }
    }
}

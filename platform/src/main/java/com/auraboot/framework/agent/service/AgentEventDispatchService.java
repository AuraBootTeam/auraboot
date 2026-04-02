package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Matches incoming business events against agent event_triggers configurations,
 * and creates dispatch tasks for matched agents.
 *
 * <p>Trigger matching rules:
 * <ul>
 *   <li>eventType must match exactly (required)</li>
 *   <li>modelCode is optional — if specified in trigger, event modelCode must match</li>
 *   <li>condition is evaluated as simple key=value pairs from eventData (Phase 6+ for complex expressions)</li>
 * </ul>
 *
 * <p>Debounce: same (tenantId, agentCode, eventType, modelCode) combination is suppressed
 * within {@value #DEBOUNCE_MS}ms to prevent event floods from triggering duplicate runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEventDispatchService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** Debounce window: suppress duplicate (tenant+agent+eventType+modelCode) within this period. */
    static final long DEBOUNCE_MS = 30_000L;

    /** In-memory debounce tracker. Evicted lazily on next check. */
    private final Map<String, Long> recentDispatches = new ConcurrentHashMap<>();

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Query all active agents in the tenant that have event_triggers matching
     * the given event. Returns matching agent codes; never null.
     *
     * @param tenantId  the tenant to query
     * @param eventType the domain event type (e.g. "entity_status_changed")
     * @param modelCode the model code of the affected record (nullable)
     * @param eventData additional event payload for condition matching (nullable)
     * @return list of agent_code values that matched, deduplicated
     */
    public List<String> findMatchingAgents(Long tenantId, String eventType,
                                            String modelCode, Map<String, Object> eventData) {
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(
                "SELECT agent_code, event_triggers FROM ab_agent_definition " +
                "WHERE tenant_id = ? AND status = 'active' AND event_triggers IS NOT NULL " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                tenantId);

        List<String> matched = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Map<String, Object> agent : agents) {
            String agentCode = (String) agent.get("agent_code");
            String triggersJson = agent.get("event_triggers") != null
                    ? agent.get("event_triggers").toString() : null;

            if (triggersJson == null) continue;

            if (matchesTrigger(triggersJson, eventType, modelCode, eventData)) {
                String debounceKey = tenantId + ":" + agentCode + ":" + eventType + ":"
                        + (modelCode != null ? modelCode : "");
                Long lastDispatch = recentDispatches.get(debounceKey);
                if (lastDispatch == null || (now - lastDispatch) > DEBOUNCE_MS) {
                    recentDispatches.put(debounceKey, now);
                    matched.add(agentCode);
                    log.debug("Event trigger matched: agent={}, eventType={}, modelCode={}",
                            agentCode, eventType, modelCode);
                } else {
                    log.debug("Debounced event dispatch for agent={} — {}ms since last dispatch",
                            agentCode, now - lastDispatch);
                }
            }
        }
        return matched;
    }

    /**
     * Create agent tasks for each matched agent code and return generated task PIDs.
     *
     * @param tenantId   the tenant
     * @param agentCodes agent codes returned by {@link #findMatchingAgents}
     * @param eventType  the triggering event type (stored in task for traceability)
     * @param eventData  the event payload (stored as input_data in the task)
     * @return list of created task PIDs
     */
    public List<String> dispatchMatchedAgents(Long tenantId, List<String> agentCodes,
                                               String eventType, Map<String, Object> eventData) {
        List<String> taskPids = new ArrayList<>();
        for (String agentCode : agentCodes) {
            String taskPid = createEventTask(tenantId, agentCode, eventType, eventData);
            taskPids.add(taskPid);
            log.info("Event-triggered dispatch: agent={}, event={}, taskPid={}",
                    agentCode, eventType, taskPid);
        }
        return taskPids;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Trigger matching
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Parse the triggers JSON and return true if any trigger entry matches the event.
     *
     * <p>Trigger JSON format:
     * <pre>
     * {
     *   "triggers": [
     *     {"eventType": "entity_status_changed", "modelCode": "crm_lead", "condition": "newStatus=QUALIFIED"},
     *     {"eventType": "record_created", "modelCode": "crm_complaint"}
     *   ]
     * }
     * </pre>
     */
    public boolean matchesTrigger(String triggersJson, String eventType,
                                   String modelCode, Map<String, Object> eventData) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                    triggersJson, new TypeReference<>() {});
            Object triggersObj = config.get("triggers");
            if (!(triggersObj instanceof List<?> triggerList)) return false;

            for (Object item : triggerList) {
                if (!(item instanceof Map<?, ?> trigger)) continue;
                if (triggerMatches(trigger, eventType, modelCode, eventData)) return true;
            }
        } catch (Exception e) {
            log.warn("Failed to parse event_triggers JSON: {} — cause: {}", triggersJson, e.getMessage());
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean triggerMatches(Map<?, ?> trigger, String eventType,
                                    String modelCode, Map<String, Object> eventData) {
        // 1. eventType must match
        String triggerEventType = (String) trigger.get("eventType");
        if (triggerEventType == null || !triggerEventType.equals(eventType)) return false;

        // 2. modelCode is optional; if present, it must match
        String triggerModelCode = (String) trigger.get("modelCode");
        if (triggerModelCode != null && !triggerModelCode.equals(modelCode)) return false;

        // 3. condition is optional; simple key=value check against eventData
        String condition = (String) trigger.get("condition");
        if (condition != null && !condition.isBlank() && eventData != null) {
            if (!evaluateSimpleCondition(condition, eventData)) return false;
        }

        return true;
    }

    /**
     * Evaluate a simple {@code key=value} condition against eventData.
     * Only the first pair (split on first '=') is evaluated.
     * Returns true if the condition key/value pair cannot be parsed (safe default).
     *
     * @param condition "newStatus=QUALIFIED" style expression
     * @param eventData flat map of event payload values
     * @return true if condition is satisfied or cannot be evaluated
     */
    public boolean evaluateSimpleCondition(String condition, Map<String, Object> eventData) {
        int idx = condition.indexOf('=');
        if (idx <= 0) return true; // unparseable — allow through
        String key = condition.substring(0, idx).trim();
        String expectedValue = condition.substring(idx + 1).trim();
        Object actual = eventData.get(key);
        return actual != null && expectedValue.equalsIgnoreCase(actual.toString().trim());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Task creation
    // ──────────────────────────────────────────────────────────────────────────

    private String createEventTask(Long tenantId, String agentCode, String eventType,
                                    Map<String, Object> eventData) {
        String taskPid = UniqueIdGenerator.generate();
        String inputJson = serializeEventData(eventData);
        String title = "Event-triggered: " + eventType + " → " + agentCode;

        jdbcTemplate.update(
                "INSERT INTO ab_agent_task " +
                "(pid, tenant_id, title, description, task_status, assignee_type, assignee_id, " +
                " input_data, tags, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, 'backlog', 'agent', ?, ?, ?, NOW(), NOW())",
                taskPid, tenantId, title,
                "Auto-created by event-driven dispatch. EventType=" + eventType,
                agentCode,
                inputJson,
                "event_triggered," + eventType.toLowerCase()
        );

        return taskPid;
    }

    private String serializeEventData(Map<String, Object> eventData) {
        if (eventData == null || eventData.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(eventData);
        } catch (Exception e) {
            log.warn("Failed to serialize eventData: {}", e.getMessage());
            return null;
        }
    }
}

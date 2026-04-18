package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * ACP D1 Grounding: persists BIF IR to ab_agent_bif.
 *
 * Every LLM chat turn produces one BIF row (audit + replay + offline analysis).
 * run_id and step_index may be null at creation time — backfilled once the turn
 * dispatches to a Skill/Run (see BifRecorder#attachRun).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BifRecorder {

    private static final Set<String> JSONB_COLUMNS = Set.of(
            "objects", "object_relations", "scope", "filters",
            "semantic_constraints", "context", "confidence",
            "candidate_skills", "explanation", "pre_context", "metrics");

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    /**
     * Insert a BIF row. Returns the generated pid, or null if persistence failed
     * (persistence failure must never block the chat flow — BIF is an audit trail).
     */
    public String record(Long tenantId, String nlInput, BusinessIntentFrame bif,
                         String runPid, String conversationId) {
        try {
            String pid = UniqueIdGenerator.generate();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pid", pid);
            row.put("tenant_id", tenantId);
            row.put("run_id", runPid);
            row.put("conversation_id", conversationId);
            row.put("nl_input", nlInput);

            row.put("intent", bif.getIntent());
            row.put("object", bif.getObject());
            row.put("primary_object", bif.getPrimaryObject() != null ? bif.getPrimaryObject() : bif.getObject());
            row.put("actionability", bif.getActionability());
            row.put("risk_level", bif.getRiskLevel());
            row.put("match_type", bif.getMatchType());
            row.put("candidate_skills_mode", bif.getCandidateSkillsMode());

            putJson(row, "objects", bif.getObjects());
            putJson(row, "scope", bif.getScope());
            putJson(row, "filters", bif.getFilters());
            putJson(row, "semantic_constraints", bif.getSemanticConstraints());
            putJson(row, "context", bif.getContext());
            putJson(row, "candidate_skills", bif.getCandidateSkills());
            putJson(row, "explanation", bif.getExplanation());
            putJson(row, "confidence", toConfidenceMap(bif.getConfidence()));

            row.put("schema_version", 1);
            row.put("created_at", LocalDateTime.now());

            dynamicDataMapper.insertWithJsonb("ab_agent_bif", row, JSONB_COLUMNS);
            return pid;
        } catch (Exception e) {
            log.warn("Failed to persist BIF for tenant={}, intent={}: {}",
                    tenantId, bif.getIntent(), e.getMessage());
            return null;
        }
    }

    /**
     * Backfill run_id / step_index / dispatched_skill after the turn has dispatched.
     */
    public void attachRun(String bifPid, String runPid, Integer stepIndex, String dispatchedSkill) {
        if (bifPid == null) return;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("run_id", runPid);
            data.put("step_index", stepIndex);
            data.put("dispatched_skill", dispatchedSkill);
            dynamicDataMapper.update("ab_agent_bif", data, Map.of("pid", bifPid));
        } catch (Exception e) {
            log.warn("Failed to backfill run on BIF pid={}: {}", bifPid, e.getMessage());
        }
    }

    private void putJson(Map<String, Object> row, String key, Object value) {
        if (value == null) return;
        try {
            row.put(key, objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            log.warn("Failed to serialize BIF.{}: {}", key, e.getMessage());
        }
    }

    private Map<String, Object> toConfidenceMap(ConfidenceScore c) {
        if (c == null) return Map.of("overall", 0.0, "intent", 0.0, "object", 0.0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overall", c.getOverall());
        m.put("intent", c.getIntent());
        m.put("object", c.getObject());
        return m;
    }
}

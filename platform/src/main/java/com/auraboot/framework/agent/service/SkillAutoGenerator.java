package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Generates exactly 2 built-in skills: dsl.command and dsl.query.
 * These generic skills replace the previous 876 auto-generated per-model atomic skills.
 * At runtime, dsl_dispatch execution mode resolves the correct tools dynamically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillAutoGenerator {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    private static final String TABLE = "ab_agent_skill";

    private static final Set<String> JSONB_COLUMNS = Set.of(
            "skill_tools", "produced_action_types", "skill_input_schema"
    );

    public record SyncResult(int created, int updated, int skipped) {}

    /**
     * Upsert the 2 built-in generic skills: dsl.command and dsl.query.
     */
    @Transactional
    public SyncResult syncSkills(Long tenantId) {
        int created = 0;
        int updated = 0;

        // dsl.command — all write operations (create, update, delete, transition)
        Map<String, Object> cmdDef = new LinkedHashMap<>();
        cmdDef.put("skill_code", "dsl.command");
        cmdDef.put("skill_name", "Execute DSL Command");
        cmdDef.put("skill_description", "Execute any DSL command (create, update, delete, transition). Requires commandCode and params from BIF resolution.");
        cmdDef.put("skill_level", "atomic");
        cmdDef.put("skill_category", "crud");
        cmdDef.put("execution_mode", "dsl_dispatch");
        cmdDef.put("output_type", "text");
        cmdDef.put("failure_mode", "fail_fast");
        cmdDef.put("max_retry", 0);
        cmdDef.put("max_steps", 1);
        cmdDef.put("timeout_sec", 30);
        cmdDef.put("actionability", "execute");
        cmdDef.put("idempotency_mode", "not_idempotent");
        cmdDef.put("is_builtin", true);

        if (upsertBuiltinSkill(tenantId, cmdDef)) {
            created++;
        } else {
            updated++;
        }

        // dsl.query — all read operations (list, get, named query)
        Map<String, Object> qryDef = new LinkedHashMap<>();
        qryDef.put("skill_code", "dsl.query");
        qryDef.put("skill_name", "Query DSL Data");
        qryDef.put("skill_description", "Query data: list records by model, get single record by ID, or execute a NamedQuery. Routes by presence of recordId/queryCode/model.");
        qryDef.put("skill_level", "atomic");
        qryDef.put("skill_category", "analysis");
        qryDef.put("execution_mode", "dsl_dispatch");
        qryDef.put("output_type", "structured_result");
        qryDef.put("render_hint", "table");
        qryDef.put("failure_mode", "fail_fast");
        qryDef.put("max_retry", 0);
        qryDef.put("max_steps", 1);
        qryDef.put("timeout_sec", 30);
        qryDef.put("actionability", "read_only");
        qryDef.put("idempotency_mode", "safe");
        qryDef.put("is_builtin", true);

        if (upsertBuiltinSkill(tenantId, qryDef)) {
            created++;
        } else {
            updated++;
        }

        log.info("Skill sync complete: created={}, updated={}", created, updated);
        return new SyncResult(created, updated, 0);
    }

    /**
     * Upsert a single built-in skill record.
     *
     * @return true if created (inserted), false if updated
     */
    private boolean upsertBuiltinSkill(Long tenantId, Map<String, Object> definition) {
        String skillCode = (String) definition.get("skill_code");
        LocalDateTime now = LocalDateTime.now();

        // Check existence
        String findSql = "SELECT pid FROM " + TABLE +
                " WHERE tenant_id = #{params.tenantId} AND skill_code = #{params.skillCode}" +
                " AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> existing = dynamicDataMapper.selectByQuery(
                findSql, Map.of("tenantId", tenantId, "skillCode", skillCode));

        if (!existing.isEmpty()) {
            // Update
            Map<String, Object> updates = new LinkedHashMap<>(definition);
            updates.remove("skill_code"); // not updatable as part of WHERE clause
            updates.put("updated_at", now);
            updates.put("skill_status", "active");

            dynamicDataMapper.updateWithJsonb(TABLE, updates,
                    Map.of("tenant_id", tenantId, "skill_code", skillCode),
                    JSONB_COLUMNS);
            return false;
        } else {
            // Insert
            Map<String, Object> record = new LinkedHashMap<>(definition);
            record.put("pid", UniqueIdGenerator.generate());
            record.put("tenant_id", tenantId);
            record.put("skill_status", "active");
            record.put("created_at", now);
            record.put("updated_at", now);
            record.put("deleted_flag", false);

            dynamicDataMapper.insertWithJsonb(TABLE, record, JSONB_COLUMNS);
            return true;
        }
    }
}

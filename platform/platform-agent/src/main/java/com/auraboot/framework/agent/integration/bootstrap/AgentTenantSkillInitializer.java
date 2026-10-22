package com.auraboot.framework.agent.integration.bootstrap;

import com.auraboot.framework.agent.service.SkillAutoGenerator;
import com.auraboot.framework.tenant.service.TenantSkillInitializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTenantSkillInitializer implements TenantSkillInitializer {
    private final SkillAutoGenerator skills;
    private final DynamicDataMapper dynamicDataMapper;
    @Override public void initialize(Long tenantId) { skills.syncSkills(tenantId); }

    @Override
    public void initializeAssistant(Long tenantId) {
        // Check for existing AuraBot agent (idempotent guard)
        String checkSql = "SELECT COUNT(*) AS cnt FROM ab_agent_definition " +
            "WHERE tenant_id = #{params.tenantId} AND agent_code = #{params.agentCode} " +
            "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
            checkSql, Map.of("tenantId", tenantId, "agentCode", "aurabot"));
        if (!rows.isEmpty() && ((Number) rows.get(0).get("cnt")).intValue() > 0) {
            log.debug("AuraBot agent already exists for tenant {}, skipping creation", tenantId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("pid", "aurabot_" + tenantId);
        agent.put("tenant_id", tenantId);
        agent.put("agent_code", "aurabot");
        agent.put("name", "AuraBot");
        agent.put("description",
            "Platform-native AI assistant with full access to all models, commands, and tools.");
        agent.put("agent_type", "reactive");
        agent.put("model", null); // resolved at runtime from the first enabled LLM provider
        agent.put("system_prompt",
            "You are AuraBot, the intelligent assistant for this platform. " +
            "Help users accomplish their tasks efficiently.");
        agent.put("max_tools", 20);
        agent.put("max_concurrent_runs", 3);
        agent.put("execution_timeout_seconds", 300);
        agent.put("status", "active");
        agent.put("deleted_flag", false);
        agent.put("created_at", now);
        agent.put("updated_at", now);

        dynamicDataMapper.insert("ab_agent_definition", agent);
        log.info("AuraBot agent created for tenant {}", tenantId);
    }

}

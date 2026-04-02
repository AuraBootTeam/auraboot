package com.auraboot.framework.agent.listener;

import com.auraboot.framework.agent.service.AgentRunService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.crm.event.InboundEmailEvent;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens for inbound email events and dispatches them to the CS Agent for automated processing.
 * Flow: InboundEmailEvent -> create ab_agent_task -> AgentRunService.executeTask()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerServiceAgentListener {

    public static final String CS_AGENT_CODE = "cs_agent";

    private final AgentRunService agentRunService;
    private final DynamicDataMapper dynamicDataMapper;

    @Async
    @EventListener
    public void onInboundEmail(InboundEmailEvent event) {
        Long tenantId = event.getTenantId();
        log.info("CS Agent: received InboundEmailEvent from {} (tenant={})", event.getSenderEmail(), tenantId);

        try {
            MetaContext.setSystemTenantContext(tenantId);

            // Check if cs_agent is configured and active for this tenant
            Map<String, Object> agentDef = findAgentDefinition(tenantId, CS_AGENT_CODE);
            if (agentDef == null) {
                log.info("CS Agent definition not found for tenant {}, skipping email processing", tenantId);
                return;
            }
            if (!"active".equals(agentDef.get("status"))) {
                log.info("CS Agent is not active for tenant {}, skipping", tenantId);
                return;
            }

            // Create agent task with email context
            String taskPid = UniqueIdGenerator.generate();
            String taskDescription = buildTaskDescription(event);

            Map<String, Object> taskData = new HashMap<>();
            taskData.put("pid", taskPid);
            taskData.put("tenant_id", tenantId);
            taskData.put("title", "Process inbound email: " + event.getEmailSubject());
            taskData.put("description", taskDescription);
            taskData.put("task_status", "todo");
            taskData.put("task_priority", "normal");
            taskData.put("assignee_type", "agent");
            taskData.put("assignee_id", CS_AGENT_CODE);
            taskData.put("created_at", LocalDateTime.now());
            taskData.put("updated_at", LocalDateTime.now());

            dynamicDataMapper.insert("ab_agent_task", taskData);
            log.info("CS Agent: created task {} for email from {}", taskPid, event.getSenderEmail());

            // Dispatch to agent runtime
            agentRunService.executeTask(tenantId, taskPid, CS_AGENT_CODE);

        } catch (Exception e) {
            log.error("CS Agent: failed to process InboundEmailEvent {}: {}", event.getEventId(), e.getMessage(), e);
        } finally {
            MetaContext.clear();
        }
    }

    private Map<String, Object> findAgentDefinition(Long tenantId, String agentCode) {
        try {
            String sql = "SELECT agent_code, status FROM ab_agent_definition " +
                    "WHERE tenant_id = #{params.tenantId} AND agent_code = #{params.agentCode} " +
                    "AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "agentCode", agentCode));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("Failed to look up agent definition {}: {}", agentCode, e.getMessage());
            return null;
        }
    }

    private String buildTaskDescription(InboundEmailEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("Process the following inbound customer email.\n\n");
        sb.append("Sender: ").append(event.getSenderEmail()).append("\n");
        sb.append("Subject: ").append(event.getEmailSubject()).append("\n");
        if (event.getAccountId() != null) {
            sb.append("Identified Account ID: ").append(event.getAccountId()).append("\n");
        }
        if (event.getContactId() != null) {
            sb.append("Identified Contact ID: ").append(event.getContactId()).append("\n");
        }
        sb.append("\n--- Email Body ---\n");
        sb.append(event.getEmailBody());
        return sb.toString();
    }
}

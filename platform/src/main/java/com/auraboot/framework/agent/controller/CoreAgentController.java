package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.service.AgentDefinitionService;
import com.auraboot.framework.agent.service.AgentEventDispatchService;
import com.auraboot.framework.agent.service.AgentOrganizationService;
import com.auraboot.framework.agent.service.McpServerConfigService;
import com.auraboot.framework.agent.spi.AgentExecutionService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class CoreAgentController {

    private final AgentProperties agentProperties;
    private final LlmProviderFactory providerFactory;
    private final AgentExecutionService agentExecutionService;
    private final McpServerConfigService mcpServerConfigService;
    private final AgentEventDispatchService agentEventDispatchService;
    private final AgentOrganizationService agentOrganizationService;
    private final AgentDefinitionService agentDefinitionService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", agentProperties.isEnabled());
        status.put("enterpriseAvailable", agentExecutionService.isAvailable());
        try {
            var configured = providerFactory.listConfiguredProviders(null);
            status.put("configuredProviders", configured.size());
            status.put("providers", configured.stream()
                .map(p -> Map.of("code", p.getProviderCode(), "name", p.getDisplayName()))
                .toList());
        } catch (Exception e) {
            status.put("configuredProviders", 0);
            status.put("providerError", e.getMessage());
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/providers")
    public ResponseEntity<?> listProviders() {
        return ResponseEntity.ok(providerFactory.listAllProviders());
    }

    @GetMapping("/providers/configured")
    public ResponseEntity<?> listConfiguredProviders() {
        return ResponseEntity.ok(providerFactory.listConfiguredProviders(null));
    }

    @PostMapping("/tools/sync")
    public ResponseEntity<?> syncTools() {
        // Tool auto-generation into ab_agent_tool is no longer needed.
        // Tools are now discovered dynamically via ToolProviderRegistry.
        return ResponseEntity.ok(Map.of("message", "Tool sync is no longer needed. Tools are discovered dynamically."));
    }

    // ──────────────────────────────────────────────────────────────
    // MCP Server Registry endpoints (Phase 5c)
    // ──────────────────────────────────────────────────────────────

    /**
     * List all active external MCP server configurations for the current tenant.
     */
    @GetMapping("/mcp-servers")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listMcpServers() {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Map<String, Object>> servers = mcpServerConfigService.listActiveServers(tenantId);
        return ResponseEntity.ok(ApiResponse.success(servers));
    }

    /**
     * Register a new external MCP server for the current tenant.
     * <p>
     * Request body fields:
     * <ul>
     *   <li>name          — required, human-readable server name (e.g. "GitHub MCP")</li>
     *   <li>url           — required, server URL or command (e.g. "npx @modelcontextprotocol/server-github")</li>
     *   <li>transportType — optional, STDIO (default) / SSE / HTTP</li>
     *   <li>authType      — optional, NONE / BEARER / API_KEY</li>
     *   <li>authConfig    — optional, map of auth config (e.g. {"token": "ghp_xxx"})</li>
     * </ul>
     */
    @PostMapping("/mcp-servers")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, String>>> registerMcpServer(
            @RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();

        String name = (String) body.get("name");
        String url = (String) body.get("url");
        if (name == null || name.isBlank()) {
            return ResponseEntity.ok(ApiResponse.error("name is required"));
        }
        if (url == null || url.isBlank()) {
            return ResponseEntity.ok(ApiResponse.error("url is required"));
        }

        String transportType = body.containsKey("transportType")
                ? (String) body.get("transportType") : "stdio";
        String authType = (String) body.get("authType");
        Map<String, Object> authConfig = body.get("authConfig") instanceof Map
                ? (Map<String, Object>) body.get("authConfig") : null;

        String pid = mcpServerConfigService.registerServer(tenantId, name, url,
                transportType, authType, authConfig);
        return ResponseEntity.ok(ApiResponse.success(Map.of("pid", pid)));
    }

    /**
     * Deactivate (soft-remove) an external MCP server for the current tenant.
     */
    @DeleteMapping("/mcp-servers/{pid}")
    public ResponseEntity<ApiResponse<Void>> deactivateMcpServer(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        mcpServerConfigService.deactivateServer(tenantId, pid);
        return ResponseEntity.ok(ApiResponse.success());
    }

    // ──────────────────────────────────────────────────────────────
    // Event-Driven Dispatch (F2) — test trigger endpoint
    // ──────────────────────────────────────────────────────────────

    /**
     * Manually fire a synthetic event to test event-driven agent dispatch.
     * <p>
     * Useful for debugging: verifies that event_triggers configurations on agent
     * definitions are correctly evaluated without having to wait for a real event.
     * <p>
     * Request body:
     * <ul>
     *   <li>eventType  — required (e.g. "entity_status_changed")</li>
     *   <li>modelCode  — optional (e.g. "crm_lead")</li>
     *   <li>eventData  — optional map of extra key/value pairs for condition matching</li>
     * </ul>
     */
    @PostMapping("/events/test-trigger")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> testEventTrigger(
            @RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();

        String eventType = (String) body.get("eventType");
        if (eventType == null || eventType.isBlank()) {
            return ResponseEntity.ok(ApiResponse.error("eventType is required"));
        }

        String modelCode = (String) body.get("modelCode");
        Map<String, Object> eventData = body.get("eventData") instanceof Map
                ? (Map<String, Object>) body.get("eventData") : Map.of();

        List<String> matched = agentEventDispatchService.findMatchingAgents(
                tenantId, eventType, modelCode, eventData);

        List<String> taskPids = List.of();
        if (!matched.isEmpty()) {
            taskPids = agentEventDispatchService.dispatchMatchedAgents(
                    tenantId, matched, eventType, eventData);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", eventType);
        result.put("modelCode", modelCode);
        result.put("matchedAgents", matched);
        result.put("createdTaskPids", taskPids);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ──────────────────────────────────────────────────────────────
    // Agent Organization Enrollment (Phase 6 — AI Employee)
    // ──────────────────────────────────────────────────────────────

    /**
     * Enroll an agent as a digital employee in the organization.
     *
     * <p>The agent must have a {@code system_user_id} (set during bootstrap seeding).
     * Creates an {@code org_employee} record (type=ai) and links it to a service
     * {@code tenant_member}. Returns the enrolled employee PID for confirmation.</p>
     *
     * <p>Request body:
     * <ul>
     *   <li>{@code departmentPid} — required, target department PID</li>
     *   <li>{@code positionPid}   — optional, position PID within the department</li>
     * </ul>
     */
    @PostMapping("/definitions/{agentPid}/enroll-employee")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enrollAsEmployee(
            @PathVariable String agentPid,
            @RequestBody Map<String, Object> body) {
        AgentDefinition agent = agentDefinitionService.findByPid(agentPid);
        if (agent == null) {
            return ResponseEntity.ok(ApiResponse.error("Agent not found: " + agentPid));
        }
        String departmentPid = (String) body.get("departmentPid");
        if (departmentPid == null || departmentPid.isBlank()) {
            return ResponseEntity.ok(ApiResponse.error("departmentPid is required"));
        }
        String positionPid = (String) body.get("positionPid");

        agentOrganizationService.enrollAsEmployee(agent.getId(), departmentPid, positionPid);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentPid", agentPid);
        result.put("enrolled", true);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Remove an agent from the org chart, deactivating its employee record.
     */
    @DeleteMapping("/definitions/{agentPid}/enroll-employee")
    public ResponseEntity<ApiResponse<Void>> removeFromOrg(@PathVariable String agentPid) {
        AgentDefinition agent = agentDefinitionService.findByPid(agentPid);
        if (agent == null) {
            return ResponseEntity.ok(ApiResponse.error("Agent not found: " + agentPid));
        }
        agentOrganizationService.removeFromOrg(agent.getId());
        return ResponseEntity.ok(ApiResponse.success());
    }

}

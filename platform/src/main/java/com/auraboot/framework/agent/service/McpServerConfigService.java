package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Registry service for external MCP server configurations.
 * <p>
 * Stores connection metadata (URL, transport type, auth) for external MCP servers
 * that agents can consume. Actual MCP client protocol handling is deferred to Phase 6+.
 * <p>
 * source_type 'mcp_external' in ab_agent_tool references servers registered here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerConfigService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * List all active MCP server configurations for a tenant.
     *
     * @param tenantId the tenant ID
     * @return list of server config maps (pid, server_name, server_url, transport_type, tool_count, last_synced_at)
     */
    public List<Map<String, Object>> listActiveServers(Long tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT pid, server_name, server_url, transport_type, tool_count, last_synced_at " +
                "FROM ab_agent_mcp_server " +
                "WHERE tenant_id = ? AND status = 'active' " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "ORDER BY server_name",
                tenantId);
    }

    /**
     * Register a new external MCP server for a tenant.
     *
     * @param tenantId      the tenant ID
     * @param name          human-readable server name (e.g. "GitHub MCP")
     * @param url           server URL or command path (e.g. "npx @modelcontextprotocol/server-github")
     * @param transportType STDIO / SSE / HTTP
     * @param authType      NONE / BEARER / API_KEY (nullable)
     * @param authConfig    auth config map (e.g. {"token": "ghp_xxx"}) — stored as JSONB, nullable
     * @return the generated pid of the new server entry
     */
    public String registerServer(Long tenantId, String name, String url,
                                  String transportType, String authType,
                                  Map<String, Object> authConfig) {
        String pid = UniqueIdGenerator.generate();
        String authJson = toJson(authConfig);
        jdbcTemplate.update(
                "INSERT INTO ab_agent_mcp_server " +
                "(pid, tenant_id, server_name, server_url, transport_type, auth_type, auth_config, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, NOW(), NOW())",
                pid, tenantId, name, url, transportType, authType, authJson);
        log.info("Registered MCP server: tenant={}, pid={}, name={}, transport={}",
                tenantId, pid, name, transportType);
        return pid;
    }

    /**
     * Deactivate (soft-delete) an MCP server so it no longer appears in active listings.
     *
     * @param tenantId the tenant ID (ensures cross-tenant isolation)
     * @param pid      the server pid
     */
    public void deactivateServer(Long tenantId, String pid) {
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_mcp_server SET status = 'inactive', updated_at = NOW() " +
                "WHERE tenant_id = ? AND pid = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                tenantId, pid);
        if (updated == 0) {
            log.warn("deactivateServer: no active server found for tenant={} pid={}", tenantId, pid);
        } else {
            log.info("Deactivated MCP server: tenant={}, pid={}", tenantId, pid);
        }
    }

    /**
     * Update the tool_count and last_synced_at for a server after a tool sync.
     * Called by future MCP client discovery phase.
     *
     * @param tenantId  the tenant ID
     * @param pid       the server pid
     * @param toolCount number of tools discovered
     */
    public void updateSyncResult(Long tenantId, String pid, int toolCount) {
        jdbcTemplate.update(
                "UPDATE ab_agent_mcp_server SET tool_count = ?, last_synced_at = NOW(), updated_at = NOW() " +
                "WHERE tenant_id = ? AND pid = ?",
                toolCount, tenantId, pid);
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize authConfig to JSON: {}", e.getMessage());
            return null;
        }
    }
}

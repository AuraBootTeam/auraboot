package com.auraboot.framework.agent.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A tenant-scoped external MCP server configuration.
 *
 * <p>The record contains decrypted connection material only while it is inside
 * the platform process. Callers must never serialize this object or include it
 * in logs. {@link #toString()} deliberately omits credentials and environment
 * values.
 */
public record McpServerTarget(
        Long tenantId,
        String pid,
        String serverName,
        String serverUrl,
        String transportType,
        String authType,
        Map<String, Object> authConfig,
        List<String> stdioArgs,
        Map<String, String> stdioEnv) {

    /**
     * Backward-compatible constructor used by protocol-focused tests and
     * in-process callers that do not resolve the target from the registry.
     */
    public McpServerTarget(String serverName, String serverUrl, String transportType,
                           String authType, Map<String, Object> authConfig) {
        this(null, null, serverName, serverUrl, transportType, authType,
                authConfig, List.of(), Map.of());
    }

    /** Column-name keys as stored in {@code ab_agent_mcp_server}. */
    public static McpServerTarget fromRow(Map<String, Object> row) {
        @SuppressWarnings("unchecked")
        Map<String, Object> auth = row.get("auth_config") instanceof Map
                ? (Map<String, Object>) row.get("auth_config")
                : null;
        @SuppressWarnings("unchecked")
        List<String> args = row.get("stdio_args") instanceof List
                ? (List<String>) row.get("stdio_args")
                : List.of();
        @SuppressWarnings("unchecked")
        Map<String, String> env = row.get("stdio_env") instanceof Map
                ? (Map<String, String>) row.get("stdio_env")
                : Map.of();
        return new McpServerTarget(
                row.get("tenant_id") instanceof Number n ? n.longValue() : null,
                (String) row.get("pid"),
                (String) row.get("server_name"),
                (String) row.get("server_url"),
                (String) row.get("transport_type"),
                (String) row.get("auth_type"),
                auth,
                args,
                env);
    }

    public String normalizedTransport() {
        if (transportType == null || transportType.isBlank()) {
            return serverUrl != null && serverUrl.startsWith("http")
                    ? "streamable_http" : "stdio";
        }
        String normalized = transportType.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "http", "streamablehttp", "streamable_http" -> "streamable_http";
            case "sse" -> "sse";
            case "stdio" -> "stdio";
            default -> normalized;
        };
    }

    public boolean isHttpTransport() {
        return "streamable_http".equals(normalizedTransport()) || "sse".equals(normalizedTransport());
    }

    public boolean isStdioTransport() {
        return "stdio".equals(normalizedTransport());
    }

    /** Stable map key for the one managed SDK client belonging to this row. */
    public String connectionKey() {
        return String.valueOf(tenantId) + ":" + (pid != null ? pid : serverName);
    }

    /**
     * Non-secret configuration fingerprint. Hash collisions only cause an
     * unnecessary reuse until the next explicit invalidation event; they never
     * cross the tenant/pid key boundary.
     */
    public int configFingerprint() {
        return Objects.hash(serverUrl, normalizedTransport(), authType, authConfig, stdioArgs, stdioEnv);
    }

    @Override
    public String toString() {
        return "McpServerTarget[tenantId=" + tenantId
                + ", pid=" + pid
                + ", serverName=" + serverName
                + ", serverUrl=" + serverUrl
                + ", transportType=" + normalizedTransport()
                + ", authType=" + authType
                + ", stdioArgsCount=" + (stdioArgs == null ? 0 : stdioArgs.size())
                + ", stdioEnvKeys=" + (stdioEnv == null ? List.of() : stdioEnv.keySet())
                + "]";
    }
}

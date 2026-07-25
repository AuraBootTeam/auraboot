package com.auraboot.framework.agent.provider;

import java.util.Map;

/**
 * A registered external MCP server, resolved into everything needed to reach it.
 *
 * <p>Carries the transport and credentials alongside the URL because both were
 * previously stored and then dropped: {@code listActiveServers} did not select
 * the auth columns and the client set no auth header, so a token entered in the
 * admin UI was written to the database and never used again. Passing a single
 * target keeps the connection details together so that cannot silently recur.
 *
 * @param serverName    registry name, used in tool codes and error messages
 * @param serverUrl     endpoint URL (HTTP transport) or command line (stdio)
 * @param transportType {@code HTTP} / {@code SSE} / {@code STDIO}, case-insensitive
 * @param authType      {@code NONE} / {@code BEARER} / {@code API_KEY}, nullable
 * @param authConfig    credential material, e.g. {@code {"token":"...","header":"X-API-Key"}}
 */
public record McpServerTarget(
        String serverName,
        String serverUrl,
        String transportType,
        String authType,
        Map<String, Object> authConfig) {

    /** Column-name keys as stored in {@code ab_agent_mcp_server}. */
    public static McpServerTarget fromRow(Map<String, Object> row) {
        @SuppressWarnings("unchecked")
        Map<String, Object> auth = row.get("auth_config") instanceof Map
                ? (Map<String, Object>) row.get("auth_config")
                : null;
        return new McpServerTarget(
                (String) row.get("server_name"),
                (String) row.get("server_url"),
                (String) row.get("transport_type"),
                (String) row.get("auth_type"),
                auth);
    }

    /** True when the transport is one this client speaks (HTTP / SSE over HTTP). */
    public boolean isHttpTransport() {
        if (transportType == null || transportType.isBlank()) {
            // Legacy rows predate the column; a URL-shaped value means HTTP.
            return serverUrl != null && serverUrl.startsWith("http");
        }
        String t = transportType.trim().toUpperCase();
        return t.equals("HTTP") || t.equals("SSE") || t.equals("STREAMABLE_HTTP");
    }

    /**
     * Never let credentials reach a log line or an error message shown to an
     * agent — {@code authConfig} is deliberately excluded.
     */
    @Override
    public String toString() {
        return "McpServerTarget[serverName=" + serverName
                + ", serverUrl=" + serverUrl
                + ", transportType=" + transportType
                + ", authType=" + authType + "]";
    }
}

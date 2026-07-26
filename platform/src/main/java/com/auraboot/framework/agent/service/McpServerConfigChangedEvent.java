package com.auraboot.framework.agent.service;

/**
 * Emitted whenever an MCP server connection configuration stops being valid.
 * Consumers close SDK sessions and invalidate tool catalogues by tenant/pid.
 */
public record McpServerConfigChangedEvent(Long tenantId, String pid, String reason) {
}

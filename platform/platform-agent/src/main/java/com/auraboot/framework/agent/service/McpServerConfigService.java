package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.McpServerTarget;
import com.auraboot.framework.agent.provider.McpStdioCommandPolicy;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.common.util.SsrfValidator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tenant-scoped registry for external MCP server configurations.
 *
 * <p>Secrets are encrypted before persistence and decrypted only for internal
 * connection targets. Controller/UI callers must use {@link #listSafeServers}
 * or {@link #exportSafeConfig}; neither method returns reusable credentials.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServerConfigService {

    static final Set<String> AUTH_SECRET_FIELDS = Set.of(
            "password", "apiKey", "token", "bearerToken", "clientSecret", "secret");

    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
            new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FieldEncryptionService fieldEncryptionService;
    private final McpStdioCommandPolicy stdioCommandPolicy;
    private final ApplicationEventPublisher eventPublisher;

    /** List all active connection targets for runtime use. */
    public List<Map<String, Object>> listActiveServers(Long tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                selectConnectionColumns()
                        + " WHERE tenant_id = ? AND status = 'active' "
                        + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) "
                        + "ORDER BY server_name",
                tenantId);
        rows.forEach(this::decodeConnectionColumns);
        return rows;
    }

    /** Direct lookup used by tool execution; avoids scanning a tenant's full registry. */
    public Map<String, Object> findActiveServer(Long tenantId, String serverName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                selectConnectionColumns()
                        + " WHERE tenant_id = ? AND server_name = ? AND status = 'active' "
                        + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) "
                        + "ORDER BY updated_at DESC LIMIT 1",
                tenantId, serverName);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        decodeConnectionColumns(row);
        return row;
    }

    /**
     * Safe registry projection for management APIs. It exposes whether secrets
     * exist and which stdio environment keys are configured, never their values.
     */
    public List<Map<String, Object>> listSafeServers(Long tenantId) {
        List<Map<String, Object>> safe = new ArrayList<>();
        for (Map<String, Object> internal : listActiveServers(tenantId)) {
            Map<String, Object> row = new LinkedHashMap<>(internal);
            Map<String, Object> auth = asObjectMap(row.remove("auth_config"));
            Map<String, String> env = asStringMap(row.remove("stdio_env"));
            row.put("auth_configured", auth != null && !auth.isEmpty());
            row.put("stdio_env_keys", env == null ? List.of() : env.keySet().stream().sorted().toList());
            safe.add(row);
        }
        return safe;
    }

    /** Backward-compatible registration overload. */
    public String registerServer(Long tenantId, String name, String url,
                                 String transportType, String authType,
                                 Map<String, Object> authConfig) {
        return registerServer(tenantId, name, url, transportType, authType,
                authConfig, List.of(), Map.of());
    }

    @Transactional
    public String registerServer(Long tenantId, String name, String endpointOrCommand,
                                 String transportType, String authType,
                                 Map<String, Object> authConfig,
                                 List<String> stdioArgs,
                                 Map<String, String> stdioEnv) {
        validateRequired(tenantId, name, endpointOrCommand);
        String normalizedTransport = normalizeTransport(transportType, endpointOrCommand);
        McpServerTarget target = new McpServerTarget(
                tenantId, null, name, endpointOrCommand, normalizedTransport,
                normalizeNullable(authType), authConfig,
                stdioArgs == null ? List.of() : List.copyOf(stdioArgs),
                stdioEnv == null ? Map.of() : Map.copyOf(stdioEnv));
        validateTarget(target);

        String pid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_mcp_server "
                        + "(pid, tenant_id, server_name, server_url, transport_type, auth_type, "
                        + "auth_config, stdio_args, transport_config, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, NOW(), NOW())",
                pid, tenantId, name, endpointOrCommand, normalizedTransport,
                normalizeNullable(authType), encryptedAuthJson(authConfig),
                toJson(stdioArgs == null ? List.of() : stdioArgs),
                encryptedTransportJson(stdioEnv));
        eventPublisher.publishEvent(new McpServerConfigChangedEvent(tenantId, pid, "registered"));
        log.info("Registered MCP server: tenant={}, pid={}, name={}, transport={}",
                tenantId, pid, name, normalizedTransport);
        return pid;
    }

    @Transactional
    public void updateServer(Long tenantId, String pid, String name, String endpointOrCommand,
                             String transportType, String authType,
                             Map<String, Object> authConfig,
                             List<String> stdioArgs,
                             Map<String, String> stdioEnv) {
        validateRequired(tenantId, name, endpointOrCommand);
        String normalizedTransport = normalizeTransport(transportType, endpointOrCommand);

        Map<String, Object> existing = findByPidInternal(tenantId, pid);
        if (existing == null) {
            throw new IllegalArgumentException("MCP server not found: " + pid);
        }
        Map<String, Object> effectiveAuth = authConfig == null || authConfig.isEmpty()
                ? asObjectMap(existing.get("auth_config")) : authConfig;
        String effectiveAuthType = authType == null || authType.isBlank()
                ? asText(existing.get("auth_type")) : authType;
        Map<String, String> effectiveEnv = stdioEnv == null || stdioEnv.isEmpty()
                ? asStringMap(existing.get("stdio_env")) : stdioEnv;
        List<String> effectiveArgs = stdioArgs == null
                ? asStringList(existing.get("stdio_args")) : stdioArgs;

        McpServerTarget target = new McpServerTarget(
                tenantId, pid, name, endpointOrCommand, normalizedTransport,
                normalizeNullable(effectiveAuthType), effectiveAuth,
                effectiveArgs == null ? List.of() : List.copyOf(effectiveArgs),
                effectiveEnv == null ? Map.of() : Map.copyOf(effectiveEnv));
        validateTarget(target);

        jdbcTemplate.update(
                "UPDATE ab_agent_mcp_server SET server_name = ?, server_url = ?, transport_type = ?, "
                        + "auth_type = ?, auth_config = ?::jsonb, stdio_args = ?::jsonb, "
                        + "transport_config = ?::jsonb, status = 'active', deleted_flag = FALSE, "
                        + "tool_count = 0, last_synced_at = NULL, last_sync_error = NULL, updated_at = NOW() "
                        + "WHERE tenant_id = ? AND pid = ?",
                name, endpointOrCommand, normalizedTransport, normalizeNullable(effectiveAuthType),
                encryptedAuthJson(effectiveAuth), toJson(effectiveArgs == null ? List.of() : effectiveArgs),
                encryptedTransportJson(effectiveEnv), tenantId, pid);
        eventPublisher.publishEvent(new McpServerConfigChangedEvent(tenantId, pid, "updated"));
    }

    @Transactional
    public void deactivateServer(Long tenantId, String pid) {
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_mcp_server SET status = 'inactive', updated_at = NOW() "
                        + "WHERE tenant_id = ? AND pid = ?",
                tenantId, pid);
        if (updated == 0) {
            log.warn("deactivateServer: no server found for tenant={} pid={}", tenantId, pid);
            return;
        }
        eventPublisher.publishEvent(new McpServerConfigChangedEvent(tenantId, pid, "deactivated"));
        log.info("Deactivated MCP server: tenant={}, pid={}", tenantId, pid);
    }

    /** Record a successful live tools/list result. */
    public void updateSyncResult(Long tenantId, String pid, int toolCount) {
        jdbcTemplate.update(
                "UPDATE ab_agent_mcp_server SET tool_count = ?, last_synced_at = NOW(), "
                        + "last_sync_error = NULL, updated_at = NOW() "
                        + "WHERE tenant_id = ? AND pid = ?",
                toolCount, tenantId, pid);
    }

    /** Record a bounded, credential-free failure message without forging a success timestamp. */
    public void updateSyncFailure(Long tenantId, String pid, String errorMessage) {
        String bounded = errorMessage == null ? "Unknown MCP synchronization failure"
                : errorMessage.substring(0, Math.min(1000, errorMessage.length()));
        jdbcTemplate.update(
                "UPDATE ab_agent_mcp_server SET last_sync_error = ?, updated_at = NOW() "
                        + "WHERE tenant_id = ? AND pid = ?",
                bounded, tenantId, pid);
    }

    /**
     * Safe portable configuration for CLI pull. Secrets are intentionally
     * absent; callers receive {@code secretRequired} and preserve/re-enter local
     * values.
     */
    public Map<String, Object> exportSafeConfig(Long tenantId) {
        Map<String, Object> servers = new LinkedHashMap<>();
        for (Map<String, Object> row : listSafeServers(tenantId)) {
            Map<String, Object> config = new LinkedHashMap<>();
            String transport = String.valueOf(row.get("transport_type"));
            config.put("transport", transport);
            if ("stdio".equals(transport)) {
                config.put("command", row.get("server_url"));
                config.put("args", row.getOrDefault("stdio_args", List.of()));
                config.put("envKeys", row.getOrDefault("stdio_env_keys", List.of()));
            } else {
                config.put("url", row.get("server_url"));
            }
            config.put("authType", row.get("auth_type"));
            config.put("secretRequired", Boolean.TRUE.equals(row.get("auth_configured"))
                    || !((List<?>) row.getOrDefault("stdio_env_keys", List.of())).isEmpty());
            servers.put(String.valueOf(row.get("server_name")), config);
        }
        return Map.of("servers", servers);
    }

    /**
     * Import the CLI's portable configuration without conflating it with the
     * MCP protocol connection. Missing entries are never deleted implicitly.
     *
     * @return a deterministic create/update plan, also used for dry-run output
     */
    @Transactional
    public List<Map<String, Object>> syncPortableServers(
            Long tenantId, Map<String, Object> requestedServers, boolean dryRun) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (requestedServers == null) {
            throw new IllegalArgumentException("servers is required");
        }

        Map<String, Map<String, Object>> currentByName = new LinkedHashMap<>();
        for (Map<String, Object> current : listSafeServers(tenantId)) {
            currentByName.put(String.valueOf(current.get("server_name")), current);
        }

        List<Map<String, Object>> plan = new ArrayList<>();
        requestedServers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String name = entry.getKey();
                    Map<String, Object> config = asObjectMap(entry.getValue());
                    if (name == null || name.isBlank() || config == null) {
                        throw new IllegalArgumentException(
                                "Each MCP server must have a non-empty name and object config");
                    }

                    String requestedTransport = asText(config.get("transport"));
                    String endpoint = firstNonBlank(
                            asText(config.get("url")), asText(config.get("command")));
                    if (endpoint == null) {
                        throw new IllegalArgumentException(
                                "MCP server '" + name + "' requires url or command");
                    }
                    String transport = normalizeTransport(requestedTransport, endpoint);
                    Map<String, Object> authConfig = asObjectMap(config.get("authConfig"));
                    Map<String, String> environment = asStringMap(config.get("env"));
                    List<String> arguments = asStringList(config.get("args"));
                    String authType = asText(config.get("authType"));
                    Map<String, Object> current = currentByName.get(name);
                    String action = current == null
                            ? "create"
                            : portableConfigChanged(
                                    current, endpoint, transport, authType,
                                    authConfig, arguments, environment)
                                    ? "update" : "unchanged";

                    if (!dryRun && !"unchanged".equals(action)) {
                        if (current == null) {
                            registerServer(
                                    tenantId, name, endpoint, transport, authType,
                                    authConfig,
                                    arguments == null ? List.of() : arguments,
                                    environment == null ? Map.of() : environment);
                        } else {
                            updateServer(
                                    tenantId, String.valueOf(current.get("pid")),
                                    name, endpoint, transport, authType, authConfig,
                                    arguments, environment);
                        }
                    }
                    plan.add(Map.of(
                            "name", name,
                            "action", action,
                            "transport", transport,
                            "secretSupplied", authConfig != null || environment != null));
                });
        return plan;
    }

    private boolean portableConfigChanged(
            Map<String, Object> current,
            String endpoint,
            String transport,
            String authType,
            Map<String, Object> authConfig,
            List<String> arguments,
            Map<String, String> environment) {
        if (!Objects.equals(endpoint, asText(current.get("server_url")))
                || !Objects.equals(transport, asText(current.get("transport_type")))) {
            return true;
        }
        if (authType != null
                && !Objects.equals(
                        normalizeNullable(authType),
                        normalizeNullable(asText(current.get("auth_type"))))) {
            return true;
        }
        if (arguments != null
                && !Objects.equals(arguments, asStringList(current.get("stdio_args")))) {
            return true;
        }

        // Safe projections cannot expose existing secret values for comparison.
        // A non-empty secret payload is therefore an explicit rotation request;
        // omission/empty values mean "preserve" and remain idempotent.
        return (authConfig != null && !authConfig.isEmpty())
                || (environment != null && !environment.isEmpty());
    }

    private Map<String, Object> findByPidInternal(Long tenantId, String pid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                selectConnectionColumns()
                        + " WHERE tenant_id = ? AND pid = ? "
                        + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) LIMIT 1",
                tenantId, pid);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        decodeConnectionColumns(row);
        return row;
    }

    private String selectConnectionColumns() {
        return "SELECT pid, tenant_id, server_name, server_url, transport_type, auth_type, "
                + "auth_config, stdio_args, transport_config, tool_count, last_synced_at, last_sync_error "
                + "FROM ab_agent_mcp_server";
    }

    private void decodeConnectionColumns(Map<String, Object> row) {
        String serverName = String.valueOf(row.get("server_name"));
        row.put("auth_config", parseEncryptedAuth(row.get("auth_config"), serverName));
        row.put("stdio_args", parseStringList(row.get("stdio_args"), serverName));
        row.put("stdio_env", parseEncryptedEnvironment(row.get("transport_config"), serverName));
        row.remove("transport_config");
    }

    private void validateTarget(McpServerTarget target) {
        switch (target.normalizedTransport()) {
            case "streamable_http", "sse" -> SsrfValidator.validate(target.serverUrl());
            case "stdio" -> stdioCommandPolicy.validate(target);
            default -> throw new IllegalArgumentException(
                    "Unsupported MCP transport: " + target.transportType());
        }
    }

    private void validateRequired(Long tenantId, String name, String endpointOrCommand) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server name is required");
        }
        if (endpointOrCommand == null || endpointOrCommand.isBlank()) {
            throw new IllegalArgumentException("MCP endpoint or executable is required");
        }
    }

    static String normalizeTransport(String transportType, String endpointOrCommand) {
        if (transportType == null || transportType.isBlank()) {
            return endpointOrCommand != null && endpointOrCommand.startsWith("http")
                    ? "streamable_http" : "stdio";
        }
        String normalized = transportType.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "http", "streamablehttp", "streamable_http" -> "streamable_http";
            case "sse", "stdio" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported MCP transport: " + transportType);
        };
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String encryptedAuthJson(Map<String, Object> authConfig) {
        String json = toJson(authConfig);
        return fieldEncryptionService.encryptJsonFields(json, AUTH_SECRET_FIELDS);
    }

    private String encryptedTransportJson(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return "{}";
        }
        Map<String, String> encrypted = new LinkedHashMap<>();
        environment.forEach((key, value) -> encrypted.put(key, fieldEncryptionService.encrypt(value)));
        return toJson(Map.of("env", encrypted));
    }

    private Map<String, Object> parseEncryptedAuth(Object raw, String serverName) {
        String json = rawJson(raw);
        if (json == null) {
            return null;
        }
        String decrypted = fieldEncryptionService.decryptJsonFields(json, AUTH_SECRET_FIELDS);
        try {
            return objectMapper.readValue(decrypted, OBJECT_MAP);
        } catch (JsonProcessingException error) {
            throw malformedConfig(serverName, "auth_config", error);
        }
    }

    private Map<String, String> parseEncryptedEnvironment(Object raw, String serverName) {
        String json = rawJson(raw);
        if (json == null) {
            return Map.of();
        }
        try {
            Map<String, Object> config = objectMapper.readValue(json, OBJECT_MAP);
            Map<String, String> encrypted = asStringMap(config.get("env"));
            if (encrypted == null || encrypted.isEmpty()) {
                return Map.of();
            }
            Map<String, String> decrypted = new LinkedHashMap<>();
            encrypted.forEach((key, value) -> decrypted.put(key, fieldEncryptionService.decrypt(value)));
            return decrypted;
        } catch (JsonProcessingException error) {
            throw malformedConfig(serverName, "transport_config", error);
        }
    }

    private List<String> parseStringList(Object raw, String serverName) {
        String json = rawJson(raw);
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException error) {
            throw malformedConfig(serverName, "stdio_args", error);
        }
    }

    private String rawJson(Object raw) {
        if (raw == null) {
            return null;
        }
        String json = raw.toString();
        return json.isBlank() || "null".equals(json) ? null : json;
    }

    private IllegalStateException malformedConfig(String serverName, String column, Exception cause) {
        return new IllegalStateException(
                "MCP server '" + serverName + "' has invalid " + column + "; re-save its configuration",
                cause);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("MCP configuration is not JSON serializable", error);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private Map<String, String> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null && item != null) {
                result.put(String.valueOf(key), String.valueOf(item));
            }
        });
        return result;
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return null;
        }
        return raw.stream().map(String::valueOf).toList();
    }

    private String asText(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String firstNonBlank(String first, String second) {
        return first != null ? first : second;
    }
}

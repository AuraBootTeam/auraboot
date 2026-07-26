package com.auraboot.framework.agent.handler;

import com.auraboot.framework.agent.service.McpServerConfigService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.DryRunSafe;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Security boundary for DSL MCP registry commands.
 *
 * <p>Generic CRUD must never persist {@code auth_config} or stdio environment
 * values. The page submits transient secret inputs to this handler, which
 * delegates all writes to {@link McpServerConfigService}'s validation and
 * encryption path.
 */
@DryRunSafe
@Component("mcpServerCommandHandler")
@RequiredArgsConstructor
public class McpServerCommandHandler implements CommandHandler {

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP =
            new TypeReference<>() {};

    private final McpServerConfigService configService;
    private final ObjectMapper objectMapper;

    @Override
    public String getHandlerName() {
        return "mcpServerCommandHandler";
    }

    @Override
    public Map<String, Object> execute(CommandHandlerContext context) {
        if (context == null || context.getTenantId() == null) {
            throw new BusinessException("Tenant is required for MCP server management");
        }
        String command = context.getCommandCode();
        String pid = targetPid(context);
        if ("acp:delete_mcp_server".equals(command)) {
            requirePid(pid);
            if (!context.isDryRun()) {
                configService.deactivateServer(context.getTenantId(), pid);
            }
            return result("deactivate", pid, context.isDryRun());
        }
        if (!"acp:create_mcp_server".equals(command)
                && !"acp:update_mcp_server".equals(command)) {
            throw new BusinessException("Unsupported MCP server command: " + command);
        }

        Map<String, Object> payload =
                context.getPayload() == null ? Map.of() : context.getPayload();
        String name = requiredText(payload, "server_name");
        String endpoint = requiredText(payload, "server_url");
        String transport = text(payload, "transport_type");
        String authType = normalizeAuthType(text(payload, "auth_type"));
        List<String> args = parseList(payload.get("stdio_args"));
        Map<String, String> environment = parseMap(payload.get("mcp_stdio_env"));
        Map<String, Object> authConfig = authConfig(payload, authType);
        boolean create = "acp:create_mcp_server".equals(command);
        if (!create) {
            requirePid(pid);
        }

        if (create && authRequiresSecret(authType)
                && (authConfig == null || authConfig.isEmpty())) {
            throw new BusinessException(
                    "Authentication secret is required when creating an authenticated MCP server");
        }
        if (context.isDryRun()) {
            return result(create ? "create" : "update", pid, true);
        }

        if (create) {
            pid = configService.registerServer(
                    context.getTenantId(), name, endpoint, transport, authType,
                    authConfig,
                    args == null ? List.of() : args,
                    environment == null ? Map.of() : environment);
        } else {
            configService.updateServer(
                    context.getTenantId(), pid, name, endpoint, transport, authType,
                    authConfig, args, environment);
        }
        return result(create ? "create" : "update", pid, false);
    }

    private Map<String, Object> authConfig(
            Map<String, Object> payload, String authType) {
        if (!authRequiresSecret(authType)) {
            return "none".equals(authType) ? Map.of() : null;
        }
        String secret = text(payload, "mcp_auth_secret");
        if (secret == null) {
            return null;
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("token", secret);
        if ("api_key".equals(authType)) {
            String header = text(payload, "mcp_api_key_header");
            if (header != null) {
                config.put("header", header);
            }
        }
        return config;
    }

    private List<String> parseList(Object raw) {
        if (raw == null || raw instanceof String text && text.isBlank()) {
            return null;
        }
        if (raw instanceof List<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        try {
            return objectMapper.readValue(String.valueOf(raw), STRING_LIST);
        } catch (JsonProcessingException error) {
            throw new BusinessException("stdio_args must be a JSON string array");
        }
    }

    private Map<String, String> parseMap(Object raw) {
        if (raw == null || raw instanceof String text && text.isBlank()) {
            return null;
        }
        if (raw instanceof Map<?, ?> values) {
            Map<String, String> result = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    result.put(String.valueOf(key), String.valueOf(value));
                }
            });
            return result;
        }
        try {
            return objectMapper.readValue(String.valueOf(raw), STRING_MAP);
        } catch (JsonProcessingException error) {
            throw new BusinessException("mcp_stdio_env must be a JSON object");
        }
    }

    private Map<String, Object> result(String action, String pid, boolean dryRun) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("handlerExecuted", true);
        result.put("action", action);
        result.put("dryRun", dryRun);
        if (pid != null) {
            result.put("mcpServerPid", pid);
        }
        return result;
    }

    private String targetPid(CommandHandlerContext context) {
        if (context.getTargetRecordId() != null
                && !context.getTargetRecordId().isBlank()) {
            return context.getTargetRecordId();
        }
        Map<String, Object> payload = context.getPayload();
        if (payload == null) {
            return null;
        }
        for (String key : List.of("pid", "recordPid", "mcp_server_pid")) {
            String value = text(payload, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String requiredText(Map<String, Object> payload, String key) {
        String value = text(payload, key);
        if (value == null) {
            throw new BusinessException(key + " is required");
        }
        return value;
    }

    private String text(Map<String, Object> payload, String key) {
        Object raw = payload.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private String normalizeAuthType(String value) {
        return value == null ? null
                : value.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private boolean authRequiresSecret(String authType) {
        return "bearer".equals(authType) || "api_key".equals(authType);
    }

    private void requirePid(String pid) {
        if (pid == null || pid.isBlank()) {
            throw new BusinessException("Target MCP server pid is required");
        }
    }
}

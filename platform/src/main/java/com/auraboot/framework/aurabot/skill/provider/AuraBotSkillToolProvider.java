package com.auraboot.framework.aurabot.skill.provider;

import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProvider;
import com.auraboot.framework.aurabot.skill.AuraBotSkillRegistry;
import com.auraboot.framework.aurabot.skill.RiskLevel;
import com.auraboot.framework.aurabot.skill.SkillMeta;
import com.auraboot.framework.aurabot.skill.error.SkillMessageSourceConfig;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 5th {@link ToolProvider} — surfaces V3 skills (registered in
 * {@link AuraBotSkillRegistry}) as LLM-facing tools for the V2 chat tool path.
 *
 * <p>Tools are derived live from the registry per request and never persisted
 * into {@code ab_agent_tool}; permission gating mirrors
 * {@code AuraBotSkillController.resolveCurrentUserPermissions}.
 *
 * <p>Tool code convention: {@code aurabot:<skillName>}, e.g.
 * {@code aurabot:model:create}. The LLM-facing {@code toolName} keeps the
 * inner colon (matches the existing skill name format).
 *
 * <p>{@link #execute(Long, String, Map)} intentionally fails: aurabot tools
 * must flow through the chat-aware {@code SkillToolExecutor} (Task 3) +
 * {@code ChatToolExecutor} branch (Task 4) so dryRun / risk gating /
 * chat-suspend semantics are honoured. Direct provider invocations would
 * bypass those checks.
 */
@Slf4j
@Component
public class AuraBotSkillToolProvider implements ToolProvider {

    public static final String PROVIDER_CODE = "aurabot";
    private static final String TOOL_CODE_PREFIX = PROVIDER_CODE + ":";

    private final AuraBotSkillRegistry skillRegistry;
    private final UserPermissionService userPermissionService;
    private final PermissionMapper permissionMapper;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public AuraBotSkillToolProvider(
            AuraBotSkillRegistry skillRegistry,
            UserPermissionService userPermissionService,
            PermissionMapper permissionMapper,
            ObjectMapper objectMapper,
            @Qualifier(SkillMessageSourceConfig.BEAN_NAME) MessageSource messageSource) {
        this.skillRegistry = skillRegistry;
        this.userPermissionService = userPermissionService;
        this.permissionMapper = permissionMapper;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public List<ToolDefinition> discover(ToolDiscoveryContext ctx) {
        Set<String> userPerms = resolvePermissions(ctx == null ? null : ctx.getUserId());
        return skillRegistry.list(userPerms).stream()
                .map(this::toToolDefinition)
                .toList();
    }

    @Override
    public ProviderExecutionResult execute(Long tenantId, String toolCode, Map<String, Object> params) {
        // Direct execution bypasses dryRun / risk gating / chat-suspend; the
        // chat path (ChatToolExecutor → SkillToolExecutor, Tasks 3-4) is the
        // only supported invocation site. Surface a loud failure for misuse.
        log.warn("AuraBotSkillToolProvider.execute called directly for {} — chat path bypassed", toolCode);
        return ProviderExecutionResult.builder()
                .success(false)
                .errorMessage("aurabot: tools must be invoked via the chat tool path "
                        + "(ChatToolExecutor branch). Direct provider execute not supported.")
                .build();
    }

    @Override
    public boolean handles(String toolCode) {
        return toolCode != null && toolCode.startsWith(TOOL_CODE_PREFIX);
    }

    private ToolDefinition toToolDefinition(SkillMeta meta) {
        // SkillRegistry.toMeta serialises riskLevel as enum.name() (uppercase),
        // so fromCode tolerates both forms. requiresConfirmation/Approval is
        // derived once via the enum's atLeast() check.
        RiskLevel risk = RiskLevel.fromCode(meta.getRiskLevel());
        boolean requiresConfirm = risk.atLeast(RiskLevel.MEDIUM);

        // displayName is an i18n key (e.g. "aurabot.skill.model.create.displayName");
        // resolve via the skill MessageSource bundle. Fall back to the key
        // itself if unresolved so missing translations are visible to ops.
        String display = resolveI18n(meta.getDisplayName(), meta.getDisplayName());
        String descKey = "aurabot.skill." + meta.getName().replace(":", ".") + ".description";
        String description = resolveI18n(descKey, display);

        Map<String, Object> schemaMap = jsonNodeToMap(meta.getParamsSchema());

        return ToolDefinition.builder()
                .toolCode(TOOL_CODE_PREFIX + meta.getName())
                .toolName(meta.getName())                              // LLM-facing
                .description(description)
                .providerCode(PROVIDER_CODE)
                .toolType("AURABOT_SKILL")
                .sourceCode(meta.getName())
                .riskLevel(meta.getRiskLevel())
                .requiresApproval(requiresConfirm)
                .requiresConfirmation(requiresConfirm)
                .parameterSchema(schemaMap)
                .build();
    }

    private String resolveI18n(String key, String fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        // MessageSource.getMessage(key, args, defaultMessage, locale) returns
        // the default when no entry exists — no exception in the happy path,
        // so no broad catch is needed here.
        return messageSource.getMessage(key, null, fallback, Locale.getDefault());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonNodeToMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Collections.emptyMap();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private Set<String> resolvePermissions(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Set<Long> ids = userPermissionService.getUserPermissionIds(userId);
        if (ids == null || ids.isEmpty()) {
            return Collections.emptySet();
        }
        List<Permission> perms = permissionMapper.findByIds(new ArrayList<>(ids));
        return perms.stream()
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

package com.auraboot.framework.meta.synthesized;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Lazily seeds model-bound {@code dynamic.operation.model} command definitions for the
 * current tenant when {@code aura.command.synthesized-dynamic-writes=true}.
 * Command definitions are tenant-scoped (TenantLine interceptor), so seeding
 * happens per tenant on first use rather than at startup; insertIdempotent
 * keeps concurrent first-writes safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicWriteCommandSeedService {

    private final CommandDefinitionMapper commandDefinitionMapper;
    private final ObjectMapper objectMapper;

    /**
     * Ensures the definition exists for the current tenant and returns its
     * code. Safe to call on every synthesized write.
     */
    public String ensureSeeded(String operation, String modelCode) {
        if (!DynamicWriteCommandHandler.ALL.contains(operation) || modelCode == null
                || !modelCode.matches("[A-Za-z][A-Za-z0-9_.:-]*")) {
            throw new IllegalArgumentException("A supported dynamic operation and model are required");
        }
        String code = operation + "." + modelCode;
        Map<String, Object> expectedConfig = Map.of("handler", operation,
                "type", DynamicWriteCommandHandler.permissionSuffix(operation),
                "handlerParams", Map.of("invocationAuthorization", "handler"));
        String config;
        String targets;
        try {
            config = objectMapper.writeValueAsString(expectedConfig);
            targets = objectMapper.writeValueAsString(List.of(modelCode));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot encode synthesized command metadata", invalid);
        }
        CommandDefinition existing = commandDefinitionMapper.findCurrentByCode(code);
        if (existing != null) {
            requireMatching(existing, modelCode, expectedConfig);
            return code;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Synthesized dynamic writes require an initialized tenant context");
        }
        CommandDefinition def = new CommandDefinition();
        def.setPid(UniqueIdGenerator.generate());
        def.setTenantId(tenantId);
        def.setCode(code);
        def.setModelCode(modelCode);
        def.setDisplayName("Synthesized " + operation + " for " + modelCode);
        def.setDescription("Platform-seeded synthesized command routing the dynamic CRUD surface through the pipeline (aura.command.synthesized-dynamic-writes)");
        def.setInputSchema("{}");
        def.setTargetModels(targets);
        // executionConfig drives the pipeline: handler + handler-owned invocation authorization
        def.setExecutionConfig(config);
        def.setCmdRiskLevel("L2");
        def.setVersion(1);
        def.setIsCurrent(true);
        def.setRowVersion(1);
        def.setStatus(StatusConstants.PUBLISHED);
        def.setDeletedFlag(false);
        def.setCreatedAt(Instant.now());
        def.setUpdatedAt(Instant.now());
        if (commandDefinitionMapper.insertIdempotent(def) == 0) {
            requireMatching(commandDefinitionMapper.findCurrentByCode(code), modelCode, expectedConfig);
        }
        log.info("Seeded synthesized dynamic write command {} for tenant {}", code, tenantId);
        return code;
    }

    private void requireMatching(CommandDefinition existing, String modelCode, Map<String, Object> expectedConfig) {
        try {
            if (existing != null && modelCode.equals(existing.getModelCode())
                    && StatusConstants.PUBLISHED.equals(existing.getStatus())
                    && existing.getExecutionConfig() != null
                    && expectedConfig.equals(objectMapper.readValue(existing.getExecutionConfig(), Map.class))) return;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid synthesized command definition", invalid);
        }
        throw new IllegalStateException("Conflicting synthesized command definition for model " + modelCode);
    }
}

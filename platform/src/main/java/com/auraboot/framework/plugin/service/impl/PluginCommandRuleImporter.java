package com.auraboot.framework.plugin.service.impl;

import static com.auraboot.framework.plugin.service.PluginResourceRecords.createResourceRecord;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.plugin.dto.imports.BindingRuleDTO;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.common.constant.StatusConstants;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/** Imports commands and binding rules. */
final class PluginCommandRuleImporter {
    // Preserve virtual calls on the original facade receiver.
    private final PluginResourceImporterImpl facadeReceiver;
    private final CommandService commandService;
    private final CommandDefinitionMapper commandDefinitionMapper;
    private final BindingRuleMapper bindingRuleMapper;
    private final com.auraboot.framework.meta.service.impl.CommandMetadataCacheService commandMetadataCache;
    private final Logger log;
    private final PluginImportSupport support;

    PluginCommandRuleImporter(
            PluginResourceImporterImpl facadeReceiver,
            CommandService commandService,
            CommandDefinitionMapper commandDefinitionMapper,
            BindingRuleMapper bindingRuleMapper,
            com.auraboot.framework.meta.service.impl.CommandMetadataCacheService commandMetadataCache,
            Logger log,
            PluginImportSupport support) {
        this.facadeReceiver = facadeReceiver;
        this.commandService = commandService;
        this.commandDefinitionMapper = commandDefinitionMapper;
        this.bindingRuleMapper = bindingRuleMapper;
        this.commandMetadataCache = commandMetadataCache;
        this.log = log;
        this.support = support;
    }

    public boolean checkCommandExists(Long tenantId, String code) {
        try {
            return commandService.findByCode(code) != null;
        } catch (com.auraboot.framework.meta.exception.CommandNotFoundException absent) {
            return false;
        }
    }

    public PluginResource importCommand(CommandDefinitionDTO dto, String pluginPid, String importId,
                                         Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                         Boolean autoPublish) {
        // Warn about inline bindingRules — they are silently ignored during command import.
        // Binding rules must be in a separate bindingRules.json registered in plugin.json resourceDirs.
        if (dto.getBindingRules() != null && !dto.getBindingRules().isEmpty()) {
            log.warn("Command '{}' has inline 'bindingRules' in commands.json — "
                    + "this field is ignored during import. "
                    + "Use a separate bindingRules.json file registered in plugin.json resourceDirs.bindingRules.",
                    PluginImportSupport.logSafe(dto.getCode()));
        }

        boolean exists = facadeReceiver.checkCommandExists(tenantId, dto.getCode());
        if (!exists) {
            // A previous uninstall leaves the command soft-deleted; the idempotent
            // insert (ON CONFLICT (tenant_id, code, version) DO NOTHING) would then
            // silently skip the insert and the auto-publish would fail on the
            // never-persisted pid. Resurrect the retired row so the update path
            // refreshes and republishes it in place.
            String resurrectedPid = support.resurrectSoftDeleted("ab_command_definition", "code",
                    dto.getCode(), tenantId, pluginPid, dto.getEffectiveExtension());
            if (resurrectedPid != null) {
                log.info("Resurrected soft-deleted command {} as {} for plugin reimport",
                        PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(resurrectedPid));
                exists = facadeReceiver.checkCommandExists(tenantId, dto.getCode());
            }
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Command already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.COMMAND,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.COMMAND, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.COMMAND, PluginImportSupport.logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.COMMAND,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing command in place (bypassing DRAFT status check for plugin reimport)
            com.auraboot.framework.meta.dto.CommandDefinitionDTO existingCmd = commandService.findByCode(dto.getCode());

            Map<String, Object> consolidatedConfig = dto.getConsolidatedExecutionConfig();
            String inputSchemaJson = dto.getInputSchema() != null ? support.toJson(dto.getInputSchema()) : "{}";
            String targetModelsJson = dto.getTargetModels() != null ? support.toJson(dto.getTargetModels()) : "[]";
            String executionConfigJson = consolidatedConfig != null ? support.toJson(consolidatedConfig) : "{}";
            String extensionJson = support.toJson(dto.getEffectiveExtension());
            String cmdRiskLevel = resolveCommandRiskLevel(dto, consolidatedConfig);

            commandDefinitionMapper.updateForPluginImport(
                dto.getEffectiveDisplayName(),
                dto.getDescription(),
                dto.getModelCode(),
                inputSchemaJson,
                targetModelsJson,
                executionConfigJson,
                extensionJson,
                cmdRiskLevel,
                pluginPid,
                existingCmd.getPid(),
                tenantId);

            commandMetadataCache.evictAll();
            log.info("Command updated in place for plugin reimport: code={}, pid={}",
                     PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(existingCmd.getPid()));

            // Auto-publish if requested — skip if already PUBLISHED to avoid rollback-only tx
            if (Boolean.TRUE.equals(autoPublish) && !StatusConstants.PUBLISHED.equals(existingCmd.getStatus())) {
                commandService.publish(existingCmd.getPid());
            }

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.COMMAND,
                    existingCmd.getPid(), null, dto.getCode(), dto.getEffectiveDisplayName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Create new command via service with pluginPid
            CommandDefinitionCreateRequest request = new CommandDefinitionCreateRequest();
            request.setCode(dto.getCode());
            request.setDisplayName(dto.getEffectiveDisplayName());
            request.setDescription(dto.getDescription());
            request.setModelCode(dto.getModelCode());
            request.setInputSchema(dto.getInputSchema() != null ? support.toJson(dto.getInputSchema()) : null);
            request.setTargetModels(dto.getTargetModels() != null ? support.toJson(dto.getTargetModels()) : null);
            // Use consolidated config that merges structured ExecutionConfig with DSL extended fields
            Map<String, Object> consolidatedConfig = dto.getConsolidatedExecutionConfig();
            request.setExecutionConfig(consolidatedConfig != null ? support.toJson(consolidatedConfig) : null);
            request.setCmdRiskLevel(resolveCommandRiskLevel(dto, consolidatedConfig));
            request.setPluginPid(pluginPid);  // Set plugin_pid via request
            request.setExtension(support.toJson(dto.getEffectiveExtension()));

            com.auraboot.framework.meta.dto.CommandDefinitionDTO created = commandService.create(request);

            // Auto-publish if requested — an adopted (resurrected) row may already be
            // PUBLISHED; republishing it would trip the DRAFT-only guard.
            if (Boolean.TRUE.equals(autoPublish)
                    && !StatusConstants.PUBLISHED.equals(created.getStatus())) {
                commandService.publish(created.getPid());
            }

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.COMMAND,
                    created.getPid(), null, dto.getCode(), dto.getEffectiveDisplayName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    private String resolveCommandRiskLevel(CommandDefinitionDTO dto, Map<String, Object> consolidatedConfig) {
        if (dto.getCmdRiskLevel() != null && !dto.getCmdRiskLevel().isBlank()) {
            return dto.getCmdRiskLevel();
        }
        if (consolidatedConfig != null) {
            Object riskLevel = consolidatedConfig.get("riskLevel");
            if (riskLevel != null && !String.valueOf(riskLevel).isBlank()) {
                return String.valueOf(riskLevel);
            }
        }
        return "L1";
    }

    public PluginResource importBindingRule(BindingRuleDTO dto, String pluginPid, String importId,
                                             Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        // Get command
        com.auraboot.framework.meta.dto.CommandDefinitionDTO command;
        try {
            command = commandService.findByCode(dto.getCommandCode());
        } catch (Exception e) {
            throw new PluginException("Command not found: " + dto.getCommandCode(), e);
        }

        String ruleCode = buildBindingRuleCode(dto);

        // Check if this exact binding rule already exists for the command.
        List<com.auraboot.framework.meta.dto.BindingRuleDTO> existingRules = commandService.getBindingRules(command.getPid());
        boolean exists = existingRules.stream()
                .anyMatch(r -> sameBindingRule(dto, r));

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Binding rule already exists: " + ruleCode);
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.BINDING_RULE,
                    null, null, ruleCode, dto.getRuleType(), ResourceAction.SKIP, null, null);
        }

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.BINDING_RULE, ruleCode)) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.BINDING_RULE, PluginImportSupport.logSafe(ruleCode));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.BINDING_RULE,
                    null, null, ruleCode, dto.getRuleType(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // OVERWRITE: delete the matching rule identity, then recreate.
            existingRules.stream()
                    .filter(r -> sameBindingRule(dto, r))
                    .forEach(r -> commandService.removeBindingRule(r.getPid()));
        }

        // Create binding rule via service
        com.auraboot.framework.meta.dto.BindingRuleDTO serviceDto = new com.auraboot.framework.meta.dto.BindingRuleDTO();
        serviceDto.setRuleType(dto.getRuleType());
        serviceDto.setExpression(dto.getExpression());
        serviceDto.setTargetModel(dto.getTargetModel());
        serviceDto.setTargetField(dto.getTargetField());
        serviceDto.setSourceField(dto.getSourceField());
        serviceDto.setHandlerClass(dto.getHandlerClass());
        serviceDto.setEventType(dto.getEventType());
        serviceDto.setConfig(dto.getConfig() != null ? support.toJson(dto.getConfig()) : null);
        serviceDto.setSequence(dto.getSequence());
        serviceDto.setEnabled(dto.getEnabled());

        com.auraboot.framework.meta.dto.BindingRuleDTO created = commandService.addBindingRule(command.getPid(), serviceDto);

        // Update plugin_pid
        bindingRuleMapper.updatePluginPid(pluginPid, created.getPid());

        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.BINDING_RULE,
                created.getPid(), null, ruleCode, dto.getRuleType(),
                exists ? ResourceAction.UPDATE : ResourceAction.CREATE, null, null);
    }

    private String buildBindingRuleCode(BindingRuleDTO dto) {
        return String.join(":",
                safePart(dto.getCommandCode()),
                safePart(dto.getRuleType()),
                safePart(dto.getSequence()),
                safePart(dto.getTargetModel()),
                safePart(dto.getTargetField()),
                safePart(dto.getSourceField()),
                safePart(dto.getHandlerClass()),
                safePart(dto.getEventType()));
    }

    private boolean sameBindingRule(
            BindingRuleDTO dto,
            com.auraboot.framework.meta.dto.BindingRuleDTO existing) {
        return Objects.equals(dto.getRuleType(), existing.getRuleType())
                && Objects.equals(dto.getSequence(), existing.getSequence())
                && Objects.equals(normalizeRulePart(dto.getTargetModel()), normalizeRulePart(existing.getTargetModel()))
                && Objects.equals(normalizeRulePart(dto.getTargetField()), normalizeRulePart(existing.getTargetField()))
                && Objects.equals(normalizeRulePart(dto.getSourceField()), normalizeRulePart(existing.getSourceField()))
                && Objects.equals(normalizeRulePart(dto.getHandlerClass()), normalizeRulePart(existing.getHandlerClass()))
                && Objects.equals(normalizeRulePart(dto.getEventType()), normalizeRulePart(existing.getEventType()));
    }

    private String normalizeRulePart(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String safePart(Object value) {
        if (value == null) {
            return "-";
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "-" : text.replace(':', '_');
    }

}

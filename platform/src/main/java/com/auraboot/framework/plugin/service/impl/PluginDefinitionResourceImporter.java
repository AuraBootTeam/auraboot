package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.dto.AutomationUpdateRequest;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.util.*;
import com.auraboot.framework.common.constant.StatusConstants;

/** Imports model, field, command, page, query and automation definitions. */
@Slf4j
@RequiredArgsConstructor
final class PluginDefinitionResourceImporter {
    private final PluginResourceImporter resourceImporter;

    private final com.auraboot.framework.meta.template.generator.DocumentCommandGenerator documentCommandGenerator;

    private final AutomationService automationService;

    private final SaveOrUpdatePluginResourceOperation saveOrUpdatePluginResourceOperation;

    private final CaptureImportSnapshotOperation captureImportSnapshotOperation;

    @FunctionalInterface
    interface SaveOrUpdatePluginResourceOperation { void execute(PluginResource resource, Long tenantId); }

    @FunctionalInterface
    interface CaptureImportSnapshotOperation { void execute(PluginResource resource, Object manifestDto); }

    private void saveOrUpdatePluginResource(PluginResource resource, Long tenantId) { saveOrUpdatePluginResourceOperation.execute(resource,tenantId); }

    private void captureImportSnapshot(PluginResource resource, Object manifestDto) { captureImportSnapshotOperation.execute(resource,manifestDto); }

    void importDicts(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getDicts() == null) return;

        for (DictDefinitionDTO dict : manifest.getDicts()) {
            if (!dict.isValid()) {
                log.warn("Skipping invalid dict entry (missing code): index={}", manifest.getDicts().indexOf(dict));
                continue;
            }
            PluginResource resource = resourceImporter.importDict(dict, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, dict);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.DICT, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.DICT, resource.getResourcePid());
                }
            }
        }
    }

    void importFields(PluginManifestExtended manifest, ImportRequest request,
                              ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getFields() == null) return;

        for (FieldDefinitionDTO field : manifest.getFields()) {
            if (!field.isValid()) {
                log.warn("Skipping invalid field entry (missing code/dataType): index={}", manifest.getFields().indexOf(field));
                continue;
            }
            PluginResource resource = resourceImporter.importField(field, pluginPid, importId, tenantId,
                    request.getConflictStrategy(), request.getAutoPublishFields());
            if (resource != null) {
                captureImportSnapshot(resource, field);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.FIELD, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.FIELD, resource.getResourcePid());
                }
            }
        }
    }

    List<String> importModels(PluginManifestExtended manifest, ImportRequest request,
                              ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        List<String> importedModelCodes = new ArrayList<>();
        if (manifest.getModels() == null) return importedModelCodes;

        for (ModelDefinitionDTO model : manifest.getModels()) {
            if (!model.isValid()) {
                log.warn("Skipping invalid model entry (missing code): index={}", manifest.getModels().indexOf(model));
                continue;
            }
            PluginResource resource = resourceImporter.importModel(model, pluginPid, importId, tenantId,
                    request.getConflictStrategy(), request.getAutoPublishModels());
            if (resource != null) {
                captureImportSnapshot(resource, model);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.MODEL, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.MODEL, resource.getResourcePid());
                }
                // Track model codes for post-processing (publish/sync)
                if (resource.getActionEnum() != ResourceAction.SKIP) {
                    importedModelCodes.add(model.getCode());
                }
            }
        }
        return importedModelCodes;
    }

    void importModelFieldBindings(PluginManifestExtended manifest, ImportRequest request,
                                          ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getModelFieldBindings() == null) return;

        for (ModelFieldBindingDTO binding : manifest.getModelFieldBindings()) {
            if (!binding.isValid()) {
                log.warn("Skipping invalid model-field binding (missing modelCode/fieldCode): index={}", manifest.getModelFieldBindings().indexOf(binding));
                continue;
            }
            PluginResource resource = resourceImporter.importModelFieldBinding(binding, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, binding);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.MODEL_FIELD_BINDING, resource.getActionEnum());
            }
        }
    }

    void generateDocumentTemplateCommands(PluginManifestExtended manifest){
        if (manifest.getModels() == null) return;

        // Collect existing command codes for dedup
        Set<String> existingCodes = new HashSet<>();
        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO cmd : manifest.getCommands()) {
                if (cmd.getCode() != null) existingCodes.add(cmd.getCode());
            }
        }

        List<CommandDefinitionDTO> generated = new ArrayList<>();
        for (ModelDefinitionDTO model : manifest.getModels()) {
            if (!"document".equals(model.getModelCategory())) continue;

            var docConfig = com.auraboot.framework.meta.template.dto.DocumentConfig.fromExtension(model.getExtension());
            if (docConfig == null) continue;

            List<CommandDefinitionDTO> modelCommands = documentCommandGenerator.generateCommands(model, docConfig);
            for (CommandDefinitionDTO cmd : modelCommands) {
                if (!existingCodes.contains(cmd.getCode())) {
                    generated.add(cmd);
                    existingCodes.add(cmd.getCode());
                    log.debug("Document template generated command: {}", logSafe(cmd.getCode()));
                } else {
                    log.debug("Document template skipped (plugin-defined): {}", logSafe(cmd.getCode()));
                }
            }
        }

        if (!generated.isEmpty()) {
            log.info("Document template generated {} commands for {} plugin",
                    generated.size(), logSafe(manifest.getPluginId()));
            if (manifest.getCommands() == null) {
                manifest.setCommands(new ArrayList<>(generated));
            } else {
                manifest.getCommands().addAll(generated);
            }
        }
    }

    void importCommands(PluginManifestExtended manifest, ImportRequest request,
                                ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getCommands() == null) return;

        for (CommandDefinitionDTO command : manifest.getCommands()) {
            if (!command.isValid()) {
                log.warn("Skipping invalid command entry (missing code/modelCode): index={}", manifest.getCommands().indexOf(command));
                continue;
            }
            PluginResource resource = resourceImporter.importCommand(command, pluginPid, importId, tenantId, request.getConflictStrategy(), request.getAutoPublishCommands());
            if (resource != null) {
                captureImportSnapshot(resource, command);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.COMMAND, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.COMMAND, resource.getResourcePid());
                }
            }
        }
    }

    void importBindingRules(PluginManifestExtended manifest, ImportRequest request,
                                    ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        // Binding rules are imported with their commands
        if (manifest.getBindingRules() == null) return;

        for (BindingRuleDTO rule : manifest.getBindingRules()) {
            if (!rule.isValid()) {
                log.warn("Skipping invalid binding rule (missing commandCode): index={}", manifest.getBindingRules().indexOf(rule));
                continue;
            }
            PluginResource resource = resourceImporter.importBindingRule(rule, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, rule);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.BINDING_RULE, resource.getActionEnum());
            }
        }
    }

    void importPages(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getPages() == null) return;

        for (PageSchemaDTO page : manifest.getPages()) {
            if (!page.isValid()) {
                String pageKey = page != null && page.getPageKey() != null ? page.getPageKey() : "<unknown>";
                throw new PluginException("Invalid page '" + pageKey + "': page JSON must use the latest V2 flat " +
                        "format with top-level kind/layout/blocks, and layout/blocks cannot be empty.");
            }
            PluginResource resource = resourceImporter.importPage(page, pluginPid, importId, tenantId,
                    request.getConflictStrategy(), request.getAutoPublishPages());
            if (resource != null) {
                captureImportSnapshot(resource, page);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.PAGE, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.PAGE, resource.getResourcePid());
                }
            }
        }
    }

    void importDashboards(PluginManifestExtended manifest, ImportRequest request,
                                  ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getDashboards() == null || manifest.getDashboards().isEmpty()) return;

        for (com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO dto : manifest.getDashboards()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid dashboard definition: code={}", logSafe(dto.getCode()));
                continue;
            }
            PluginResource resource = resourceImporter.importDashboard(dto, pluginPid, importId, tenantId,
                    request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, dto);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.PAGE, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.PAGE, resource.getResourcePid());
                }
            }
        }
    }

    void importNamedQueries(PluginManifestExtended manifest, ImportRequest request,
                                    ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getNamedQueries() == null) return;

        for (NamedQueryDefinitionDTO namedQuery : manifest.getNamedQueries()) {
            if (!namedQuery.isValid()) {
                log.warn("Skipping invalid named query entry (missing code/fromSql): index={}", manifest.getNamedQueries().indexOf(namedQuery));
                continue;
            }
            // Plugin-imported NQs default to PUBLISHED (same rationale as autoPublishModels).
            // JSON without explicit status deserializes to null (Jackson ignores @Builder.Default),
            // and normalizeNamedQueryStatus maps null → "draft". Override to PUBLISHED.
            if (namedQuery.getStatus() == null || "draft".equalsIgnoreCase(namedQuery.getStatus())) {
                namedQuery.setStatus(StatusConstants.PUBLISHED);
            }
            PluginResource resource = resourceImporter.importNamedQuery(
                    namedQuery, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, namedQuery);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.NAMED_QUERY, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.NAMED_QUERY, resource.getResourcePid());
                }
            }
        }
    }

    void importAgentDefinitions(PluginManifestExtended manifest, ImportRequest request,
                                        ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getAgentDefinitions() == null) return;

        for (AgentDefinitionDTO agentDefinition : manifest.getAgentDefinitions()) {
            if (!agentDefinition.isValid()) {
                log.warn("Skipping invalid agent definition entry (missing agentCode/name): index={}",
                        manifest.getAgentDefinitions().indexOf(agentDefinition));
                continue;
            }
            PluginResource resource = resourceImporter.importAgentDefinition(
                    agentDefinition, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, agentDefinition);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.AGENT_DEFINITION, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.AGENT_DEFINITION, resource.getResourcePid());
                }
            }
        }
    }

    void importAutomations(PluginManifestExtended manifest){
        if (manifest.getAutomations() == null || manifest.getAutomations().isEmpty()) return;
        int imported = 0;
        for (AutomationDefinitionDTO dto : manifest.getAutomations()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid automation seed (missing key/name/model/trigger): index={}",
                        manifest.getAutomations().indexOf(dto));
                continue;
            }
            importAutomation(dto);
            imported++;
        }
        if (imported > 0) {
            log.info("Imported {} automation seed(s) for plugin {}", imported, logSafe(manifest.getPluginId()));
        }
    }

    void importAutomation(AutomationDefinitionDTO dto){
        AutomationDTO existing = findExistingAutomation(dto);
        if (existing == null) {
            automationService.create(toAutomationCreateRequest(dto));
            return;
        }
        AutomationUpdateRequest request = new AutomationUpdateRequest();
        request.setName(dto.getName());
        request.setDescription(dto.getDescription());
        request.setTriggerType(dto.getTriggerType());
        request.setTriggerConfig(dto.getTriggerConfig());
        request.setTriggerCondition(dto.getTriggerCondition());
        request.setActions(dto.getActions());
        request.setFlowConfig(dto.getFlowConfig());
        request.setEnabled(dto.getEnabled());
        automationService.update(existing.getPid(), request);
    }

    AutomationDTO findExistingAutomation(AutomationDefinitionDTO dto){
        List<AutomationDTO> automations = automationService.getByModelCode(dto.getModelCode());
        if (automations == null || automations.isEmpty()) {
            return null;
        }
        return automations.stream()
                .filter(existing -> Objects.equals(existing.getName(), dto.getName()))
                .findFirst()
                .orElse(null);
    }

    AutomationCreateRequest toAutomationCreateRequest(AutomationDefinitionDTO dto){
        AutomationCreateRequest request = new AutomationCreateRequest();
        request.setName(dto.getName());
        request.setDescription(dto.getDescription());
        request.setModelCode(dto.getModelCode());
        request.setTriggerType(dto.getTriggerType());
        request.setTriggerConfig(dto.getTriggerConfig());
        request.setTriggerCondition(dto.getTriggerCondition());
        request.setActions(dto.getActions());
        request.setFlowConfig(dto.getFlowConfig());
        request.setEnabled(dto.getEnabled());
        return request;
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}

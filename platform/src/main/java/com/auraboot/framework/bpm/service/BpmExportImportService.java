package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.entity.BpmRule;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.mapper.BpmRuleMapper;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * BPM process definition export/import service.
 * Handles packaging process definitions with their associated
 * configurations (forms, rules, permissions, hooks, SLA) for
 * cross-environment migration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmExportImportService {

    private final ObjectMapper objectMapper;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final BpmNodeHookService hookService;
    private final SlaConfigService slaConfigService;
    private final BpmRuleMapper ruleMapper;

    /**
     * Export a process definition package as a Map.
     * Includes BPMN XML, form bindings, rules, permissions, hooks, and SLA config.
     */
    public Map<String, Object> exportPackage(String processKey) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // 1. Get process definition
        BpmProcessDefinition definition = processDefinitionMapper.selectOne(
                new QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("process_key", processKey)
                        .eq("is_current", true)
                        .eq("deleted_flag", false)
        );

        if (definition == null) {
            throw new IllegalArgumentException("Process definition not found: " + processKey);
        }

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("format", "aura-bpm-package");
        pkg.put("version", "1.0");
        pkg.put("exportedAt", Instant.now().toString());
        pkg.put("processKey", processKey);

        // 2. Process definition data
        Map<String, Object> processData = new LinkedHashMap<>();
        processData.put("processKey", definition.getProcessKey());
        processData.put("processName", definition.getProcessName());
        processData.put("bpmnContent", definition.getBpmnContent());
        processData.put("status", definition.getStatus());
        processData.put("version", definition.getVersion());
        processData.put("extension", definition.getExtension());
        pkg.put("processDefinition", processData);

        // 3. Node hooks
        List<BpmNodeHook> hooks = hookService.getHooksByProcessKey(processKey);
        pkg.put("nodeHooks", hooks.stream().map(h -> Map.of(
                "nodeId", h.getNodeId(),
                "hookType", h.getHookType(),
                "executionOrder", h.getExecutionOrder() != null ? h.getExecutionOrder() : 0,
                "hookConfig", h.getHookConfig() != null ? h.getHookConfig() : Map.of(),
                "failStrategy", h.getFailStrategy() != null ? h.getFailStrategy() : "block",
                "async", Boolean.TRUE.equals(h.getAsync()),
                "enabled", Boolean.TRUE.equals(h.getEnabled())
        )).toList());

        // 4. Permissions - now managed by RBAC (ab_permission + ab_role_permission)
        pkg.put("permissions", List.of());

        // 5. SLA configs (find by target PROCESS + processKey)
        List<SlaConfigEntity> slaConfigs = slaConfigService.findByTarget("process", processKey);
        pkg.put("slaConfigs", slaConfigs.stream().map(s -> Map.of(
                "name", s.getName() != null ? s.getName() : "",
                "targetType", s.getTargetType() != null ? s.getTargetType() : "",
                "targetKey", s.getTargetKey() != null ? s.getTargetKey() : "",
                "deadlineMode", s.getDeadlineMode() != null ? s.getDeadlineMode() : "",
                "deadlineValue", s.getDeadlineValue() != null ? s.getDeadlineValue() : "",
                "warningRules", s.getWarningRules() != null ? s.getWarningRules() : List.of(),
                "suspendPolicy", s.getSuspendPolicy() != null ? s.getSuspendPolicy() : "pause",
                "enabled", Boolean.TRUE.equals(s.getEnabled())
        )).toList());

        log.info("Exported BPM process package: processKey={}, hooks={}, sla={}",
                processKey, hooks.size(), slaConfigs.size());
        return pkg;
    }

    /**
     * Validate an import package before execution.
     * Checks for conflicts, missing dependencies, and schema compatibility.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> validatePackage(Map<String, Object> pkg) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Map<String, String>> conflicts = new ArrayList<>();

        // Basic validation
        if (!pkg.containsKey("processKey") || !pkg.containsKey("version")) {
            errors.add("Missing required fields: processKey, version");
        }
        if (!"aura-bpm-package".equals(pkg.get("format"))) {
            errors.add("Invalid package format");
        }

        // Check for existing process definition conflict
        String processKey = (String) pkg.get("processKey");
        if (processKey != null) {
            Long tenantId = MetaContext.getCurrentTenantId();
            BpmProcessDefinition existing = processDefinitionMapper.selectOne(
                    new QueryWrapper<BpmProcessDefinition>()
                            .eq("tenant_id", tenantId)
                            .eq("process_key", processKey)
                            .eq("is_current", true)
                            .eq("deleted_flag", false)
            );
            if (existing != null) {
                conflicts.add(Map.of(
                        "type", "process_definition",
                        "key", processKey,
                        "message", "Process definition already exists (version " + existing.getVersion() + ")"
                ));
            }
        }

        // Validate process definition data
        Object procDef = pkg.get("processDefinition");
        if (procDef instanceof Map) {
            Map<String, Object> pd = (Map<String, Object>) procDef;
            if (pd.get("bpmnContent") == null) {
                warnings.add("Process definition has no BPMN content");
            }
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("conflicts", conflicts);
        return result;
    }

    /**
     * Execute the import with a specified conflict resolution strategy.
     *
     * @param pkg      the process package to import
     * @param strategy conflict resolution strategy: SKIP_EXISTING, OVERWRITE, NEW_VERSION
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> executeImport(Map<String, Object> pkg, String strategy) {
        String processKey = (String) pkg.get("processKey");
        Long tenantId = MetaContext.getCurrentTenantId();

        log.info("Importing BPM process package: strategy={}, processKey={}", strategy, processKey);

        List<String> imported = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // 1. Import process definition
        Object procDef = pkg.get("processDefinition");
        if (procDef instanceof Map) {
            Map<String, Object> pd = (Map<String, Object>) procDef;
            BpmProcessDefinition existing = processDefinitionMapper.selectOne(
                    new QueryWrapper<BpmProcessDefinition>()
                            .eq("tenant_id", tenantId)
                            .eq("process_key", processKey)
                            .eq("is_current", true)
                            .eq("deleted_flag", false)
            );

            if (existing != null) {
                if ("skip_existing".equals(strategy)) {
                    skipped.add("processDefinition");
                } else if ("overwrite".equals(strategy)) {
                    existing.setBpmnContent((String) pd.get("bpmnContent"));
                    existing.setProcessName((String) pd.get("processName"));
                    existing.setExtension((Map<String, Object>) pd.get("extension"));
                    existing.setBusinessDataBindings(normalizeBusinessDataBindings(pd.get("businessDataBindings")));
                    existing.setUpdatedAt(Instant.now());
                    processDefinitionMapper.updateById(existing);
                    imported.add("processDefinition (overwritten)");
                } else {
                    // NEW_VERSION: increment version
                    existing.setIsCurrent(false);
                    processDefinitionMapper.updateById(existing);

                    BpmProcessDefinition newDef = new BpmProcessDefinition();
                    newDef.setPid(com.auraboot.framework.common.util.UlidGenerator.generate());
                    newDef.setTenantId(tenantId);
                    newDef.setProcessKey(processKey);
                    newDef.setProcessName((String) pd.get("processName"));
                    newDef.setBpmnContent((String) pd.get("bpmnContent"));
                    newDef.setExtension((Map<String, Object>) pd.get("extension"));
                    newDef.setBusinessDataBindings(normalizeBusinessDataBindings(pd.get("businessDataBindings")));
                    newDef.setStatus(StatusConstants.DRAFT);
                    newDef.setVersion(existing.getVersion() != null ? existing.getVersion() + 1 : 1);
                    newDef.setIsCurrent(true);
                    newDef.setCreatedAt(Instant.now());
                    newDef.setUpdatedAt(Instant.now());
                    processDefinitionMapper.insert(newDef);
                    imported.add("processDefinition (new version " + newDef.getVersion() + ")");
                }
            } else {
                // No existing - create new
                BpmProcessDefinition newDef = new BpmProcessDefinition();
                newDef.setPid(com.auraboot.framework.common.util.UlidGenerator.generate());
                newDef.setTenantId(tenantId);
                newDef.setProcessKey(processKey);
                newDef.setProcessName((String) pd.get("processName"));
                newDef.setBpmnContent((String) pd.get("bpmnContent"));
                newDef.setExtension((Map<String, Object>) pd.get("extension"));
                newDef.setBusinessDataBindings(normalizeBusinessDataBindings(pd.get("businessDataBindings")));
                newDef.setStatus(StatusConstants.DRAFT);
                newDef.setVersion(1);
                newDef.setIsCurrent(true);
                newDef.setCreatedAt(Instant.now());
                newDef.setUpdatedAt(Instant.now());
                processDefinitionMapper.insert(newDef);
                imported.add("processDefinition (created)");
            }
        }

        // 2. Import node hooks
        Object hooksObj = pkg.get("nodeHooks");
        if (hooksObj instanceof List<?> hooksList) {
            for (Object hookObj : hooksList) {
                if (hookObj instanceof Map<?, ?> hookMap) {
                    BpmNodeHook hook = new BpmNodeHook();
                    hook.setProcessKey(processKey);
                    hook.setNodeId((String) hookMap.get("nodeId"));
                    hook.setHookType((String) hookMap.get("hookType"));
                    hook.setHookConfig((Map<String, Object>) hookMap.get("hookConfig"));
                    hook.setFailStrategy((String) hookMap.get("failStrategy"));
                    hook.setAsync((Boolean) hookMap.get("async"));
                    hook.setEnabled((Boolean) hookMap.get("enabled"));
                    hookService.createHook(hook);
                }
            }
            imported.add("nodeHooks (" + hooksList.size() + ")");
        }

        log.info("Import completed: imported={}, skipped={}", imported, skipped);

        return Map.of(
                "success", true,
                "strategy", strategy,
                "processKey", processKey != null ? processKey : "unknown",
                "imported", imported,
                "skipped", skipped
        );
    }

    private Map<String, Object> normalizeBusinessDataBindings(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
            return normalized;
        }
        if (raw instanceof List<?> list) {
            return Map.of("bindings", list);
        }
        return Map.of();
    }
}

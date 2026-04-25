package com.auraboot.framework.bpm.service;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.converter.JsonToBpmnConverter;
import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.mapper.BpmNodeHookMapper;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Process deployment service.
 * Handles deploying BPMN definitions to SmartEngine and managing process definitions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessDeploymentService {

    private final SmartEngine smartEngine;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final BpmAuditService bpmAuditService;
    private final JsonToBpmnConverter jsonToBpmnConverter;
    private final BpmNodeHookService bpmNodeHookService;
    private final BpmNodeHookMapper bpmNodeHookMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private static final String EXTENSION_KEY_DESIGNER_JSON = "designerJson";

    // ==================== Query Operations ====================

    /**
     * Get all process definitions for current tenant.
     */
    public List<BpmProcessDefinition> listProcessDefinitions() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return processDefinitionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("deleted_flag", false)
                        .eq("is_current", true)
                        .orderByAsc("process_name"));
    }

    /**
     * Get paginated process definitions for current tenant.
     * Supports keyword search and filter conditions.
     */
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<BpmProcessDefinition> listProcessDefinitionsPaged(
            int page, int size, String keyword, String filtersJson) {
        Long tenantId = MetaContext.getCurrentTenantId();

        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BpmProcessDefinition> pageRequest =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page + 1, size);

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmProcessDefinition> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("deleted_flag", false)
                        .eq("is_current", true)
                        .orderByDesc("created_at");

        // Keyword search on process_key and process_name
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w
                    .like("process_key", keyword)
                    .or()
                    .like("process_name", keyword));
        }

        // Parse filters JSON array: [{"fieldName":"status","operator":"EQ","value":"draft"}]
        if (filtersJson != null && !filtersJson.isBlank()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode filters = om.readTree(filtersJson);
                if (filters.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode f : filters) {
                        String fieldName = f.has("fieldName") ? f.get("fieldName").asText() : null;
                        String operator = f.has("operator") ? f.get("operator").asText() : "EQ";
                        String value = f.has("value") ? f.get("value").asText() : null;
                        if (fieldName != null && value != null && !value.isEmpty()) {
                            switch (operator) {
                                case "EQ" -> wrapper.eq(fieldName, value);
                                case "NE" -> wrapper.ne(fieldName, value);
                                case "like" -> wrapper.like(fieldName, value);
                                default -> wrapper.eq(fieldName, value);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse filters JSON: {}", filtersJson, e);
            }
        }

        return processDefinitionMapper.selectPage(pageRequest, wrapper);
    }

    /**
     * Get process definitions by status.
     */
    public List<BpmProcessDefinition> listByStatus(String status) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return processDefinitionMapper.findByStatus(tenantId, status);
    }

    /**
     * Get process definitions by category.
     */
    public List<BpmProcessDefinition> listByCategory(String category) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return processDefinitionMapper.findByCategory(tenantId, category);
    }

    /**
     * Get process definition by PID.
     */
    public BpmProcessDefinition getByPid(String pid) {
        BpmProcessDefinition def = processDefinitionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmProcessDefinition>()
                        .eq("pid", pid)
                        .eq("deleted_flag", false));
        if (def != null && !def.getTenantId().equals(MetaContext.getCurrentTenantId())) {
            return null; // Tenant isolation
        }
        return def;
    }

    /**
     * Get process definition by process key.
     */
    public BpmProcessDefinition getByProcessKey(String processKey) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return processDefinitionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmProcessDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("process_key", processKey)
                        .eq("is_current", true)
                        .eq("deleted_flag", false));
    }

    /**
     * Get all versions of a process definition.
     */
    public List<BpmProcessDefinition> getAllVersions(String processKey) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return processDefinitionMapper.findAllVersions(tenantId, processKey);
    }

    /**
     * Get deployed process definitions.
     */
    public List<BpmProcessDefinition> getDeployedProcesses() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return processDefinitionMapper.findDeployed(tenantId);
    }

    // ==================== Create/Update Operations ====================

    /**
     * Create a new process definition.
     */
    @Transactional
    public BpmProcessDefinition create(CreateProcessRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Check if process key already exists
        if (processDefinitionMapper.existsByProcessKey(tenantId, request.processKey())) {
            throw new IllegalArgumentException("Process key already exists: " + request.processKey());
        }

        String pid = UlidGenerator.generate();

        // Build extension map, storing designerJson if provided
        Map<String, Object> extension = new HashMap<>();
        if (StringUtils.hasText(request.designerJson())) {
            extension.put(EXTENSION_KEY_DESIGNER_JSON, request.designerJson());
        }

        // Compile designer JSON immediately so draft save/export round-trips
        // have durable BPMN XML before the process is deployed.
        String bpmnContent = StringUtils.hasText(request.bpmnContent())
                ? request.bpmnContent()
                : StringUtils.hasText(request.designerJson())
                        ? compileDesignerJson(
                                request.designerJson(), request.processKey(), request.processName())
                        : "";

        // Derive form_bindings from designerJson when the caller did not supply them explicitly
        Map<String, Object> resolvedFormBindings = request.formBindings();
        if ((resolvedFormBindings == null || resolvedFormBindings.isEmpty())
                && StringUtils.hasText(request.designerJson())) {
            resolvedFormBindings = deriveFormBindingsFromDesignerJson(
                    request.designerJson(), request.processKey());
        }

        BpmProcessDefinition definition = BpmProcessDefinition.builder()
                .pid(pid)
                .tenantId(tenantId)
                .processKey(request.processKey())
                .processName(request.processName())
                .description(request.description())
                .category(request.category())
                .bpmnContent(bpmnContent)
                .formBindings(resolvedFormBindings)
                .businessDataBindings(request.businessDataBindings() != null
                        ? Map.of("bindings", request.businessDataBindings())
                        : Map.of())
                .extension(extension.isEmpty() ? null : extension)
                .status(StatusConstants.DRAFT)
                .version(1)
                .isCurrent(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        processDefinitionMapper.insert(definition);
        log.info("Created process definition: processKey={}, pid={}", request.processKey(), pid);

        return definition;
    }

    /**
     * Update an existing process definition.
     */
    @Transactional
    public BpmProcessDefinition update(String pid, UpdateProcessRequest request) {
        BpmProcessDefinition existing = getByPid(pid);
        if (existing == null) {
            throw new IllegalArgumentException("Process definition not found: " + pid);
        }

        if (existing.isDeployed()) {
            throw new IllegalStateException("Cannot update deployed process. Create a new version instead.");
        }

        if (request.processName() != null) {
            existing.setProcessName(request.processName());
        }
        if (request.description() != null) {
            existing.setDescription(request.description());
        }
        if (request.category() != null) {
            existing.setCategory(request.category());
        }
        if (request.bpmnContent() != null) {
            existing.setBpmnContent(request.bpmnContent());
        }
        if (request.formBindings() != null) {
            existing.setFormBindings(request.formBindings());
        }
        if (request.businessDataBindings() != null) {
            existing.setBusinessDataBindings(Map.of("bindings", request.businessDataBindings()));
        }
        String generatedBpmnContent = null;
        if (StringUtils.hasText(request.designerJson())) {
            Map<String, Object> ext = existing.getExtension() != null
                    ? new HashMap<>(existing.getExtension())
                    : new HashMap<>();
            ext.put(EXTENSION_KEY_DESIGNER_JSON, request.designerJson());
            existing.setExtension(ext);
            if (!StringUtils.hasText(request.bpmnContent())) {
                generatedBpmnContent = compileDesignerJson(
                        request.designerJson(), existing.getProcessKey(), existing.getProcessName());
                existing.setBpmnContent(generatedBpmnContent);
            }

            // Re-derive form_bindings from the new designerJson when the caller did
            // not supply explicit formBindings (designer save path never pre-derives them).
            if (request.formBindings() == null) {
                Map<String, Object> derived = deriveFormBindingsFromDesignerJson(
                        request.designerJson(), existing.getProcessKey());
                if (derived != null && !derived.isEmpty()) {
                    existing.setFormBindings(derived);
                }
            }
        }

        existing.setUpdatedAt(Instant.now());
        processDefinitionMapper.updateById(existing);
        if (StringUtils.hasText(generatedBpmnContent)) {
            processDefinitionMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<BpmProcessDefinition>()
                            .eq("pid", pid)
                            .eq("tenant_id", existing.getTenantId())
                            .set("bpmn_content", generatedBpmnContent)
                            .set("updated_at", existing.getUpdatedAt()));
        }

        log.info("Updated process definition: pid={}", pid);
        return existing;
    }

    /**
     * Create a new version of an existing process.
     */
    @Transactional
    public BpmProcessDefinition createNewVersion(String processKey, String bpmnContent, String designerJson) {
        Long tenantId = MetaContext.getCurrentTenantId();
        BpmProcessDefinition current = getByProcessKey(processKey);

        if (current == null) {
            throw new IllegalArgumentException("Process not found: " + processKey);
        }

        // Mark current version as not current
        processDefinitionMapper.clearCurrentVersion(tenantId, processKey);

        // Create new version
        int nextVersion = processDefinitionMapper.getNextVersion(tenantId, processKey);
        String newPid = UlidGenerator.generate();

        // Build extension map: carry over from current version, then override with new designerJson
        Map<String, Object> extension = current.getExtension() != null
                ? new HashMap<>(current.getExtension())
                : new HashMap<>();
        if (StringUtils.hasText(designerJson)) {
            extension.put(EXTENSION_KEY_DESIGNER_JSON, designerJson);
        }

        BpmProcessDefinition newVersion = BpmProcessDefinition.builder()
                .pid(newPid)
                .tenantId(tenantId)
                .processKey(processKey)
                .processName(current.getProcessName())
                .description(current.getDescription())
                .category(current.getCategory())
                .bpmnContent(bpmnContent)
                .formBindings(current.getFormBindings())
                .businessDataBindings(current.getBusinessDataBindings())
                .extension(extension.isEmpty() ? null : extension)
                .status(StatusConstants.DRAFT)
                .version(nextVersion)
                .isCurrent(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        processDefinitionMapper.insert(newVersion);
        log.info("Created new version: processKey={}, version={}", processKey, nextVersion);

        return newVersion;
    }

    // ==================== Deploy/Undeploy Operations ====================

    /**
     * Deploy a process definition to SmartEngine.
     *
     * <p>If bpmnContent is not set but designerJson exists in the extension field,
     * the designer JSON is automatically converted to BPMN 2.0 XML before deployment.
     */
    @Transactional
    public BpmProcessDefinition deploy(String pid) {
        BpmProcessDefinition definition = getByPid(pid);
        if (definition == null) {
            throw new IllegalArgumentException("Process definition not found: " + pid);
        }

        if (definition.isDeployed()) {
            log.info("Process already deployed: processKey={}", definition.getProcessKey());
            return definition;
        }

        // Always parse designerJson when present so we can both (a) compile to BPMN if
        // bpmnContent is missing and (b) extract node hooks for ab_bpm_node_hook
        // persistence regardless of whether the BPMN was pre-supplied (GAP-254).
        com.fasterxml.jackson.databind.JsonNode designerRoot = null;
        String designerJson = getDesignerJson(definition);
        if (StringUtils.hasText(designerJson)) {
            designerRoot = parseCanonicalDesignerJson(
                    designerJson, definition.getProcessKey(), definition.getProcessName());
        }

        // Auto-convert designer JSON to BPMN XML if bpmnContent is empty
        if (!StringUtils.hasText(definition.getBpmnContent())) {
            if (designerRoot == null) {
                throw new IllegalStateException(
                        "Cannot deploy process '" + definition.getProcessKey()
                                + "': neither bpmnContent nor designerJson is available");
            }

            log.info("Converting designer JSON to BPMN XML: processKey={}", definition.getProcessKey());
            // Inject canonical processKey/name so JsonToBpmnConverter emits
            // <process id="<processKey>"> instead of the default "process_1",
            // which would collide across multiple UI-created processes and
            // break startProcess(processKey) lookup downstream.
            String bpmnXml = jsonToBpmnConverter.convertFromJsonNode(designerRoot);
            definition.setBpmnContent(bpmnXml);
            // Persist the compiled XML so subsequent GET /{pid}/bpmn, exports,
            // and version snapshots see the real content instead of the empty
            // placeholder stored at create-time.
            processDefinitionMapper.updateBpmnContent(pid, bpmnXml);
        }

        // GAP-254: compile designerJson node hooks into ab_bpm_node_hook so the
        // runtime BpmNodeHookService.getHooks(processKey,nodeId,hookType) finds
        // them on ACTIVITY_START / ACTIVITY_END. We delete-and-reinsert per
        // (processKey, nodeId) tuple so re-deploys stay idempotent.
        if (designerRoot != null) {
            persistDesignerHooks(definition.getProcessKey(), designerRoot);

            // Materialise form_bindings from designerJson at deploy time so that
            // BpmFormService.loadFormBindings() finds them even when the frontend
            // save path did not pre-derive them (common for UI-created processes).
            if (definition.getFormBindings() == null || definition.getFormBindings().isEmpty()) {
                Map<String, Object> derivedBindings = deriveFormBindingsFromDesignerJson(
                        designerJson, definition.getProcessKey());
                if (derivedBindings != null && !derivedBindings.isEmpty()) {
                    definition.setFormBindings(derivedBindings);
                    processDefinitionMapper.updateById(definition);
                }
            }
        }

        try {
            // Ensure BPMN process element has a version attribute (SmartEngine requires it)
            String bpmnContent = definition.getBpmnContent();
            String versionStr = String.valueOf(definition.getVersion());
            if (!bpmnContent.contains("version=\"")) {
                bpmnContent = bpmnContent.replaceFirst(
                        "(<process\\s+[^>]*)(>)",
                        "$1 version=\"" + versionStr + ".0.0\"$2");
            }

            // Deploy to SmartEngine - second arg is tenantId, NOT filename
            String tenantId = MetaContext.getCurrentTenantIdAsString();
            ByteArrayInputStream bpmnStream = new ByteArrayInputStream(
                    bpmnContent.getBytes(StandardCharsets.UTF_8));

            smartEngine.getRepositoryCommandService()
                    .deploy(bpmnStream, tenantId);

            // Update definition status - use process key and version as deployment ID
            String deploymentId = definition.getProcessKey() + ":" + definition.getVersion();
            processDefinitionMapper.updateDeployment(pid, deploymentId);

            // Refresh in-memory state for the returned DTO
            definition.setDeploymentId(deploymentId);
            definition.setDeployedAt(Instant.now());
            definition.setStatus(StatusConstants.DEPLOYED);
            definition.setUpdatedAt(Instant.now());

            // Record audit
            bpmAuditService.auditProcessDefinitionOperation("deploy", definition.getProcessKey(),
                    definition.getVersion(), Map.of("deploymentId", deploymentId));

            log.info("Process deployed: processKey={}, deploymentId={}",
                    definition.getProcessKey(), deploymentId);

            return definition;

        } catch (Exception e) {
            log.error("Failed to deploy process: processKey={}", definition.getProcessKey(), e);
            throw new BusinessException("Failed to deploy process: " + e.getMessage(), e);
        }
    }

    /**
     * Undeploy a process definition.
     */
    @Transactional
    public BpmProcessDefinition undeploy(String pid) {
        BpmProcessDefinition definition = getByPid(pid);
        if (definition == null) {
            throw new IllegalArgumentException("Process definition not found: " + pid);
        }

        if (!definition.isDeployed()) {
            throw new IllegalStateException("Process is not deployed: " + definition.getProcessKey());
        }

        // Check for running instances
        com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam queryParam =
                new com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam();
        queryParam.setTenantId(MetaContext.getCurrentTenantIdAsString());
        queryParam.setStatus("running");
        List<com.auraboot.smart.framework.engine.model.instance.ProcessInstance> running =
                smartEngine.getProcessQueryService().findList(queryParam);
        if (running != null) {
            long count = running.stream()
                    .filter(pi -> definition.getProcessKey().equals(pi.getProcessDefinitionId()))
                    .count();
            if (count > 0) {
                throw new IllegalStateException(
                        "Cannot undeploy: " + count + " running instance(s) for " + definition.getProcessKey());
            }
        }

        // Update status to ARCHIVED
        processDefinitionMapper.updateStatus(pid, "archived");

        // Refresh in-memory state for the returned DTO
        definition.setStatus(StatusConstants.ARCHIVED);
        definition.setUpdatedAt(Instant.now());

        // Record audit
        bpmAuditService.auditProcessDefinitionOperation("undeploy", definition.getProcessKey(),
                definition.getVersion(), Map.of());

        log.info("Process undeployed: processKey={}", definition.getProcessKey());

        return definition;
    }

    /**
     * Suspend a deployed process.
     */
    @Transactional
    public BpmProcessDefinition suspend(String pid) {
        BpmProcessDefinition definition = getByPid(pid);
        if (definition == null) {
            throw new IllegalArgumentException("Process definition not found: " + pid);
        }

        if (!definition.isDeployed()) {
            throw new IllegalStateException("Can only suspend deployed processes");
        }

        processDefinitionMapper.updateStatus(pid, "suspended");

        // Refresh in-memory state for the returned DTO
        definition.setStatus(StatusConstants.SUSPENDED);
        definition.setUpdatedAt(Instant.now());

        log.info("Process suspended: processKey={}", definition.getProcessKey());

        return definition;
    }

    /**
     * Resume a suspended process.
     */
    @Transactional
    public BpmProcessDefinition resume(String pid) {
        BpmProcessDefinition definition = getByPid(pid);
        if (definition == null) {
            throw new IllegalArgumentException("Process definition not found: " + pid);
        }

        if (!definition.isSuspended()) {
            throw new IllegalStateException("Process is not suspended");
        }

        processDefinitionMapper.updateStatus(pid, "deployed");

        // Refresh in-memory state for the returned DTO
        definition.setStatus(StatusConstants.DEPLOYED);
        definition.setUpdatedAt(Instant.now());

        log.info("Process resumed: processKey={}", definition.getProcessKey());

        return definition;
    }

    // ==================== Delete Operations ====================

    /**
     * Delete a process definition (soft delete).
     */
    @Transactional
    public void delete(String pid) {
        BpmProcessDefinition definition = getByPid(pid);
        if (definition == null) {
            throw new IllegalArgumentException("Process definition not found: " + pid);
        }

        if (definition.isDeployed()) {
            throw new IllegalStateException("Cannot delete deployed process. Undeploy first.");
        }

        // Use MyBatis Plus deleteById which respects @TableLogic for soft delete
        processDefinitionMapper.deleteById(definition.getId());

        log.info("Process deleted: pid={}", pid);
    }

    // ==================== Form Binding Operations ====================

    /**
     * Update form bindings for a process.
     */
    @Transactional
    public BpmProcessDefinition updateFormBindings(String pid, Map<String, Object> formBindings) {
        BpmProcessDefinition definition = getByPid(pid);
        if (definition == null) {
            throw new IllegalArgumentException("Process definition not found: " + pid);
        }

        definition.setFormBindings(formBindings);
        definition.setUpdatedAt(Instant.now());
        processDefinitionMapper.updateById(definition);

        log.info("Updated form bindings: pid={}", pid);
        return definition;
    }

    /**
     * Update timeout/escalation config for a process definition (GAP-003).
     * Persists timeoutHours, timeoutAction and escalateToUserId on the entity.
     */
    @Transactional
    public void updateTimeoutConfig(BpmProcessDefinition definition) {
        definition.setUpdatedAt(Instant.now());
        processDefinitionMapper.updateById(definition);
        log.info("Updated timeout config: pid={}, timeoutHours={}, action={}",
                definition.getPid(), definition.getTimeoutHours(), definition.getTimeoutAction());
    }

    /**
     * Get form binding for a specific user task.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getFormBinding(String processKey, String taskId) {
        BpmProcessDefinition definition = getByProcessKey(processKey);
        if (definition == null || definition.getFormBindings() == null) {
            return null;
        }

        return (Map<String, Object>) definition.getFormBindings().get(taskId);
    }

    // ==================== Helper Methods ====================

    private String getCurrentUserId() {
        return com.auraboot.framework.bpm.util.BpmSecurityUtil.getCurrentUserId();
    }

    /**
     * GAP-254: persist designer-side node hooks into {@code ab_bpm_node_hook} so the
     * runtime {@link BpmNodeHookService} can fetch them on ACTIVITY_START /
     * ACTIVITY_END. Re-deploy is idempotent — we hard-delete prior hooks scoped to
     * this {@code (tenantId, processKey)} tuple before reinserting from the latest
     * designerJson, so designer-side hook removals propagate cleanly.
     */
    private void persistDesignerHooks(String processKey,
                                      com.fasterxml.jackson.databind.JsonNode designerRoot) {
        List<JsonToBpmnConverter.NodeHookEntry> entries =
                jsonToBpmnConverter.extractHookEntries(designerRoot);
        // Always wipe prior hooks for this process key first; an empty designerJson
        // hooks list legitimately means "remove all node hooks for this process".
        Long tenantId = MetaContext.getCurrentTenantId();
        var deleteWrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmNodeHook>()
                .eq("tenant_id", tenantId)
                .eq("process_key", processKey);
        bpmNodeHookMapper.delete(deleteWrapper);

        if (entries.isEmpty()) {
            log.debug("No designer node hooks to persist for processKey={}", processKey);
            return;
        }

        for (JsonToBpmnConverter.NodeHookEntry entry : entries) {
            JsonToBpmnConverter.HookDescriptor d = entry.descriptor();
            BpmNodeHook hook = new BpmNodeHook();
            hook.setProcessKey(processKey);
            hook.setNodeId(entry.nodeId());
            hook.setHookType(d.hookType()); // BpmNodeHookService.createHook normalizes
            hook.setExecutionOrder(d.executionOrder());
            hook.setHookConfig(toMap(d.hookConfigNode()));
            hook.setFailStrategy(d.failStrategy());
            hook.setAsync(d.async());
            hook.setEnabled(d.enabled());
            bpmNodeHookService.createHook(hook);
        }
        log.info("Persisted {} designer node hook(s) for processKey={}", entries.size(), processKey);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    private String compileDesignerJson(String designerJson, String processKey, String processName) {
        return jsonToBpmnConverter.convertFromJsonNode(
                parseCanonicalDesignerJson(designerJson, processKey, processName));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode parseCanonicalDesignerJson(
            String designerJson, String processKey, String processName) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(designerJson);
            root.put("key", processKey);
            if (!StringUtils.hasText(root.path("name").asText()) && StringUtils.hasText(processName)) {
                root.put("name", processName);
            }
            return root;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse designerJson for process '" + processKey + "': " + e.getMessage(), e);
        }
    }

    /**
     * Derive form_bindings from a designerJson string by scanning userTask nodes.
     *
     * <p>For each userTask node the method prefers {@code data.formBinding} (full
     * object) when present; otherwise falls back to {@code data.formPageKey}
     * (plain string) and synthesises a minimal {@code {formType:"PAGE",formRef:key}}
     * binding. Mirrors {@code PluginResourceImporterImpl.deriveFormBindingsFromDesigner}.
     *
     * @param designerJsonStr raw JSON string stored by the designer save path
     * @param processKey      used only for log messages
     * @return non-null map keyed by node-id, or {@code null} when nothing was derived
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deriveFormBindingsFromDesignerJson(
            String designerJsonStr, String processKey) {
        if (!StringUtils.hasText(designerJsonStr)) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = objectMapper.readTree(designerJsonStr);
        } catch (Exception e) {
            log.warn("deriveFormBindings: failed to parse designerJson for process {}: {}",
                    processKey, e.getMessage());
            return null;
        }

        com.fasterxml.jackson.databind.JsonNode nodesNode = root.get("nodes");
        if (nodesNode == null || !nodesNode.isArray() || nodesNode.isEmpty()) {
            return null;
        }

        Map<String, Object> derived = new java.util.LinkedHashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode node : nodesNode) {
            com.fasterxml.jackson.databind.JsonNode typeNode = node.get("type");
            if (typeNode == null || !"userTask".equals(typeNode.asText())) continue;

            com.fasterxml.jackson.databind.JsonNode idNode = node.get("id");
            if (idNode == null || idNode.asText().isBlank()) continue;
            String nodeId = idNode.asText();

            com.fasterxml.jackson.databind.JsonNode dataNode = node.get("data");
            if (dataNode == null || !dataNode.isObject()) continue;

            // Prefer data.formBinding (full object) when present
            com.fasterxml.jackson.databind.JsonNode formBindingNode = dataNode.get("formBinding");
            if (formBindingNode != null && formBindingNode.isObject() && !formBindingNode.isEmpty()) {
                derived.put(nodeId, objectMapper.convertValue(formBindingNode, Map.class));
                continue;
            }

            // Fallback: synthesise from data.formPageKey (plain string)
            com.fasterxml.jackson.databind.JsonNode formPageKeyNode = dataNode.get("formPageKey");
            if (formPageKeyNode != null && !formPageKeyNode.asText().isBlank()) {
                String pageKey = formPageKeyNode.asText();
                Map<String, Object> binding = new java.util.LinkedHashMap<>();
                binding.put("formType", "PAGE");
                binding.put("formRef", pageKey);
                derived.put(nodeId, binding);
            }
        }

        if (derived.isEmpty()) {
            return null;
        }
        log.info("Derived {} form binding(s) from designerJson for process {}", derived.size(), processKey);
        return derived;
    }

    /**
     * Extract designer JSON from the definition's extension field.
     *
     * @return the designer JSON string, or null if not present
     */
    private String getDesignerJson(BpmProcessDefinition definition) {
        Map<String, Object> extension = definition.getExtension();
        if (extension == null) {
            return null;
        }
        Object designerJson = extension.get(EXTENSION_KEY_DESIGNER_JSON);
        return designerJson != null ? designerJson.toString() : null;
    }

    // ==================== Request Records ====================

    public record CreateProcessRequest(
            String processKey,
            String processName,
            String description,
            String category,
            String bpmnContent,
            String designerJson,
            Map<String, Object> formBindings,
            List<Map<String, Object>> businessDataBindings
    ) {}

    public record UpdateProcessRequest(
            String processName,
            String description,
            String category,
            String bpmnContent,
            String designerJson,
            Map<String, Object> formBindings,
            List<Map<String, Object>> businessDataBindings
    ) {}
}

package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.menu.constant.MenuStatus;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.DictCreateRequest;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.DictUpdateRequest;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.dto.NamedQueryCreateRequest;
import com.auraboot.framework.meta.dto.NamedQueryDTO;
import com.auraboot.framework.meta.dto.NamedQueryFieldBatchRequest;
import com.auraboot.framework.meta.dto.NamedQueryFieldRequest;
import com.auraboot.framework.meta.dto.NamedQueryUpdateRequest;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.dashboard.dto.DashboardCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.dto.DashboardUpdateRequest;
import com.auraboot.framework.dashboard.entity.Dashboard;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.plugin.dto.imports.BindingRuleDTO;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DictDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.OwnershipType;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ProcessDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Implementation of resource importer using service layer.
 *
 * Refactored from direct JDBC to use service layer methods for better:
 * - Validation and business rule consistency
 * - Event publishing and cache management
 * - Maintainability
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginResourceImporterImpl implements PluginResourceImporter {

    // Service layer dependencies
    private final MetaModelService metaModelService;
    private final MetaFieldService metaFieldService;
    private final ModelFieldBindingService modelFieldBindingService;
    private final SchemaManagementService schemaManagementService;
    private final DictService dictService;
    private final CommandService commandService;
    private final PermissionService permissionService;
    private final RoleService roleService;
    private final MenuService menuService;
    private final PageSchemaService pageSchemaService;
    private final NamedQueryService namedQueryService;
    private final DashboardService dashboardService;
    // env-layering PoC #16: resolve target env_id for plugin-imported page rows
    private final com.auraboot.framework.environment.service.EnvironmentService environmentService;

    // Infrastructure dependencies
    // LEGITIMATE: JdbcTemplate kept only for resurrectSoftDeleted() which uses dynamic table names
    private final JdbcTemplate jdbcTemplate;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final ObjectMapper objectMapper;

    // Mapper dependencies for plugin import (replaces JdbcTemplate for fixed tables)
    private final MetaModelMapper metaModelMapper;
    private final CommandDefinitionMapper commandDefinitionMapper;
    private final BindingRuleMapper bindingRuleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final DictMapper dictMapper;
    private final NamedQueryMapper namedQueryMapper;
    private final AgentDefinitionMapper agentDefinitionMapper;

    /**
     * Tracks menu code → database ID mappings within a single import session.
     * Used for resolving parentCode to parentId when creating child menus.
     * Must be cleared before each import batch via {@link #clearMenuCodeMap()}.
     */
    private final Map<String, Long> menuCodeToIdMap = new java.util.concurrent.ConcurrentHashMap<>();

    // Dependencies for plugin reimport support (in-place update)
    private final MetaFieldMapper metaFieldMapper;
    private final ExtensionConverter extensionConverter;
    private final PluginResourceMapper pluginResourceMapper;
    private final com.auraboot.framework.meta.service.impl.CommandMetadataCacheService commandMetadataCache;

    // Dependencies for deploying BPMN to SmartEngine at import time.
    private final com.auraboot.framework.bpm.converter.JsonToBpmnConverter jsonToBpmnConverter;
    private final com.auraboot.smart.framework.engine.SmartEngine smartEngine;

    // Optional dependency - may not be configured in all environments
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CacheManager cacheManager;

    // ==================== Existence Checks ====================

    @Override
    public boolean checkModelExists(Long tenantId, String code) {
        return metaModelService.isModelExists(code);
    }

    @Override
    public boolean checkFieldExists(Long tenantId, String code) {
        return metaFieldService.isFieldExists(code);
    }

    @Override
    public boolean checkCommandExists(Long tenantId, String code) {
        try {
            com.auraboot.framework.meta.dto.CommandDefinitionDTO cmd = commandService.findByCode(code);
            return cmd != null;
        } catch (Exception e) {
            // findByCode lacks a NotFoundException type today; any failure is treated as
            // "does not exist" so a re-import can proceed with create. Logged at debug
            // so a real DB/connectivity failure is still observable when troubleshooting.
            log.debug("checkCommandExists treating exception as 'not exists' for code={}: {}", code, e.toString());
            return false;
        }
    }

    @Override
    public boolean checkPermissionExists(Long tenantId, String code) {
        try {
            PermissionDTO perm = permissionService.findByCode(code);
            return perm != null;
        } catch (Exception e) {
            log.debug("checkPermissionExists treating exception as 'not exists' for code={}: {}", code, e.toString());
            return false;
        }
    }

    @Override
    public boolean checkRoleExists(Long tenantId, String code) {
        return roleMapper.existsByCode(tenantId, code);
    }

    @Override
    public boolean checkMenuExists(Long tenantId, String code) {
        return menuMapper.existsByCode(tenantId, code);
    }

    /**
     * Clear the menu code-to-ID map. Should be called before each plugin import session.
     */
    public void clearMenuCodeMap() {
        menuCodeToIdMap.clear();
    }

    @Override
    public boolean checkProcessExists(Long tenantId, String key) {
        return processDefinitionMapper.existsByProcessKey(tenantId, key);
    }

    @Override
    public boolean checkPageExists(Long tenantId, String pageKey) {
        com.auraboot.framework.meta.dto.PageSchemaDTO page = pageSchemaService.findAnyByPageKey(pageKey);
        return page != null;
    }

    @Override
    public boolean checkDictExists(Long tenantId, String code) {
        DictDTO dict = dictService.findByCode(code);
        return dict != null;
    }

    @Override
    public boolean checkNamedQueryExists(Long tenantId, String code) {
        try {
            NamedQueryDTO query = namedQueryService.findByCode(code);
            return query != null;
        } catch (Exception e) {
            log.debug("checkNamedQueryExists treating exception as 'not exists' for code={}: {}", code, e.toString());
            return false;
        }
    }

    @Override
    public boolean checkAgentDefinitionExists(Long tenantId, String agentCode) {
        return findActiveAgentDefinition(tenantId, agentCode) != null;
    }

    // ==================== OVERWRITE_SAFE Helper ====================

    /**
     * Check if a resource has been modified by a user (for OVERWRITE_SAFE strategy).
     * Returns true if the resource is tracked in ab_plugin_resource and marked as user_modified.
     */
    private boolean isResourceUserModified(Long tenantId, ResourceType type, String resourceCode) {
        try {
            PluginResource pr = pluginResourceMapper.findByTypeAndCode(tenantId, type.code(), resourceCode);
            return pr != null && Boolean.TRUE.equals(pr.getUserModified());
        } catch (Exception e) {
            log.debug("Failed to check user-modified status for {} {}: {}", type, resourceCode, e.getMessage());
            return false;
        }
    }

    /**
     * Check if OVERWRITE_SAFE strategy should skip this resource.
     * Returns true if the strategy is OVERWRITE_SAFE and the resource has been user-modified.
     */
    private boolean shouldSkipForOverwriteSafe(Long tenantId, ImportRequest.ConflictStrategy strategy,
                                                ResourceType type, String resourceCode) {
        return strategy == ImportRequest.ConflictStrategy.OVERWRITE_SAFE
                && isResourceUserModified(tenantId, type, resourceCode);
    }

    /**
     * Detect a page row created by {@code MetaModelServiceImpl.autoCreateDefaultPages}. Such rows
     * carry no user content and are tagged with {@code extension.auto_created=true}. Plugin imports
     * must overwrite them unconditionally so that the canonical DSL from {@code config/pages/*.json}
     * lands in place of the stub.
     *
     * <p>Returns {@code false} on any lookup error or if the flag is absent — the caller will then
     * fall back to the normal OVERWRITE_SAFE skip behavior.
     */
    private boolean isAutoCreatedStubPage(String pageKey) {
        try {
            com.auraboot.framework.meta.dto.PageSchemaDTO existing = pageSchemaService.findAnyByPageKey(pageKey);
            if (existing == null || existing.getExtension() == null) {
                return false;
            }
            Object flag = existing.getExtension().get("auto_created");
            return Boolean.TRUE.equals(flag) || "true".equals(String.valueOf(flag));
        } catch (Exception e) {
            // Defensive: on lookup failure, do not bypass the user-modified guard.
            log.debug("isAutoCreatedStubPage lookup failed for pageKey={}: {}", pageKey, e.getMessage());
            return false;
        }
    }

    // ==================== Import Operations ====================

    @Override
    public PluginResource importModel(ModelDefinitionDTO dto, String pluginPid, String importId,
                                       Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                       Boolean autoPublish) {
        boolean exists = checkModelExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Model already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.MODEL, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.MODEL, dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        // Build extension map
        Map<String, Object> extension = dto.getExtension() != null ? new HashMap<>(dto.getExtension()) : new HashMap<>();
        extension.put("displayName", dto.getEffectiveDisplayName());
        extension.put("description", dto.getDescription());
        extension.put("modelType", dto.getModelType());
        if (dto.getModelCategory() != null) {
            extension.put("modelCategory", dto.getModelCategory());
        }
        // Warn on unknown extension keys (informational, does not block)
        ExtensionBean.warnUnknownModelKeys(dto.getCode(), extension.keySet(), log);
        // tableName is a first-class column. Extract from extension as fallback (legacy format).
        String effectiveTableName = dto.getTableName();
        if (effectiveTableName == null && extension.containsKey("tableName")) {
            effectiveTableName = (String) extension.get("tableName");
            extension.remove("tableName"); // Remove from extension — it's stored as a first-class column
        }

        if (exists) {
            // Update existing model via service
            MetaModelDTO existingModel = metaModelService.findByCode(dto.getCode());

            // Wrap in nested format to match ExtensionBean serialization: {"extension": {...}}
            Map<String, Object> wrappedExtension = new HashMap<>();
            wrappedExtension.put("extension", extension);
            String extensionJson = toJson(wrappedExtension);
            metaModelMapper.updateForPluginImport(extensionJson, pluginPid, effectiveTableName,
                    dto.getModelCategory(),
                    dto.getSemanticDescription(), dto.getDomainCategory(),
                    dto.getDataSensitivity(), dto.getLifecycleDescription(),
                    tenantId, dto.getCode());
            // Evict ALL tenant caches for this model — plugin import affects all tenants,
            // and per-tenant eviction would leave other tenants with stale tableName/softDelete.
            metaModelService.clearAllCache();

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL,
                    existingModel.getPid(), null, dto.getCode(), dto.getEffectiveDisplayName(),
                    ResourceAction.UPDATE, null, extension);
        } else {
            // Check for soft-deleted record and resurrect if found
            String resurrectPid = resurrectSoftDeleted("ab_meta_model", "code", dto.getCode(), tenantId, pluginPid, extension);
            if (resurrectPid != null) {
                // Also set the first-class table_name column
                if (effectiveTableName != null) {
                    metaModelMapper.updateTableNameByPid(effectiveTableName, resurrectPid);
                }
                log.info("Resurrected soft-deleted model: code={}, pid={}", dto.getCode(), resurrectPid);
                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL,
                        resurrectPid, null, dto.getCode(), dto.getEffectiveDisplayName(),
                        ResourceAction.CREATE, null, extension);
            }

            // Create new model via service with pluginPid
            MetaModelCreateRequest request = new MetaModelCreateRequest();
            request.setCode(dto.getCode());
            request.setDisplayName(dto.getEffectiveDisplayName());
            request.setDescription(dto.getDescription());
            request.setModelType(dto.getModelType() != null ? dto.getModelType() : "entity");
            request.setModelCategory(dto.getModelCategory());
            request.setSemanticDescription(dto.getSemanticDescription());
            request.setDomainCategory(dto.getDomainCategory());
            request.setDataSensitivity(dto.getDataSensitivity());
            request.setLifecycleDescription(dto.getLifecycleDescription());
            request.setTableName(effectiveTableName);
            request.setExtension(extension);
            request.setTenantId(tenantId);
            request.setPluginPid(pluginPid);  // Set plugin_pid via request

            MetaModelDTO created = metaModelService.create(request);

            // NOTE: descriptor's autoPublish flag is honored by the plugin import
            // post-processor (publishes after fields + bindings are imported).
            // Do not publish here — at this point only the draft model exists; field
            // bindings haven't been imported yet, and publish() requires ≥1 binding.

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL,
                    created.getPid(), null, dto.getCode(), dto.getEffectiveDisplayName(),
                    ResourceAction.CREATE, null, extension);
        }
    }

    @Override
    public PluginResource importField(FieldDefinitionDTO dto, String pluginPid, String importId,
                                       Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                       Boolean autoPublish) {
        boolean exists = checkFieldExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Field already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.FIELD,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.FIELD, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.FIELD, dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.FIELD,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        // Build extension map
        Map<String, Object> extension = buildFieldExtension(dto);

        PluginResource result;
        if (exists) {
            // Update existing field in place (without creating new version)
            result = updateFieldForReimport(dto, pluginPid, importId, tenantId, extension);
        } else {
            // Check for soft-deleted record and resurrect if found
            String resurrectPid = resurrectSoftDeleted("ab_meta_field", "code", dto.getCode(), tenantId, pluginPid, extension);
            if (resurrectPid != null) {
                log.info("Resurrected soft-deleted field: code={}, pid={}", dto.getCode(), resurrectPid);
                result = createResourceRecord(pluginPid, importId, tenantId, ResourceType.FIELD,
                        resurrectPid, null, dto.getCode(), dto.getEffectiveDisplayName(),
                        ResourceAction.CREATE, null, extension);
            } else {
                // Create new field via service
                result = createNewField(dto, pluginPid, importId, tenantId, extension, autoPublish);
            }
        }

        // Auto-publish updated fields that are still in draft
        if (Boolean.TRUE.equals(autoPublish) && result != null && result.getResourcePid() != null) {
            MetaFieldDTO field = metaFieldService.findCurrentByCode(dto.getCode()).orElse(null);
            if (field != null && "draft".equalsIgnoreCase(field.getStatus())) {
                try {
                    metaFieldService.publishVersion(field.getPid());
                    log.info("Auto-published field after import: {}", dto.getCode());
                } catch (Exception e) {
                    // Auto-publish is best-effort: import succeeded, the field exists in
                    // draft state, an admin can publish manually later. Don't fail the
                    // whole import for a publish hiccup.
                    log.warn("Failed to auto-publish field {}: {}", dto.getCode(), e.getMessage(), e);
                }
            }
        }

        // Bind dictionary if dictCode is specified
        if (dto.getDictCode() != null && !dto.getDictCode().isBlank()) {
            MetaFieldDTO field = metaFieldService.findCurrentByCode(dto.getCode()).orElse(null);
            if (field != null) {
                metaFieldService.bindDictionary(field.getPid(), dto.getDictCode());
                log.info("Bound dictionary to field: fieldCode={}, dictCode={}", dto.getCode(), dto.getDictCode());
            }
        }

        return result;
    }

    /**
     * Update existing field in place for plugin reimport (without creating new version).
     * Uses Mapper directly to bypass version creation and uniqueness validation.
     */
    private PluginResource updateFieldForReimport(FieldDefinitionDTO dto, String pluginPid,
                                                   String importId, Long tenantId, Map<String, Object> extension) {
        // 1. Find existing field
        Field existingField = metaFieldMapper.findCurrentByCode(dto.getCode());
        if (existingField == null) {
            throw new PluginException("Field exists but cannot be found: " + dto.getCode());
        }

        // 2. Convert extension to ExtensionBean
        var extensionBean = extensionConverter.toBean(extension);

        // 3. Update in place via Mapper (no version creation, no validation)
        int updated = metaFieldMapper.updateFieldInPlace(
            existingField.getPid(),
            dto.getDataType(),
            buildFieldFeature(dto),
            resolveFieldRefTarget(dto),
            extensionBean,
            pluginPid
        );

        if (updated == 0) {
            throw new PluginException("Failed to update field: " + dto.getCode());
        }

        // 4. Evict cache
        evictFieldCache(existingField.getPid());

        log.info("Field updated in place for plugin reimport: code={}, pid={}",
                 dto.getCode(), existingField.getPid());

        // 5. Return resource record
        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.FIELD,
                existingField.getPid(), null, dto.getCode(), dto.getEffectiveDisplayName(),
                ResourceAction.UPDATE, null, extension);
    }

    /**
     * Create new field via service layer.
     */
    private PluginResource createNewField(FieldDefinitionDTO dto, String pluginPid,
                                           String importId, Long tenantId, Map<String, Object> extension,
                                           Boolean autoPublish) {
        MetaFieldCreateRequest request = new MetaFieldCreateRequest();
        request.setCode(dto.getCode());
        request.setDataType(dto.getDataType());
        request.setFeature(buildFieldFeature(dto));
        request.setRefTarget(resolveFieldRefTarget(dto));
        request.setUiSchema(dto.getUiSchema());
        request.setQuerySchema(dto.getQuerySchema());
        request.setExtension(extension);
        request.setAutoPublish(Boolean.TRUE.equals(autoPublish));
        request.setPluginPid(pluginPid);

        MetaFieldDTO created = metaFieldService.create(request);

        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.FIELD,
                created.getPid(), null, dto.getCode(), dto.getEffectiveDisplayName(),
                ResourceAction.CREATE, null, extension);
    }

    /**
     * Build field extension map from DTO.
     */
    private Map<String, Object> buildFieldExtension(FieldDefinitionDTO dto) {
        Map<String, Object> extension = dto.getExtension() != null ? new HashMap<>(dto.getExtension()) : new HashMap<>();
        extension.put("displayName", dto.getEffectiveDisplayName());
        extension.put("description", dto.getDescription());
        if (dto.getFeature() != null) {
            extension.put("feature", dto.getFeature());
        }
        if (dto.getConstraints() != null) {
            Map<String, Object> constraints = new LinkedHashMap<>();
            if (dto.getConstraints().getRequired() != null) constraints.put("required", dto.getConstraints().getRequired());
            if (dto.getConstraints().getMaxLength() != null) constraints.put("maxLength", dto.getConstraints().getMaxLength());
            if (dto.getConstraints().getMinLength() != null) constraints.put("minLength", dto.getConstraints().getMinLength());
            if (dto.getConstraints().getPattern() != null) constraints.put("pattern", dto.getConstraints().getPattern());
            if (dto.getConstraints().getMin() != null) constraints.put("min", dto.getConstraints().getMin());
            if (dto.getConstraints().getMax() != null) constraints.put("max", dto.getConstraints().getMax());
            if (dto.getConstraints().getUnique() != null) constraints.put("unique", dto.getConstraints().getUnique());
            if (!constraints.isEmpty()) {
                extension.put("constraints", constraints);
            }
        }
        // Always try to resolve refTarget for reference-type fields, even when only
        // referenceModelCode is specified (without an explicit refTarget block).
        Map<String, Object> resolvedRefTarget = resolveFieldRefTarget(dto);
        if (resolvedRefTarget != null) {
            extension.put("refTarget", resolvedRefTarget);
        }
        if (dto.getUiSchema() != null) {
            extension.put("uiSchema", dto.getUiSchema());
        }
        if (dto.getQuerySchema() != null) {
            extension.put("querySchema", dto.getQuerySchema());
        }
        return extension;
    }

    private Map<String, Object> buildFieldFeature(FieldDefinitionDTO dto) {
        Map<String, Object> feature = dto.getFeature() != null ? new LinkedHashMap<>(dto.getFeature()) : new LinkedHashMap<>();
        if (dto.getConstraints() == null) return feature.isEmpty() ? null : feature;

        // GAP-259 regression guard: field-level `constraints.required` MUST NOT propagate to
        // the global FieldFeatureBean. Required-ness is a per-binding concept (one field can be
        // required in model_A but optional in model_B). Writing it to feature.required causes
        // cross-plugin cross-model pollution — any plugin re-importing the same field code
        // silently flips NOT NULL semantics on every model bound to it (downstream DDL in
        // SchemaManagementServiceImpl reads field.isRequired() to emit NOT NULL columns).
        // Use ModelFieldBindingDTO.required for per-binding required-ness instead.
        if (dto.getConstraints().getUnique() != null) {
            feature.put("unique", dto.getConstraints().getUnique());
        }

        Map<String, Object> validation = new LinkedHashMap<>();
        if (feature.get("validation") instanceof Map<?, ?> existingValidation) {
            for (Map.Entry<?, ?> entry : existingValidation.entrySet()) {
                if (entry.getKey() != null) {
                    validation.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        if (dto.getConstraints().getMaxLength() != null) validation.put("maxLength", dto.getConstraints().getMaxLength());
        if (dto.getConstraints().getMinLength() != null) validation.put("minLength", dto.getConstraints().getMinLength());
        if (dto.getConstraints().getPattern() != null) validation.put("pattern", dto.getConstraints().getPattern());
        if (dto.getConstraints().getMin() != null) validation.put("minValue", dto.getConstraints().getMin());
        if (dto.getConstraints().getMax() != null) validation.put("maxValue", dto.getConstraints().getMax());
        if (!validation.isEmpty()) {
            feature.put("validation", validation);
        }

        return feature.isEmpty() ? null : feature;
    }

    private Map<String, Object> resolveFieldRefTarget(FieldDefinitionDTO dto) {
        if (dto.getRefTarget() != null && !dto.getRefTarget().isEmpty()) {
            return dto.getRefTarget();
        }
        if (!"reference".equalsIgnoreCase(dto.getDataType())) {
            return null;
        }

        // Determine the effective reference model code.
        // Priority: explicit referenceModelCode > extension.referenceModelCode > extension.refModelCode
        String modelCode = dto.getReferenceModelCode();
        if ((modelCode == null || modelCode.isBlank()) && dto.getExtension() != null) {
            Object extRefModelCode = dto.getExtension().get("referenceModelCode");
            if (extRefModelCode == null) {
                extRefModelCode = dto.getExtension().get("refModelCode");
            }
            if (extRefModelCode instanceof String s && !s.isBlank()) {
                modelCode = s;
            }
        }

        if (modelCode == null || modelCode.isBlank()) {
            return null;
        }

        Map<String, Object> refTarget = new LinkedHashMap<>();
        refTarget.put("refType", "entity");
        refTarget.put("targetEntity", modelCode);
        refTarget.put("modelCode", modelCode);

        // Resolve display field from extension.refDisplayField
        // Must use "displayField" key to match DynamicDataServiceImpl.enrichReferenceDisplayFields()
        if (dto.getExtension() != null) {
            Object refDisplayField = dto.getExtension().get("refDisplayField");
            if (refDisplayField instanceof String s && !s.isBlank()) {
                refTarget.put("displayField", s);
            }
        }

        return refTarget;
    }

    /**
     * Evict field cache after update.
     */
    private void evictFieldCache(String pid) {
        if (cacheManager != null) {
            Cache cache = cacheManager.getCache("metaField");
            if (cache != null) {
                cache.evict(pid);
            }
        }
    }

    @Override
    public PluginResource importModelFieldBinding(ModelFieldBindingDTO dto, String pluginPid, String importId,
                                                   Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        // Get model and field IDs
        MetaModelDTO model = metaModelService.findByCode(dto.getModelCode());
        if (model == null) {
            throw new PluginException("Model not found: " + dto.getModelCode());
        }

        MetaFieldDTO field = metaFieldService.findCurrentByCode(dto.getFieldCode()).orElse(null);
        if (field == null) {
            throw new PluginException("Field not found: " + dto.getFieldCode());
        }

        Long modelId = model.getId();
        Long fieldId = field.getId();
        String bindingCode = dto.getModelCode() + "." + dto.getFieldCode();

        // Check if binding exists
        boolean exists = metaModelService.isFieldBoundToModel(modelId, fieldId);

        // Get binding pid for resource tracking
        String bindingPid = fieldBindingMapper.getPidByModelAndField(modelId, fieldId);

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                    bindingPid, null, bindingCode, bindingCode, ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.MODEL_FIELD_BINDING, bindingCode)) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.MODEL_FIELD_BINDING, bindingCode);
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                    bindingPid, null, bindingCode, bindingCode, ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing binding (may be soft-deleted, so also clear deleted_flag)
            var existingBinding = metaModelService.getFieldBinding(modelId, fieldId).orElse(null);
            if (existingBinding != null) {
                existingBinding.setFieldOrder(dto.getSequence());
                existingBinding.setRequired(dto.getRequired());
                existingBinding.setVisible(dto.getVisible());
                existingBinding.setEditable(dto.getEditable());
                existingBinding.setDefaultValue(dto.getDefaultValue());
                existingBinding.setAliasCode(dto.getAliasCode());
                existingBinding.setDictOverrideCode(dto.getDictOverrideCode());
                existingBinding.setUiHint(dto.getUiHint());
                existingBinding.setIsSystemBinding(dto.getIsSystemBinding());
                // Clear soft-delete flag (entity doesn't have deletedFlag to avoid MyBatis Plus global logical delete)
                fieldBindingMapper.clearDeletedFlag(modelId, fieldId);

                metaModelService.updateFieldBinding(existingBinding);
            }

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                    bindingPid, null, bindingCode, bindingCode, ResourceAction.UPDATE, null, null);
        } else {
            // Check if a soft-deleted binding exists (isFieldBoundToModel filters deleted_flag)
            int softDeletedCount = fieldBindingMapper.countSoftDeleted(modelId, fieldId);

            if (softDeletedCount > 0) {
                // Resurrect soft-deleted binding instead of inserting (would violate unique constraint)
                fieldBindingMapper.resurrectBinding(
                        dto.getSequence(), dto.getRequired(), dto.getVisible(), dto.getEditable(),
                        dto.getDefaultValue(), modelId, fieldId);

                if (dto.getAliasCode() != null || dto.getDictOverrideCode() != null ||
                    dto.getUiHint() != null || dto.getIsSystemBinding() != null) {
                    fieldBindingMapper.updateExtraFields(modelId, fieldId,
                            dto.getAliasCode(), dto.getDictOverrideCode(),
                            dto.getUiHint(), dto.getIsSystemBinding());
                }

                bindingPid = fieldBindingMapper.getPidByModelAndField(modelId, fieldId);
                log.info("Resurrected soft-deleted binding: {}", bindingCode);

                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                        bindingPid, null, bindingCode, bindingCode, ResourceAction.CREATE, null, null);
            }

            // Create new binding via service
            metaModelService.bindFieldToModel(
                modelId, fieldId, dto.getSequence(), dto.getRequired(),
                dto.getVisible(), dto.getEditable(), dto.getDefaultValue(),
                null, null, null  // validationRules, displayConfig, remarks
            );

            // Update additional fields via Mapper
            if (dto.getAliasCode() != null || dto.getDictOverrideCode() != null ||
                dto.getUiHint() != null || dto.getIsSystemBinding() != null) {
                fieldBindingMapper.updateExtraFields(modelId, fieldId,
                        dto.getAliasCode(), dto.getDictOverrideCode(),
                        dto.getUiHint(), dto.getIsSystemBinding());
            }

            // Get the newly created binding's pid
            bindingPid = fieldBindingMapper.getPidByModelAndField(modelId, fieldId);

            if (StatusConstants.PUBLISHED.equals(model.getStatus())) {
                // Auto-publish the field when binding to an already-published model
                if (!StatusConstants.PUBLISHED.equals(field.getStatus())) {
                    Field fieldEntity = metaFieldMapper.selectById(fieldId);
                    if (fieldEntity != null) {
                        fieldEntity.setStatus(StatusConstants.PUBLISHED);
                        metaFieldMapper.updateById(fieldEntity);
                        log.info("Auto-published field {} (bound to published model {})", dto.getFieldCode(), dto.getModelCode());
                    }
                }

                SchemaOperationResult schemaResult = schemaManagementService.updateTableByModel(dto.getModelCode());
                if (schemaResult == null || !schemaResult.isSuccess()) {
                    String errorMessage = schemaResult != null && schemaResult.getErrorMessage() != null
                            ? schemaResult.getErrorMessage()
                            : "unknown schema sync error";
                    throw new PluginException("Failed to sync schema after binding field " + dto.getFieldCode()
                            + " to model " + dto.getModelCode() + ": " + errorMessage);
                }
            }

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                    bindingPid, null, bindingCode, bindingCode, ResourceAction.CREATE, null, null);
        }
    }

    @Override
    public PluginResource importCommand(CommandDefinitionDTO dto, String pluginPid, String importId,
                                         Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                         Boolean autoPublish) {
        // Warn about inline bindingRules — they are silently ignored during command import.
        // Binding rules must be in a separate bindingRules.json registered in plugin.json resourceDirs.
        if (dto.getBindingRules() != null && !dto.getBindingRules().isEmpty()) {
            log.warn("Command '{}' has inline 'bindingRules' in commands.json — "
                    + "this field is ignored during import. "
                    + "Use a separate bindingRules.json file registered in plugin.json resourceDirs.bindingRules.",
                    dto.getCode());
        }

        boolean exists = checkCommandExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Command already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.COMMAND,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.COMMAND, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.COMMAND, dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.COMMAND,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing command in place (bypassing DRAFT status check for plugin reimport)
            com.auraboot.framework.meta.dto.CommandDefinitionDTO existingCmd = commandService.findByCode(dto.getCode());

            Map<String, Object> consolidatedConfig = dto.getConsolidatedExecutionConfig();
            String inputSchemaJson = dto.getInputSchema() != null ? toJson(dto.getInputSchema()) : "{}";
            String targetModelsJson = dto.getTargetModels() != null ? toJson(dto.getTargetModels()) : "[]";
            String executionConfigJson = consolidatedConfig != null ? toJson(consolidatedConfig) : "{}";
            String extensionJson = dto.getExtension() != null ? toJson(dto.getExtension()) : "{}";
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
                     dto.getCode(), existingCmd.getPid());

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
            request.setInputSchema(dto.getInputSchema() != null ? toJson(dto.getInputSchema()) : null);
            request.setTargetModels(dto.getTargetModels() != null ? toJson(dto.getTargetModels()) : null);
            // Use consolidated config that merges structured ExecutionConfig with DSL extended fields
            Map<String, Object> consolidatedConfig = dto.getConsolidatedExecutionConfig();
            request.setExecutionConfig(consolidatedConfig != null ? toJson(consolidatedConfig) : null);
            request.setCmdRiskLevel(resolveCommandRiskLevel(dto, consolidatedConfig));
            request.setPluginPid(pluginPid);  // Set plugin_pid via request
            request.setExtension(dto.getExtension() != null ? toJson(dto.getExtension()) : "{}");

            com.auraboot.framework.meta.dto.CommandDefinitionDTO created = commandService.create(request);

            // Auto-publish if requested
            if (Boolean.TRUE.equals(autoPublish)) {
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

    @Override
    public PluginResource importBindingRule(BindingRuleDTO dto, String pluginPid, String importId,
                                             Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        // Get command
        com.auraboot.framework.meta.dto.CommandDefinitionDTO command;
        try {
            command = commandService.findByCode(dto.getCommandCode());
        } catch (Exception e) {
            throw new PluginException("Command not found: " + dto.getCommandCode(), e);
        }

        String ruleCode = dto.getCommandCode() + ":" + dto.getRuleType();

        // Check if binding rule already exists for this command + ruleType
        List<com.auraboot.framework.meta.dto.BindingRuleDTO> existingRules = commandService.getBindingRules(command.getPid());
        boolean exists = existingRules.stream()
                .anyMatch(r -> dto.getRuleType().equals(r.getRuleType()));

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Binding rule already exists: " + ruleCode);
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.BINDING_RULE,
                    null, null, ruleCode, dto.getRuleType(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.BINDING_RULE, ruleCode)) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.BINDING_RULE, ruleCode);
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.BINDING_RULE,
                    null, null, ruleCode, dto.getRuleType(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // OVERWRITE: delete existing rules with same ruleType, then recreate
            existingRules.stream()
                    .filter(r -> dto.getRuleType().equals(r.getRuleType()))
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
        serviceDto.setConfig(dto.getConfig() != null ? toJson(dto.getConfig()) : null);
        serviceDto.setSequence(dto.getSequence());
        serviceDto.setEnabled(dto.getEnabled());

        com.auraboot.framework.meta.dto.BindingRuleDTO created = commandService.addBindingRule(command.getPid(), serviceDto);

        // Update plugin_pid
        bindingRuleMapper.updatePluginPid(pluginPid, created.getPid());

        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.BINDING_RULE,
                created.getPid(), null, ruleCode, dto.getRuleType(),
                exists ? ResourceAction.UPDATE : ResourceAction.CREATE, null, null);
    }

    @Override
    public PluginResource importPermission(PermissionDefinitionDTO dto, String pluginPid, String importId,
                                            Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        boolean exists = checkPermissionExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Permission already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.PERMISSION, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.PERMISSION, dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing permission
            PermissionDTO existingPerm = permissionService.findByCode(dto.getCode());

            String dataScopeConfigJson = dto.getDataScopeConfig() != null ? toJson(dto.getDataScopeConfig()) : null;
            String extensionJson = dto.getExtension() != null ? toJson(dto.getExtension()) : null;
            String tagsArray = dto.getTags() != null ? "{" + String.join(",", dto.getTags()) + "}" : null;

            permissionMapper.updateForPluginImport(
                    dto.getEffectiveName(), dto.getDescription(), dto.getCategory(),
                    dto.getResourceType(), dto.getResourceCode(), dto.getAction(),
                    dto.getDataScopeType(), dataScopeConfigJson, extensionJson, tagsArray,
                    pluginPid, tenantId, dto.getCode());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    existingPerm.getPid(), null, dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Check for soft-deleted permission and resurrect if found
            String resurrectPid = resurrectSoftDeleted("ab_permission", "code", dto.getCode(), tenantId, pluginPid, null);
            if (resurrectPid != null) {
                log.info("Resurrected soft-deleted permission: code={}, pid={}", dto.getCode(), resurrectPid);
                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                        resurrectPid, null, dto.getCode(), dto.getEffectiveName(),
                        ResourceAction.CREATE, null, null);
            }

            // Create new permission via service with pluginPid
            PermissionCreateRequest request = new PermissionCreateRequest();
            request.setCode(dto.getCode());
            request.setName(dto.getEffectiveName());
            request.setDescription(dto.getDescription());
            request.setResourceType(dto.getResourceType() != null ? dto.getResourceType() : "menu");
            request.setResourceCode(dto.getResourceCode() != null ? dto.getResourceCode() : dto.getCode());
            request.setAction(dto.getAction() != null ? dto.getAction() : "view");
            request.setDataScopeType(dto.getDataScopeType());
            request.setDataScopeConfig(dto.getDataScopeConfig());
            request.setExtension(dto.getExtension());
            request.setTags(dto.getTags() != null ? dto.getTags().toArray(new String[0]) : null);
            request.setPluginPid(pluginPid);  // Set plugin_pid via request

            PermissionDTO created = permissionService.create(request);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    @Override
    public PluginResource importRole(RoleDefinitionDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        // Guard: platform-only role codes cannot be imported into business tenants
        if (com.auraboot.framework.rbac.constant.RoleConstants.isPlatformOnly(dto.getCode())) {
            String scopeType = dto.getScopeType() != null ? dto.getScopeType() : "tenant";
            if (!"global".equals(scopeType)) {
                throw new PluginException(
                    "Role code '" + dto.getCode() + "' is reserved for platform level (scope_type=global). "
                    + "Plugin cannot create it in a business tenant.");
            }
        }

        boolean exists = checkRoleExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Role already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.ROLE, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.ROLE, dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing role
            String scopeContentJson = dto.getScopeContent() != null ? toJson(dto.getScopeContent()) : null;

            roleMapper.updateForPluginImport(
                    dto.getEffectiveName(), dto.getDescription(), dto.getType(),
                    dto.getPriority(), dto.getIsDefault(), dto.getIsSystem(),
                    dto.getScopeType(), scopeContentJson, pluginPid, tenantId, dto.getCode());

            // Update role-permission bindings using Mapper
            Long roleId = roleMapper.findIdByCode(tenantId, dto.getCode());
            updateRolePermissions(roleId, dto.getPermissions(), tenantId, pluginPid);

            String existingPid = roleMapper.findPidByCode(tenantId, dto.getCode());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    existingPid, roleId, dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Create new role via service
            Role role = new Role();
            role.setPid(UlidGenerator.generate());
            role.setTenantId(tenantId);
            role.setCode(dto.getCode());
            role.setName(dto.getEffectiveName());
            role.setDescription(dto.getDescription());
            role.setType(dto.getType() != null ? dto.getType() : "custom");
            role.setPriority(dto.getPriority() != null ? dto.getPriority() : 100);
            role.setIsDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false);
            role.setIsSystem(dto.getIsSystem() != null ? dto.getIsSystem() : false);
            role.setScopeType(dto.getScopeType() != null ? dto.getScopeType() : "tenant");
            role.setStatus(StatusConstants.ACTIVE);
            role.setDeletedFlag(false);
            role.setCreatedAt(Instant.now());
            role.setUpdatedAt(Instant.now());

            Role created = roleService.createRole(role);

            // Update plugin_pid
            roleMapper.updatePluginPidById(pluginPid, created.getId());

            // Create role-permission bindings
            updateRolePermissions(created.getId(), dto.getPermissions(), tenantId, pluginPid);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    private void updateRolePermissions(Long roleId, List<String> permissionCodes, Long tenantId, String pluginPid) {
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return;
        }

        for (String permCode : permissionCodes) {
            try {
                PermissionDTO perm = permissionService.findByCode(permCode);
                if (perm == null) {
                    log.warn("Permission not found for role binding: {}", permCode);
                    continue;
                }

                // Check if binding exists
                int count = rolePermissionMapper.countByRoleAndPermission(roleId, perm.getId(), tenantId);

                if (count == 0) {
                    // Use service to bind
                    permissionService.bindToRole(roleId, perm.getId());
                }
            } catch (Exception e) {
                // Bind loop is per-permission best-effort: a single failed binding
                // (missing perm, race with another import) must not block the rest
                // of the role's permission set. Failure surfaced via warn log only.
                log.warn("Failed to bind permission {} to role: {}", permCode, e.getMessage(), e);
            }
        }
    }

    @Override
    public PluginResource importMenu(MenuDefinitionDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        // Validate: dynamic menus must have pageKey
        String path = dto.getPath();
        if (path != null && path.startsWith("/dynamic/")
                && (dto.getPageKey() == null || dto.getPageKey().isBlank())) {
            throw new PluginException(
                    "Menu '" + dto.getCode() + "' has dynamic path '" + path
                    + "' but missing pageKey. Add pageKey to menus.json.");
        }

        boolean exists = checkMenuExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Menu already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MENU,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.MENU, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.MENU, dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MENU,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        // Get parent ID if specified
        Long parentId = null;
        if (dto.getParentCode() != null) {
            // First check in-memory map (populated during same import session)
            parentId = menuCodeToIdMap.get(dto.getParentCode());
            if (parentId == null) {
                // Strict lookup by code only.
                parentId = menuMapper.findIdByCode(tenantId, dto.getParentCode());
                if (parentId == null) {
                    log.warn("Parent menu not found by code '{}'", dto.getParentCode());
                }
            }
        }

        // Build extension with modelCode and pageType
        Map<String, Object> extension = dto.getExtension() != null
                ? new java.util.HashMap<>(dto.getExtension())
                : new java.util.HashMap<>();
        if (dto.getModelCode() != null && !dto.getModelCode().isBlank()) {
            extension.put("modelCode", dto.getModelCode());
        }
        if (dto.getKind() != null && !dto.getKind().isBlank()) {
            extension.put("pageType", dto.getKind());
        }
        String extensionJson = extension.isEmpty() ? null : toJson(extension);
        String pageKey = dto.getPageKey() != null && !dto.getPageKey().isBlank() ? dto.getPageKey() : null;

        if (exists) {
            // Update existing menu — also update name to effective name

            menuMapper.updateForPluginImport(
                    dto.getCode(), dto.getEffectiveName(), dto.getPath(), dto.getComponent(), dto.getIcon(), dto.getType(),
                    parentId, dto.getPermissionCode(), dto.getVisible() != null ? dto.getVisible() : true, dto.getOrderNo(),
                    dto.getI18nKey(), dto.getRedirect(), pageKey, dto.getPagePid(), extensionJson,
                    pluginPid, tenantId, dto.getCode());

            String existingPid = menuMapper.findPidByCode(tenantId, dto.getCode());

            // Store in map for child resolution
            Long existingId = menuMapper.findIdByPid(tenantId, existingPid);
            menuCodeToIdMap.put(dto.getCode(), existingId);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MENU,
                    existingPid, null, dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Create new menu via service
            Menu menu = new Menu();
            menu.setPid(UlidGenerator.generate());
            menu.setTenantId(tenantId);
            menu.setCode(dto.getCode());
            menu.setName(dto.getEffectiveName());
            menu.setPath(dto.getPath());
            menu.setComponent(dto.getComponent());
            menu.setIcon(dto.getIcon());
            // type pass-through: 0=Directory, 1=Menu, 2=Button (same as DB convention)
            menu.setType(dto.getType() != null ? dto.getType() : 1);
            menu.setParentId(parentId);
            menu.setPermissionCode(dto.getPermissionCode());
            menu.setVisible(dto.getVisible() != null ? dto.getVisible() : true);
            menu.setOrderNo(dto.getOrderNo() != null ? dto.getOrderNo() : 0);
            menu.setI18nKey(dto.getI18nKey());
            menu.setRedirect(dto.getRedirect());
            menu.setPageKey(pageKey);
            menu.setStatus(MenuStatus.ACTIVE);
            menu.setDeletedFlag(false);
            menu.setCreatedAt(Instant.now());
            menu.setUpdatedAt(Instant.now());

            Menu created = menuService.createMenu(menu);

            // Store code → id mapping for child menu parent resolution
            menuCodeToIdMap.put(dto.getCode(), created.getId());

            // Update plugin_pid, page_key and extension
            menuMapper.updatePluginFields(pluginPid, pageKey, extensionJson, created.getId());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MENU,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    @Override
    public PluginResource importProcess(ProcessDefinitionDTO dto, String pluginPid, String importId,
                                         Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                         Boolean autoDeploy) {
        String pid = UlidGenerator.generate();
        boolean exists = checkProcessExists(tenantId, dto.getKey());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Process already exists: " + dto.getKey());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PROCESS,
                    null, null, dto.getKey(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.PROCESS, dto.getKey())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.PROCESS, dto.getKey());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PROCESS,
                    null, null, dto.getKey(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        // Build extension map, including designerJson if present
        Map<String, Object> extension = new HashMap<>();
        if (dto.getDesignerJson() != null) {
            extension.put("designerJson", dto.getDesignerJson());
        }

        // Derive form bindings from designerJson node `data.formPageKey` when the
        // DTO did not declare an explicit top-level `formBindings` map. Plugin
        // authors editing processes.json in designer form carry the user-task ↔
        // page binding inline on each node; without this derivation the
        // BpmProcessDefinition.form_bindings column stays empty and runtime
        // /api/bpm/forms/task/{id} returns a null formBinding even though the
        // page exists. Explicit DTO-level formBindings always win so callers
        // that already supply the richer FormBindingConfig shape are untouched.
        Map<String, Object> derivedFormBindings = null;
        if (dto.getFormBindings() == null || dto.getFormBindings().isEmpty()) {
            derivedFormBindings = deriveFormBindingsFromDesigner(dto);
        }

        // Determine BPMN XML at import time so the DB row always carries a
        // non-empty bpmn_content when we are (auto-)deploying. SmartEngine
        // registration still happens in deployProcessToSmartEngine, but now we
        // share the compiled XML with the persisted entity instead of leaving
        // bpmn_content='' while status='deployed' (runtime then fails with
        // bpm.rule.execution_failed or missing-definition errors).
        Integer initialVersion = 1;
        String initialBpmnContent = compileBpmnContent(dto, initialVersion);

        BpmProcessDefinition process = BpmProcessDefinition.builder()
                .pid(pid)
                .tenantId(tenantId)
                .pluginPid(pluginPid)
                .processKey(dto.getKey())
                .processName(dto.getEffectiveName())
                .description(dto.getDescription())
                .category(dto.getCategory())
                .bpmnContent(initialBpmnContent)
                .extension(extension.isEmpty() ? null : extension)
                .formBindings(dto.getFormBindings() != null && !dto.getFormBindings().isEmpty()
                        ? objectMapper.convertValue(dto.getFormBindings(), new TypeReference<>() {})
                        : (derivedFormBindings != null ? derivedFormBindings : new HashMap<>()))
                .businessDataBindings(dto.getBusinessDataBindings() != null
                        ? Map.of("bindings", dto.getBusinessDataBindings())
                        : new HashMap<>())
                .status(Boolean.TRUE.equals(autoDeploy) ? "deployed" : "draft")
                .version(1)
                .isCurrent(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        if (exists) {
            // Create new version
            processDefinitionMapper.clearCurrentVersion(tenantId, dto.getKey());
            int nextVersion = processDefinitionMapper.getNextVersion(tenantId, dto.getKey());
            process.setVersion(nextVersion);
            // Recompile BPMN with the new version attribute so the persisted
            // XML and the SmartEngine deployment stay in lock-step.
            String recompiled = compileBpmnContent(dto, nextVersion);
            process.setBpmnContent(recompiled);
        }

        processDefinitionMapper.insert(process);

        if (Boolean.TRUE.equals(autoDeploy)) {
            deployProcessToSmartEngine(dto, tenantId, process.getVersion(), process.getBpmnContent());
        }

        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PROCESS,
                pid, process.getId(), dto.getKey(), dto.getEffectiveName(),
                exists ? ResourceAction.UPDATE : ResourceAction.CREATE, null, null);
    }

    /**
     * Compile a {@link ProcessDefinitionDTO}'s designerJson (or pass-through
     * BPMN XML) into a version-stamped BPMN XML string. Returns empty string
     * when neither designerJson nor bpmnContent is provided; callers that need
     * non-empty content should validate upstream.
     */
    @SuppressWarnings("unchecked")
    private String compileBpmnContent(ProcessDefinitionDTO dto, Integer version) {
        Map<String, Object> designerJson = dto.getDesignerJson() != null
                ? objectMapper.convertValue(dto.getDesignerJson(), new TypeReference<Map<String, Object>>() {})
                : null;
        if (designerJson != null && !designerJson.isEmpty()) {
            // Prefer designerJson when both sources are present. It is the editable source of truth
            // and avoids importing stale embedded BPMN XML from older plugin manifests.
            designerJson.putIfAbsent("key", dto.getKey());
            if (dto.getEffectiveName() != null) {
                designerJson.putIfAbsent("name", dto.getEffectiveName());
            }
            try {
                String bpmnXml = jsonToBpmnConverter.convertFromMap(designerJson);
                return stampVersion(bpmnXml, version);
            } catch (Exception e) {
                log.error("Failed to compile BPMN for {}: {}", dto.getKey(), e.getMessage(), e);
                throw new PluginException("Failed to compile BPMN for " + dto.getKey() + ": " + e.getMessage(), e);
            }
        }

        if (dto.getBpmnContent() != null && !dto.getBpmnContent().isBlank()) {
            return stampVersion(dto.getBpmnContent(), version);
        }

        if (designerJson == null || designerJson.isEmpty()) {
            return "";
        }
        return "";
    }

    /**
     * Scan {@code dto.designerJson.nodes} and derive a {@code formBindings}
     * map compatible with {@link com.auraboot.framework.bpm.dto.FormBindingConfig}
     * for every {@code userTask} node that carries {@code data.formPageKey}.
     *
     * <p>Resulting map shape (keyed by node id): <pre>
     *   { "task_manager_approve": { "formType": "PAGE", "formRef": "wd_leave_request_detail" } }
     * </pre>
     *
     * <p>If the referenced page does not exist in {@code ab_page_schema}, we log
     * a warning but still emit the binding — deploy-time / runtime form lookup
     * will surface the missing page with a clearer error. This matches the
     * project's fail-late philosophy for form bindings (page may be imported
     * later in the same transaction).
     *
     * @return a non-empty map with derived bindings, or {@code null} if the
     *         designerJson has no qualifying userTask nodes.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deriveFormBindingsFromDesigner(ProcessDefinitionDTO dto) {
        Map<String, Object> designerJson = dto.getDesignerJson();
        if (designerJson == null || designerJson.isEmpty()) {
            return null;
        }
        Object nodesObj = designerJson.get("nodes");
        if (!(nodesObj instanceof List<?> nodes) || nodes.isEmpty()) {
            return null;
        }
        Map<String, Object> derived = new LinkedHashMap<>();
        for (Object nodeObj : nodes) {
            if (!(nodeObj instanceof Map)) continue;
            Map<String, Object> node = (Map<String, Object>) nodeObj;
            Object type = node.get("type");
            if (!"userTask".equals(type)) continue;
            Object dataObj = node.get("data");
            if (!(dataObj instanceof Map)) continue;
            Map<String, Object> data = (Map<String, Object>) dataObj;
            Object pageKeyObj = data.get("formPageKey");
            if (!(pageKeyObj instanceof String pageKey) || pageKey.isBlank()) continue;
            Object nodeIdObj = node.get("id");
            if (!(nodeIdObj instanceof String nodeId) || nodeId.isBlank()) continue;

            // Verify the page exists; warn (not throw) so a stale reference
            // does not wedge the entire plugin import. Deploy-time runtime
            // will report the missing page through the form probe endpoint.
            try {
                if (pageSchemaMapper.selectAnyByPageKey(pageKey) == null) {
                    log.warn("Process {} node {} declares formPageKey={} but no matching row in ab_page_schema; binding will be emitted anyway",
                            dto.getKey(), nodeId, pageKey);
                }
            } catch (Exception e) {
                log.warn("Failed to verify page_key={} for process {} node {}: {}",
                        pageKey, dto.getKey(), nodeId, e.getMessage());
            }

            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("formType", "PAGE");
            binding.put("formRef", pageKey);
            derived.put(nodeId, binding);
        }
        return derived.isEmpty() ? null : derived;
    }

    private String stampVersion(String bpmnXml, Integer version) {
        if (bpmnXml.contains("version=\"")) {
            return bpmnXml;
        }
        String versionStr = String.valueOf(version);
        return bpmnXml.replaceFirst(
                "(<process\\s+[^>]*)(>)",
                "$1 version=\"" + versionStr + ".0.0\"$2");
    }

    /**
     * Register a pre-compiled BPMN XML with SmartEngine. Idempotent by
     * (tenantId, processKey, version): SmartEngine's deploy is safe to
     * re-invoke. Failures are wrapped in {@link PluginException} so the
     * import transaction rolls back cleanly.
     */
    private void deployProcessToSmartEngine(ProcessDefinitionDTO dto, Long tenantId,
                                             Integer version, String bpmnXml) {
        if (bpmnXml == null || bpmnXml.isBlank()) {
            log.info("Process {} has no BPMN content; skipping SmartEngine deploy", dto.getKey());
            return;
        }
        try {
            // Use tenant-aware deploy so the cache key includes tenantId.
            // Without tenantId the key is processKey:version; but ProcessEngineService.startProcess
            // passes TENANT_ID in variables, causing SmartEngine to look up by
            // processKey:version:tenantId — which would be absent.
            smartEngine.getRepositoryCommandService()
                    .deployWithUTF8Content(bpmnXml, String.valueOf(tenantId));
            log.info("Deployed BPMN process to SmartEngine: tenantId={}, processKey={}, version={}",
                    tenantId, dto.getKey(), version);
        } catch (Exception e) {
            log.error("Failed to deploy BPMN process {} to SmartEngine: {}", dto.getKey(), e.getMessage(), e);
            throw new PluginException("Failed to deploy process " + dto.getKey() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public PluginResource importPage(PageSchemaDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                      Boolean autoPublish) {
        boolean exists = checkPageExists(tenantId, dto.getPageKey());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Page already exists: " + dto.getPageKey());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    null, null, dto.getPageKey(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.PAGE, dto.getPageKey())) {
            // Stub pages emitted by MetaModelServiceImpl.autoCreateDefaultPages are tagged with
            // extension.auto_created=true. Such rows carry no user content (zero columns / buttons /
            // fields) and exist solely so that /api/pages/key/{pageKey} resolves between model
            // publish and plugin import. They must never block plugin DSL overwrite, even if the
            // resource was somehow flagged user-modified earlier. Bypass the skip in that case.
            if (!isAutoCreatedStubPage(dto.getPageKey())) {
                log.info("Skipping user-modified resource: {} {}", ResourceType.PAGE, dto.getPageKey());
                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                        null, null, dto.getPageKey(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
            }
            log.info("Forcing overwrite of auto-created stub page: {}", dto.getPageKey());
        }

        // V2 flat format: kind/profile/title/layout/blocks directly on DTO
        String kind = dto.getKind() != null ? dto.getKind() : "list";
        String profile = dto.getProfile() != null ? dto.getProfile() : "admin";
        String titleJson = dto.getTitle() != null ? toJson(dto.getTitle()) : null;
        String layoutJson = dto.getLayout() != null ? toJson(dto.getLayout()) : null;
        String blocksJson = dto.getBlocks() != null ? toJson(dto.getBlocks()) : "[]";
        String extensionJson = dto.getExtension() != null && !dto.getExtension().isEmpty()
                ? toJson(dto.getExtension()) : "{}";
        String titleDisplay = dto.getEffectiveName();
        boolean isTemplate = dto.getIsTemplate() != null && dto.getIsTemplate();
        int sortWeight = dto.getSortWeight() != null ? dto.getSortWeight() : 0;
        int schemaVersion = 2;

        if (exists) {
            // Update existing page via direct SQL to bypass service-layer name uniqueness validation
            com.auraboot.framework.meta.dto.PageSchemaDTO existingPage = pageSchemaService.findAnyByPageKey(dto.getPageKey());

            pageSchemaMapper.updateForPluginImport(
                titleDisplay, titleJson, dto.getDescription(), kind, profile,
                dto.getModelCode(), layoutJson, blocksJson,
                schemaVersion, isTemplate, dto.getTemplateCategory(), sortWeight,
                extensionJson, pluginPid, existingPage.getPid(), tenantId);

            if (autoPublish != null && autoPublish && !StatusConstants.PUBLISHED.equals(existingPage.getStatus())) {
                pageSchemaMapper.publishByPid(existingPage.getPid());
            }

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    existingPage.getPid(), null, dto.getPageKey(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Create new page via direct SQL to bypass service-layer name uniqueness validation
            String pid = UlidGenerator.generate();
            boolean publish = autoPublish != null && autoPublish;

            // env-layering PoC #16: resolve env_id from MetaContext (HTTP request) or fall back
            // to tenant default (CLI / startup imports without env hint).
            Long envId = com.auraboot.framework.application.tenant.MetaContext.getCurrentEnvironmentId();
            if (envId == null) {
                envId = environmentService.findOrCreateDefaultId(tenantId);
            }

            pageSchemaMapper.insertForPluginImport(
                pid, tenantId, envId, publish ? "published" : "draft",
                dto.getPageKey(), dto.getModelCode(),
                titleDisplay, titleJson, dto.getDescription(), kind, profile,
                layoutJson, blocksJson, schemaVersion,
                isTemplate, dto.getTemplateCategory(),
                publish ? java.time.Instant.now() : null,
                sortWeight, extensionJson, pluginPid);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    pid, null, dto.getPageKey(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    /**
     * Import a dashboard from the first-class {@code config/dashboards/*.json} contract (Plan #8).
     *
     * <p>Conflict strategy is honoured at the dashboard level (by code):
     * <ul>
     *   <li>ERROR       → throw if a dashboard with the same code already exists</li>
     *   <li>SKIP        → return a SKIP record when dashboard already exists</li>
     *   <li>OVERWRITE / OVERWRITE_SAFE → update widgets + layout in place</li>
     * </ul>
     */
    @Override
    public PluginResource importDashboard(DashboardDefinitionDTO dto, String pluginPid, String importId,
                                          Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        log.info("Importing dashboard '{}' from config/dashboards/", dto.getCode());

        if (!dto.isValid()) {
            throw new PluginException(
                    "Invalid dashboard definition '%s': code, title and widgets are required".formatted(dto.getCode()));
        }

        // Build widgets JsonNode from the raw List<Object>
        com.fasterxml.jackson.databind.JsonNode widgetsNode = objectMapper.valueToTree(dto.getWidgets());
        com.fasterxml.jackson.databind.JsonNode layoutConfigNode = buildDashboardLayoutConfig(dto.getLayoutConfig());

        DashboardDTO existing = dashboardService.findByCode(dto.getCode());

        if (existing != null && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Dashboard already exists: " + dto.getCode());
        }

        if (existing != null && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            log.info("Dashboard '{}' already exists — skipping (SKIP strategy)", dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    existing.getPid(), null, dto.getCode(), dto.getTitle(),
                    ResourceAction.SKIP, null, null);
        }

        if (existing != null) {
            // OVERWRITE or OVERWRITE_SAFE — update in place
            DashboardUpdateRequest updateReq = new DashboardUpdateRequest();
            updateReq.setTitle(dto.getTitle());
            updateReq.setDescription(dto.getDescription());
            updateReq.setScope(dto.getEffectiveScope());
            updateReq.setLayoutConfig(layoutConfigNode);
            updateReq.setWidgets(widgetsNode);
            dashboardService.update(existing.getPid(), updateReq);
            log.info("Dashboard updated from config/dashboards/: code={}, pid={}", dto.getCode(), existing.getPid());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    existing.getPid(), null, dto.getCode(), dto.getTitle(),
                    ResourceAction.UPDATE, null, null);
        } else {
            DashboardCreateRequest createReq = new DashboardCreateRequest();
            createReq.setCode(dto.getCode());
            createReq.setTitle(dto.getTitle());
            createReq.setDescription(dto.getDescription());
            createReq.setScope(dto.getEffectiveScope());
            createReq.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
            createReq.setLayoutConfig(layoutConfigNode);
            createReq.setWidgets(widgetsNode);
            DashboardDTO created = dashboardService.create(createReq);
            // Plugin dashboards are published immediately unless status=draft
            if (!"draft".equals(dto.getEffectiveStatus())) {
                dashboardService.publish(created.getPid());
            }
            log.info("Dashboard created from config/dashboards/: code={}, pid={}", dto.getCode(), created.getPid());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    created.getPid(), null, dto.getCode(), dto.getTitle(),
                    ResourceAction.CREATE, null, null);
        }
    }

    /**
     * Build a dashboard layoutConfig JsonNode from a raw map.
     * Applies defaults when the map is null or missing keys.
     */
    private com.fasterxml.jackson.databind.JsonNode buildDashboardLayoutConfig(Map<String, Object> rawLayout) {
        com.fasterxml.jackson.databind.node.ObjectNode cfg = objectMapper.createObjectNode();
        int columns   = rawLayout != null && rawLayout.get("columns")   instanceof Number n ? n.intValue() : 12;
        int rowHeight = rawLayout != null && rawLayout.get("rowHeight") instanceof Number n ? n.intValue() : 100;
        int gap       = rawLayout != null && rawLayout.get("gap")       instanceof Number n ? n.intValue() : 16;
        String compact = rawLayout != null && rawLayout.get("compactType") instanceof String s ? s : "vertical";
        cfg.put("columns",     columns);
        cfg.put("rowHeight",   rowHeight);
        cfg.put("gap",         gap);
        cfg.put("compactType", compact);
        return cfg;
    }

    @Override
    public PluginResource importDict(DictDefinitionDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        boolean exists = checkDictExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Dictionary already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.DICT, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.DICT, dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing dict
            DictDTO existingDict = dictService.findByCode(dto.getCode());

            DictUpdateRequest updateRequest = new DictUpdateRequest();
            updateRequest.setName(dto.getEffectiveName());
            updateRequest.setDescription(dto.getDescription());
            updateRequest.setDictType(dto.getDictType());

            DictDTO updated = dictService.update(existingDict.getPid(), updateRequest);

            // Update dict items using new replaceItems service method
            if (dto.getItems() != null && !dto.getItems().isEmpty()) {
                List<DictCreateRequest.DictItemCreateRequest> itemRequests = dto.getItems().stream()
                    .map(item -> {
                        DictCreateRequest.DictItemCreateRequest itemReq = new DictCreateRequest.DictItemCreateRequest();
                        itemReq.setValue(item.getValue());
                        itemReq.setLabel(item.getEffectiveLabel());
                        itemReq.setSortOrder(item.getSortNo());
                        itemReq.setParentValue(item.getParentValue());
                        itemReq.setDisabled(StatusConstants.DISABLED.equals(item.getStatus()));
                        if (item.getExtra() != null) {
                            itemReq.setExtension(objectMapper.convertValue(item.getExtra(), JsonNode.class));
                        }
                        return itemReq;
                    })
                    .toList();
                dictService.replacePluginItems(updated.getPid(), itemRequests);
            }

            // Update plugin_pid
            dictMapper.updatePluginPidByPid(pluginPid, updated.getPid());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                    updated.getPid(), updated.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Check for soft-deleted record and resurrect if found
            String resurrectPid = resurrectSoftDeleted("ab_dict", "code", dto.getCode(), tenantId, pluginPid, null);
            if (resurrectPid != null) {
                log.info("Resurrected soft-deleted dict: code={}, pid={}", dto.getCode(), resurrectPid);
                // Now update the resurrected dict with current data
                DictDTO resurrected = dictService.findByCode(dto.getCode());
                if (resurrected != null && dto.getItems() != null && !dto.getItems().isEmpty()) {
                    List<DictCreateRequest.DictItemCreateRequest> itemRequests = dto.getItems().stream()
                        .map(item -> {
                            DictCreateRequest.DictItemCreateRequest itemReq = new DictCreateRequest.DictItemCreateRequest();
                            itemReq.setValue(item.getValue());
                            itemReq.setLabel(item.getEffectiveLabel());
                            itemReq.setSortOrder(item.getSortNo());
                            itemReq.setParentValue(item.getParentValue());
                            itemReq.setDisabled(StatusConstants.DISABLED.equals(item.getStatus()));
                            if (item.getExtra() != null) {
                                itemReq.setExtension(objectMapper.convertValue(item.getExtra(), JsonNode.class));
                            }
                            return itemReq;
                        })
                        .toList();
                    dictService.replaceItems(resurrected.getPid(), itemRequests);
                }
                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                        resurrectPid, null, dto.getCode(), dto.getEffectiveName(),
                        ResourceAction.CREATE, null, null);
            }

            // Create new dict via service with pluginPid
            DictCreateRequest request = new DictCreateRequest();
            request.setCode(dto.getCode());
            request.setName(dto.getEffectiveName());
            request.setDescription(dto.getDescription());
            request.setDictType(dto.getDictType() != null ? dto.getDictType() : "simple");
            request.setSourceType("static");
            request.setEnabled(true);
            request.setPluginPid(pluginPid);  // Set plugin_pid via request

            // Convert items
            if (dto.getItems() != null) {
                request.setItems(dto.getItems().stream()
                    .map(item -> {
                        DictCreateRequest.DictItemCreateRequest itemReq = new DictCreateRequest.DictItemCreateRequest();
                        itemReq.setValue(item.getValue());
                        itemReq.setLabel(item.getEffectiveLabel());
                        itemReq.setSortOrder(item.getSortNo());
                        itemReq.setParentValue(item.getParentValue());
                        itemReq.setDisabled(StatusConstants.DISABLED.equals(item.getStatus()));
                        if (item.getExtra() != null) {
                            itemReq.setExtension(objectMapper.convertValue(item.getExtra(), JsonNode.class));
                        }
                        return itemReq;
                    })
                    .toList());
            }

            DictDTO created = dictService.create(request);
            // Mark all items as PLUGIN-sourced for source-aware reimport
            dictService.markItemsAsPluginSource(created.getPid());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    @Override
    public PluginResource importNamedQuery(NamedQueryDefinitionDTO dto, String pluginPid, String importId,
                                           Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        boolean exists = checkNamedQueryExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Named query already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.NAMED_QUERY,
                    null, null, dto.getCode(), dto.getEffectiveTitle(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.NAMED_QUERY, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.NAMED_QUERY, dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.NAMED_QUERY,
                    null, null, dto.getCode(), dto.getEffectiveTitle(), ResourceAction.SKIP, null, null);
        }

        Map<String, Object> currentState = buildNamedQueryState(dto);
        List<NamedQueryFieldRequest> clonedFields = cloneNamedQueryFields(dto.getFields(), dto.getCode());

        if (exists) {
            // Use mapper directly to bypass Spring cache — stale cache can return a PID that no
            // longer exists in the DB (e.g. after uninstall/reinstall in test environments).
            NamedQuery existingEntity = namedQueryMapper.findByCode(dto.getCode());
            if (existingEntity == null) {
                // Cache was stale — the NQ was deleted; fall through to CREATE.
                exists = false;
            } else {
            String targetStatus = dto.getStatus() != null ? dto.getStatus() : existingEntity.getStatus();

            // Ensure query is editable before updating SQL/fields.
            ensureNamedQueryEditable(existingEntity.getPid());

            NamedQueryUpdateRequest updateRequest = new NamedQueryUpdateRequest();
            updateRequest.setTitle(dto.getEffectiveTitle());
            updateRequest.setDescription(dto.getDescription());
            updateRequest.setFromSql(dto.getFromSql());
            updateRequest.setBaseWhere(dto.getBaseWhere());
            updateRequest.setDefaultOrder(dto.getDefaultOrder());
            updateRequest.setTags(dto.getTags());
            updateRequest.setMetadata(dto.getMetadata());
            updateRequest.setPolicy(dto.getPolicy());
            updateRequest.setValidateSql(dto.getValidateSql() == null || dto.getValidateSql());
            updateRequest.setCheckPermissions(dto.getCheckPermissions() == null || dto.getCheckPermissions());
            namedQueryService.update(existingEntity.getPid(), updateRequest);

            if (clonedFields != null && !clonedFields.isEmpty()) {
                NamedQueryFieldBatchRequest fieldBatchRequest = new NamedQueryFieldBatchRequest();
                fieldBatchRequest.setOperationType("set");
                fieldBatchRequest.setClearExisting(true);
                fieldBatchRequest.setSource("plugin");
                fieldBatchRequest.setValidateFields(true);
                fieldBatchRequest.setSkipDuplicates(false);
                fieldBatchRequest.setFields(clonedFields);
                namedQueryService.batchSaveFields(dto.getCode(), fieldBatchRequest);
            }

            transitionNamedQueryStatus(existingEntity.getPid(), targetStatus);
            NamedQuery updated = namedQueryMapper.findByCode(dto.getCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.NAMED_QUERY,
                    updated.getPid(), updated.getId(), dto.getCode(), dto.getEffectiveTitle(),
                    ResourceAction.UPDATE, null, currentState);
            }
        }

        NamedQueryCreateRequest createRequest = new NamedQueryCreateRequest();
        createRequest.setCode(dto.getCode());
        createRequest.setTitle(dto.getEffectiveTitle());
        createRequest.setDescription(dto.getDescription());
        createRequest.setFromSql(dto.getFromSql());
        createRequest.setBaseWhere(dto.getBaseWhere());
        createRequest.setDefaultOrder(dto.getDefaultOrder());
        createRequest.setStatus(normalizeNamedQueryStatus(dto.getStatus()));
        createRequest.setFields(clonedFields);
        createRequest.setTags(dto.getTags());
        createRequest.setMetadata(dto.getMetadata());
        createRequest.setPolicy(dto.getPolicy());
        createRequest.setValidateSql(dto.getValidateSql() == null || dto.getValidateSql());
        createRequest.setCheckPermissions(dto.getCheckPermissions() == null || dto.getCheckPermissions());

        NamedQueryDTO created = namedQueryService.create(createRequest);
        // Mark all fields as PLUGIN-sourced for source-aware reimport
        namedQueryService.markFieldsAsPluginSource(dto.getCode());
        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.NAMED_QUERY,
                created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveTitle(),
                ResourceAction.CREATE, null, currentState);
    }

    private Map<String, Object> buildNamedQueryState(NamedQueryDefinitionDTO dto) {
        Map<String, Object> state = new HashMap<>();
        state.put("code", dto.getCode());
        state.put("title", dto.getEffectiveTitle());
        state.put("description", dto.getDescription());
        state.put("fromSql", dto.getFromSql());
        state.put("status", normalizeNamedQueryStatus(dto.getStatus()));
        if (dto.getBaseWhere() != null) {
            state.put("baseWhere", dto.getBaseWhere());
        }
        if (dto.getDefaultOrder() != null) {
            state.put("defaultOrder", dto.getDefaultOrder());
        }
        if (dto.getFields() != null) {
            state.put("fields", dto.getFields());
        }
        if (dto.getTags() != null) {
            state.put("tags", dto.getTags());
        }
        if (dto.getMetadata() != null) {
            state.put("metadata", dto.getMetadata());
        }
        if (dto.getPolicy() != null) {
            state.put("policy", dto.getPolicy());
        }
        return state;
    }

    private List<NamedQueryFieldRequest> cloneNamedQueryFields(List<NamedQueryFieldRequest> fields, String queryCode) {
        if (fields == null) {
            return null;
        }
        List<NamedQueryFieldRequest> cloned = objectMapper.convertValue(fields,
                new TypeReference<List<NamedQueryFieldRequest>>() {});
        // Validate required properties instead of auto-filling defaults
        for (NamedQueryFieldRequest field : cloned) {
            String fieldCode = field.getFieldCode();
            if (fieldCode == null || fieldCode.isBlank()) {
                throw new PluginException("Named query '" + queryCode + "': field is missing 'code'/'fieldCode'");
            }
            if (field.getColumnExpr() == null || field.getColumnExpr().isBlank()) {
                throw new PluginException("Named query '" + queryCode + "', field '" + fieldCode
                        + "': missing 'columnExpr'. Add \"columnExpr\": \"" + fieldCode + "\" if it matches the SQL alias");
            }
            if (field.getDataType() == null || field.getDataType().isBlank()) {
                throw new PluginException("Named query '" + queryCode + "', field '" + fieldCode
                        + "': missing 'dataType'. Must be one of: string, number, date, boolean, json, array");
            }
        }
        return cloned;
    }

    private String normalizeNamedQueryStatus(String status) {
        if (status == null || status.isBlank()) {
            return "draft";
        }
        if ("enabled".equalsIgnoreCase(status)) {
            return "published";
        }
        if ("disabled".equalsIgnoreCase(status)) {
            return "archived";
        }
        return status.toLowerCase();
    }

    private boolean isNamedQueryEditableStatus(String status) {
        String normalized = normalizeNamedQueryStatus(status);
        return StatusConstants.DRAFT.equals(normalized) || StatusConstants.TESTING.equals(normalized);
    }

    private void ensureNamedQueryEditable(String queryPid) {
        NamedQueryDTO current = namedQueryService.findByPid(queryPid);
        if (!isNamedQueryEditableStatus(current.getStatus())) {
            transitionNamedQueryStatus(queryPid, "draft");
        }
    }

    private void transitionNamedQueryStatus(String queryPid, String targetStatus) {
        String target = normalizeNamedQueryStatus(targetStatus);
        NamedQueryDTO current = namedQueryService.findByPid(queryPid);
        String currentStatus = normalizeNamedQueryStatus(current.getStatus());

        if (currentStatus.equals(target)) {
            return;
        }

        // First, normalize to DRAFT to guarantee deterministic transitions.
        int guard = 0;
        while (!StatusConstants.DRAFT.equals(currentStatus) && guard++ < 8) {
            switch (currentStatus) {
                case "testing" -> namedQueryService.updateStatus(queryPid, "draft");
                case "published" -> namedQueryService.updateStatus(queryPid, "deprecated");
                case "deprecated" -> namedQueryService.updateStatus(queryPid, "archived");
                case "archived" -> namedQueryService.updateStatus(queryPid, "draft");
                default -> throw new PluginException("Unsupported named query status: " + currentStatus);
            }
            currentStatus = normalizeNamedQueryStatus(namedQueryService.findByPid(queryPid).getStatus());
        }

        if (guard >= 8 && !StatusConstants.DRAFT.equals(currentStatus)) {
            throw new PluginException("Failed to normalize named query status to DRAFT: " + queryPid);
        }

        // Transition from DRAFT to target.
        if (StatusConstants.DRAFT.equals(target)) {
            return;
        }
        if (StatusConstants.ARCHIVED.equals(target)) {
            namedQueryService.updateStatus(queryPid, "archived");
            return;
        }
        if (StatusConstants.TESTING.equals(target)) {
            namedQueryService.updateStatus(queryPid, "testing");
            return;
        }
        if (StatusConstants.PUBLISHED.equals(target)) {
            namedQueryService.updateStatus(queryPid, "testing");
            namedQueryService.updateStatus(queryPid, "published");
            return;
        }
        if (StatusConstants.DEPRECATED.equals(target)) {
            namedQueryService.updateStatus(queryPid, "testing");
            namedQueryService.updateStatus(queryPid, "published");
            namedQueryService.updateStatus(queryPid, "deprecated");
            return;
        }

        throw new PluginException("Unsupported target named query status: " + target);
    }

    @Override
    public PluginResource importAgentDefinition(AgentDefinitionDTO dto, String pluginPid, String importId,
                                                Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        AgentDefinition existing = findActiveAgentDefinition(tenantId, dto.getAgentCode());
        boolean exists = existing != null;

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Agent definition already exists: " + dto.getAgentCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.AGENT_DEFINITION,
                    null, null, dto.getAgentCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.AGENT_DEFINITION, dto.getAgentCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.AGENT_DEFINITION, dto.getAgentCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.AGENT_DEFINITION,
                    null, null, dto.getAgentCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        Map<String, Object> currentState = buildAgentDefinitionState(dto);
        Instant now = Instant.now();

        if (exists) {
            applyAgentDefinition(existing, dto);
            existing.setUpdatedAt(now);
            existing.setDeletedFlag(false);
            agentDefinitionMapper.updateById(existing);

            AgentDefinition updated = findActiveAgentDefinition(tenantId, dto.getAgentCode());
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.AGENT_DEFINITION,
                    updated.getPid(), updated.getId(), dto.getAgentCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, currentState);
        }

        AgentDefinition created = new AgentDefinition();
        created.setPid(UlidGenerator.generate());
        created.setTenantId(tenantId);
        applyAgentDefinition(created, dto);
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        created.setDeletedFlag(false);
        agentDefinitionMapper.insert(created);

        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.AGENT_DEFINITION,
                created.getPid(), created.getId(), dto.getAgentCode(), dto.getEffectiveName(),
                ResourceAction.CREATE, null, currentState);
    }

    private AgentDefinition findActiveAgentDefinition(Long tenantId, String agentCode) {
        if (tenantId == null || agentCode == null || agentCode.isBlank()) {
            return null;
        }
        return agentDefinitionMapper.selectOne(new QueryWrapper<AgentDefinition>()
                .eq("tenant_id", tenantId)
                .eq("agent_code", agentCode)
                .apply("(deleted_flag = FALSE OR deleted_flag IS NULL)")
                .last("LIMIT 1"));
    }

    private void applyAgentDefinition(AgentDefinition target, AgentDefinitionDTO dto) {
        target.setAgentCode(dto.getAgentCode());
        target.setName(dto.getEffectiveName());
        target.setDescription(dto.getDescription());
        target.setAvatarUrl(dto.getAvatarUrl());
        target.setAgentType(defaultString(dto.getAgentType(), "reactive"));
        target.setModel(defaultString(dto.getModel(), "claude-sonnet-4-6"));
        target.setSystemPrompt(dto.getSystemPrompt());
        target.setTools(toJsonText(dto.getTools()));
        target.setSkills(toJsonText(dto.getSkills()));
        target.setGuardrails(toJsonText(dto.getGuardrails()));
        target.setSoulProfile(dto.getSoulProfile());
        target.setPersonality(dto.getPersonality());
        target.setExpertise(dto.getExpertise());
        target.setCommunicationStyle(dto.getCommunicationStyle());
        target.setBoundaries(dto.getBoundaries());
        target.setSoulGoals(dto.getSoulGoals());
        target.setAllowedModels(dto.getAllowedModels());
        target.setAllowedOperations(dto.getAllowedOperations() != null
                ? dto.getAllowedOperations()
                : List.of("query", "create", "update", "delete", "transition"));
        target.setMaxTools(dto.getMaxTools() != null ? dto.getMaxTools() : 20);
        target.setMaxConcurrentRuns(dto.getMaxConcurrentRuns() != null ? dto.getMaxConcurrentRuns() : 3);
        target.setExecutionTimeoutSeconds(dto.getExecutionTimeoutSeconds() != null
                ? dto.getExecutionTimeoutSeconds()
                : 300);
        target.setEventTriggers(dto.getEventTriggers());
        target.setAutoReplyMode(defaultString(dto.getAutoReplyMode(), "mention"));
        target.setStatus(defaultString(dto.getStatus(), "active"));
        target.setStats(dto.getStats());
        target.setVisibility(defaultString(dto.getVisibility(), "private"));
    }

    private Map<String, Object> buildAgentDefinitionState(AgentDefinitionDTO dto) {
        Map<String, Object> state = new HashMap<>();
        state.put("agentCode", dto.getAgentCode());
        state.put("name", dto.getEffectiveName());
        state.put("description", dto.getDescription());
        state.put("agentType", defaultString(dto.getAgentType(), "reactive"));
        state.put("model", defaultString(dto.getModel(), "claude-sonnet-4-6"));
        state.put("systemPrompt", dto.getSystemPrompt());
        state.put("tools", dto.getTools());
        state.put("skills", dto.getSkills());
        state.put("guardrails", dto.getGuardrails());
        state.put("soulProfile", dto.getSoulProfile());
        state.put("allowedModels", dto.getAllowedModels());
        state.put("allowedOperations", dto.getAllowedOperations());
        state.put("maxTools", dto.getMaxTools());
        state.put("maxConcurrentRuns", dto.getMaxConcurrentRuns());
        state.put("executionTimeoutSeconds", dto.getExecutionTimeoutSeconds());
        state.put("eventTriggers", dto.getEventTriggers());
        state.put("autoReplyMode", defaultString(dto.getAutoReplyMode(), "mention"));
        state.put("status", defaultString(dto.getStatus(), "active"));
        state.put("visibility", defaultString(dto.getVisibility(), "private"));
        return state;
    }

    private String toJsonText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new PluginException("Failed to serialize agent definition field: " + e.getMessage(), e);
        }
    }

    private String defaultString(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    // ==================== Rollback Operations ====================

    /**
     * Rollback handlers below run during plugin un-install or import-failure compensation.
     *
     * The {@code catch (Exception)} branches here are intentional:
     *   1. They are best-effort cleanups; a single failed delete must not block
     *      subsequent rollback steps for other resource types.
     *   2. They fall back to a direct mapper/jdbc archive call when the service
     *      method fails — the resource still ends up in a tombstoned state.
     *   3. {@code log.warn} carries the message + cause via SLF4J's {@code {}}
     *      placeholder, so trace context is preserved for incident analysis.
     *
     * Per docs/agent-rules/review-baseline.md §1, this is the "non-transactional
     * cleanup" exception to the no-catch-Exception rule.
     */
    @Override
    public void rollbackResource(PluginResource resource) {
        ResourceType type = resource.getResourceTypeEnum();

        switch (type) {
            case MODEL -> {
                if (resource.getResourcePid() != null) {
                    try {
                        metaModelService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for model {}: {}", resource.getResourcePid(), e.getMessage());
                        metaModelMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case FIELD -> {
                if (resource.getResourcePid() != null) {
                    try {
                        metaFieldService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for field {}: {}", resource.getResourcePid(), e.getMessage());
                        metaFieldMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case COMMAND -> {
                if (resource.getResourcePid() != null) {
                    try {
                        commandService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for command {}: {}", resource.getResourcePid(), e.getMessage());
                        commandDefinitionMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case PERMISSION -> {
                if (resource.getResourceId() != null) {
                    try {
                        permissionService.delete(resource.getResourceId());
                    } catch (Exception e) {
                        log.warn("Service delete failed for permission {}: {}", resource.getResourceId(), e.getMessage());
                        permissionMapper.softDelete(resource.getResourceId());
                    }
                }
            }
            case ROLE -> {
                try {
                    roleMapper.softDeleteByPid(resource.getResourcePid());
                } catch (Exception e) {
                    log.warn("Failed to delete role {}: {}", resource.getResourcePid(), e.getMessage());
                }
            }
            case MENU -> {
                if (resource.getResourceId() != null) {
                    try {
                        menuService.deleteMenu(resource.getResourceId());
                    } catch (Exception e) {
                        log.warn("Service delete failed for menu {}: {}", resource.getResourceId(), e.getMessage());
                        menuMapper.softDeleteById(resource.getResourceId());
                    }
                }
            }
            case PAGE -> {
                if (resource.getResourcePid() != null) {
                    try {
                        pageSchemaService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for page {}: {}", resource.getResourcePid(), e.getMessage());
                        pageSchemaMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case DICT -> {
                if (resource.getResourcePid() != null) {
                    try {
                        dictService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for dict {}, using mapper fallback: {}",
                                resource.getResourcePid(), e.getMessage());
                        dictMapper.softDeleteByPid(resource.getResourcePid());
                    }
                }
            }
            case NAMED_QUERY -> {
                if (resource.getResourcePid() != null) {
                    try {
                        namedQueryService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Failed to delete named query {}: {}", resource.getResourcePid(), e.getMessage());
                        namedQueryMapper.updateStatusByPid(resource.getResourcePid(), "archived");
                    }
                }
            }
            case AGENT_DEFINITION -> {
                if (resource.getResourcePid() != null) {
                    jdbcTemplate.update("""
                            UPDATE ab_agent_definition
                            SET deleted_flag = TRUE, updated_at = NOW()
                            WHERE pid = ?
                            """, resource.getResourcePid());
                }
            }
            case PROCESS -> processDefinitionMapper.updateStatus(resource.getResourcePid(), "archived");
            default -> log.warn("Rollback not implemented for resource type: {}", type);
        }
    }

    @Override
    public void restoreResource(PluginResource resource) {
        if (resource.getPreviousState() == null) {
            return;
        }
        log.info("Restoring resource: {} ({})", resource.getResourceCode(), resource.getResourceType());
    }

    // ==================== Helper Methods ====================

    private PluginResource createResourceRecord(String pluginPid, String importId, Long tenantId,
                                                ResourceType type, String resourcePid, Long resourceId,
                                                String code, String name, ResourceAction action,
                                                Map<String, Object> previousState, Map<String, Object> currentState) {
        return createResourceRecord(pluginPid, importId, tenantId, type, resourcePid, resourceId,
                code, name, action, previousState, currentState, null);
    }

    private PluginResource createResourceRecord(String pluginPid, String importId, Long tenantId,
                                                ResourceType type, String resourcePid, Long resourceId,
                                                String code, String name, ResourceAction action,
                                                Map<String, Object> previousState, Map<String, Object> currentState,
                                                String pluginVersion) {
        OwnershipType ownership = type.getDefaultOwnership();
        if (action == ResourceAction.SKIP) {
            ownership = null;
        }

        return PluginResource.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .pluginPid(pluginPid)
                .importId(importId)
                .resourceType(type.code())
                .resourcePid(resourcePid)
                .resourceId(resourceId)
                .resourceCode(code)
                .resourceName(name)
                .action(action.code())
                .previousState(previousState)
                .currentState(currentState)
                .ownershipType(ownership != null ? ownership.code() : OwnershipType.SHARED.code())
                .importSnapshot(currentState)
                .userModified(false)
                .lastSyncVersion(pluginVersion)
                .sequence(type.getImportOrder())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new PluginException("Failed to serialize to JSON: " + e.getMessage(), e);
        }
    }

    // convertMenuType removed: menus.json now uses DB convention directly (0=Directory, 1=Menu, 2=Button)

    /**
     * Check for a soft-deleted record and resurrect it by clearing deleted_flag.
     * Returns the pid of the resurrected record, or null if no soft-deleted record exists.
     */
    private String resurrectSoftDeleted(String tableName, String codeColumn, String codeValue,
                                         Long tenantId, String pluginPid, Map<String, Object> extension) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                String.format("SELECT pid FROM %s WHERE %s = ? AND tenant_id = ? AND deleted_flag = TRUE LIMIT 1",
                        tableName, codeColumn),
                codeValue, tenantId);

        if (rows.isEmpty()) {
            return null;
        }

        String pid = (String) rows.get(0).get("pid");

        // Resurrect: clear deleted_flag and update plugin_pid
        if (extension != null && !extension.isEmpty()) {
            Map<String, Object> wrappedExtension = new HashMap<>();
            wrappedExtension.put("extension", extension);
            String extensionJson = toJson(wrappedExtension);
            jdbcTemplate.update(
                    String.format("UPDATE %s SET deleted_flag = FALSE, plugin_pid = ?, extension = ?::jsonb, updated_at = NOW() WHERE pid = ?",
                            tableName),
                    pluginPid, extensionJson, pid);
        } else {
            jdbcTemplate.update(
                    String.format("UPDATE %s SET deleted_flag = FALSE, plugin_pid = ?, updated_at = NOW() WHERE pid = ?",
                            tableName),
                    pluginPid, pid);
        }

        return pid;
    }
}

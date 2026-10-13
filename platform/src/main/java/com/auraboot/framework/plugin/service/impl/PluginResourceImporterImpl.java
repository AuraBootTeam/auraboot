package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.entity.AgentEvalCase;
import com.auraboot.framework.agent.eval.EvalCaseStructureValidator;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.mapper.AgentEvalCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.menu.constant.MenuStatus;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
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
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
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
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.RolePermissionService;
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
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.RoleDataScopeDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.RolePermissionPolicyDefinitionDTO;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.common.constant.StatusConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final DataScopeService dataScopeService;
    private final RolePermissionService rolePermissionService;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final MenuService menuService;
    private final PageSchemaService pageSchemaService;
    private final NamedQueryService namedQueryService;
    private final DashboardService dashboardService;
    // env-layering PoC #16: resolve target env_id for plugin-imported page rows

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
    private final com.auraboot.framework.environment.service.EnvironmentService environmentService;

    // Infrastructure dependencies
    // LEGITIMATE: JdbcTemplate kept only for resurrectSoftDeleted() which uses dynamic table names
    private final JdbcTemplate jdbcTemplate;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<DecisionUsageIndexService> usageIndexServiceProvider;

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
    private final AgentEvalCaseMapper agentEvalCaseMapper;
    // Provider-plugin resolution for fail-closed role permission errors (model owner naming).
    private final PluginRecordMapper pluginRecordMapper;

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

    // Optional dependency - may not be configured in all environments
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CacheManager cacheManager;

    // ==================== Existence Checks ====================

    @Override
    public boolean checkModelExists(Long tenantId, String code) {
        // Deliberately a DB-current probe, not the runtime definition lookup: the
        // definition cache and the application-release catalog can both report a
        // model this import cannot see or load (a draft row cached by a
        // rolled-back import attempt, a catalog-only model), which deadlocked
        // importModel into "Model exists but cannot be loaded for update" on the
        // retry. The probe must resolve exactly what the update branch resolves.
        return metaModelMapper.findCurrentByCodeForTenant(tenantId, code) != null;
    }

    @Override
    public boolean checkFieldExists(Long tenantId, String code) {
        // Same DB-current contract as checkModelExists; updateFieldForReimport
        // resolves the existing row through this very query.
        return metaFieldMapper.findCurrentByCode(code) != null;
    }

    @Override
    public boolean checkCommandExists(Long tenantId, String code) {
        try {
            return commandService.findByCode(code) != null;
        } catch (com.auraboot.framework.meta.exception.CommandNotFoundException absent) {
            return false;
        }
    }

    @Override
    public boolean checkPermissionExists(Long tenantId, String code) {
        try {
            PermissionDTO perm = permissionService.findByCode(code);
            return perm != null;
        } catch (Exception e) {
            log.debug("checkPermissionExists treating exception as 'not exists' for code={}: {}",
                    logSafe(code), logSafe(e.toString()));
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
            log.debug("checkNamedQueryExists treating exception as 'not exists' for code={}: {}",
                    logSafe(code), logSafe(e.toString()));
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
            log.debug("Failed to check user-modified status for {} {}: {}",
                    type, logSafe(resourceCode), logSafe(e.getMessage()));
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
            log.debug("isAutoCreatedStubPage lookup failed for pageKey={}: {}",
                    logSafe(pageKey), logSafe(e.getMessage()));
            return false;
        }
    }

    // ==================== Import Operations ====================

    @Override
    public PluginResource importModel(ModelDefinitionDTO dto, String pluginPid, String importId,
                                       Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                       Boolean autoPublish) {
        registerModelCacheRollbackEviction(dto.getCode());
        boolean exists = checkModelExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Model already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.MODEL, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.MODEL, logSafe(dto.getCode()));
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
        extension.put("immutable", Boolean.TRUE.equals(dto.getImmutable()));
        extension.put("commandOnlyCreate", Boolean.TRUE.equals(dto.getCommandOnlyCreate()));
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
            if (existingModel == null) {
                throw new PluginException("Model exists but cannot be loaded for update: " + dto.getCode());
            }

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
                log.info("Resurrected soft-deleted model: code={}, pid={}",
                        logSafe(dto.getCode()), logSafe(resurrectPid));
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

    /**
     * The meta definition cache is a read-through Spring cache: an entry loaded
     * while the import transaction is still open survives that transaction's
     * rollback, leaving a phantom definition for a model the database no longer
     * has (misleading retries, and any runtime reader between rollback and the
     * next eviction). Evict on rollback so the cache converges with the
     * database; commits keep the entry, which is then accurate.
     */
    void registerModelCacheRollbackEviction(String modelCode) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    log.warn("Import rolled back; evicting cached definitions of model {}",
                            logSafe(modelCode));
                    metaModelService.clearAllCache();
                }
            }
        });
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
            log.info("Skipping user-modified resource: {} {}", ResourceType.FIELD, logSafe(dto.getCode()));
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
                log.info("Resurrected soft-deleted field: code={}, pid={}",
                        logSafe(dto.getCode()), logSafe(resurrectPid));
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
                    log.info("Auto-published field after import: {}", logSafe(dto.getCode()));
                } catch (Exception e) {
                    // Auto-publish is best-effort: import succeeded, the field exists in
                    // draft state, an admin can publish manually later. Don't fail the
                    // whole import for a publish hiccup.
                    log.warn("Failed to auto-publish field {}: {}", logSafe(dto.getCode()), logSafe(e.getMessage()), e);
                }
            }
        }

        // Bind dictionary if dictCode is specified
        if (dto.getDictCode() != null && !dto.getDictCode().isBlank()) {
            MetaFieldDTO field = metaFieldService.findCurrentByCode(dto.getCode()).orElse(null);
            if (field != null) {
                metaFieldService.bindDictionary(field.getPid(), dto.getDictCode());
                log.info("Bound dictionary to field: fieldCode={}, dictCode={}",
                        logSafe(dto.getCode()), logSafe(dto.getDictCode()));
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
        var feature = buildFieldFeature(dto);
        var refTarget = resolveFieldRefTarget(dto);
        boolean definitionChanged = !Objects.equals(existingField.getDataType(), dto.getDataType())
                || !Objects.equals(existingField.getFeature(), objectMapper.convertValue(feature, FieldFeatureBean.class))
                || !Objects.equals(existingField.getRefTarget(), objectMapper.convertValue(refTarget, FieldRefTargetBean.class))
                || !Objects.equals(fieldExtensionValues(existingField.getExtension()), fieldExtensionValues(extensionBean));

        // 3. Update in place via Mapper (no version creation, no validation)
        int updated = metaFieldMapper.updateFieldInPlace(
            existingField.getPid(),
            dto.getDataType(),
            feature,
            refTarget,
            extensionBean,
            pluginPid
        );

        if (updated == 0) {
            throw new PluginException("Failed to update field: " + dto.getCode());
        }

        // 4. Evict cache
        evictFieldCache(existingField.getPid());
        metaModelService.clearAllCache();

        if (definitionChanged) {
            syncPublishedModelsForUpdatedField(existingField, dto.getCode());
        }

        log.info("Field updated in place for plugin reimport: code={}, pid={}",
                 logSafe(dto.getCode()), logSafe(existingField.getPid()));

        // 5. Return resource record
        return createResourceRecord(pluginPid, importId, tenantId, ResourceType.FIELD,
                existingField.getPid(), null, dto.getCode(), dto.getEffectiveDisplayName(),
                ResourceAction.UPDATE, null, extension);
    }

    private Map<String, Object> fieldExtensionValues(ExtensionBean extension) {
        if (extension == null) {
            return Map.of();
        }
        // Both persisted JSON shapes expose the same values; nested values take precedence.
        Map<String, Object> values = new LinkedHashMap<>(extension.getDynamicProperties());
        if (extension.getExtension() != null) {
            values.putAll(extension.getExtension());
        }
        return values;
    }

    private void syncPublishedModelsForUpdatedField(Field existingField, String fieldCode) {
        if (existingField.getId() == null) {
            log.warn("Skipping schema sync for reimported field without database id");
            return;
        }

        List<String> modelCodes = fieldBindingMapper.findPublishedModelCodesByFieldId(existingField.getId());
        if (modelCodes == null || modelCodes.isEmpty()) {
            return;
        }

        for (String modelCode : modelCodes) {
            SchemaOperationResult schemaResult = schemaManagementService.updateTableByModel(modelCode);
            if (schemaResult == null || !schemaResult.isSuccess()) {
                String errorMessage = schemaResult != null && schemaResult.getErrorMessage() != null
                        ? schemaResult.getErrorMessage()
                        : "unknown schema sync error";
                throw new PluginException("Failed to sync schema after updating field " + fieldCode
                        + " for model " + modelCode + ": " + errorMessage);
            }
            log.info("Synced published model after field reimport");
        }
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
        if (dto.getColumnName() != null && !dto.getColumnName().isBlank()) {
            extension.put("columnName", dto.getColumnName());
        }
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
            if (dto.getConstraints().getPrecision() != null) constraints.put("precision", dto.getConstraints().getPrecision());
            if (dto.getConstraints().getScale() != null) constraints.put("scale", dto.getConstraints().getScale());
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
        if (dto.getImmutable() != null) {
            extension.put("immutable", dto.getImmutable());
        }
        if (dto.getImmutableWhen() != null) {
            Map<String, Object> immutableWhen = new LinkedHashMap<>();
            immutableWhen.put("field", dto.getImmutableWhen().getField());
            immutableWhen.put("in", dto.getImmutableWhen().getIn());
            extension.put("immutableWhen", immutableWhen);
        }
        if (dto.getAllowedWriterCommands() != null) {
            extension.put("allowedWriterCommands", List.copyOf(dto.getAllowedWriterCommands()));
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

    /**
     * Normalize a reference field's target config to ONE canonical shape, collapsing the four
     * historical writing styles (top-level {@code referenceModelCode}, {@code refTarget.modelCode},
     * {@code refTarget.targetModel}, {@code refTarget.targetEntity}) into the canonical
     * {@code refTarget.targetEntity}. The persisted {@code ref_target} therefore always uses
     * {@code targetEntity}; the alias keys ({@code modelCode}/{@code targetModel}) are dropped so the
     * runtime has a single shape to read. Other refTarget keys (valueField, importMatchFields,
     * relationship, filter, …) are preserved. C1: import is the single normalization point;
     * runtime reads canonical only.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveFieldRefTarget(FieldDefinitionDTO dto) {
        // Source refTarget may be declared top-level (dto.refTarget) OR nested under extension.refTarget
        // (the shape many plugin field JSON files use). Prefer the explicit top-level one.
        Map<String, Object> source = new LinkedHashMap<>();
        if (dto.getRefTarget() != null && !dto.getRefTarget().isEmpty()) {
            source.putAll(dto.getRefTarget());
        } else if (dto.getExtension() != null && dto.getExtension().get("refTarget") instanceof Map<?, ?> nested) {
            source.putAll((Map<String, Object>) nested);
        }

        // Resolve the target model code from any alias, in priority order.
        String target = firstNonBlank(
                stringOf(source.get("targetEntity")),
                stringOf(source.get("targetModel")),
                stringOf(source.get("modelCode")),
                dto.getReferenceModelCode(),
                dto.getExtension() != null ? stringOf(dto.getExtension().get("referenceModelCode")) : null,
                dto.getExtension() != null ? stringOf(dto.getExtension().get("refModelCode")) : null);

        // Not a resolvable reference: only normalize reference-type fields with a target.
        if (target == null) {
            return (!"reference".equalsIgnoreCase(dto.getDataType()) || source.isEmpty()) ? null : source;
        }

        Map<String, Object> canonical = new LinkedHashMap<>(source);
        // Drop alias keys — canonical keeps only targetEntity.
        canonical.remove("targetModel");
        canonical.remove("modelCode");
        canonical.put("targetEntity", target);
        canonical.putIfAbsent("refType", "entity");

        // Display field: prefer explicit refTarget.displayField, else extension.refDisplayField.
        if (stringOf(canonical.get("displayField")) == null && dto.getExtension() != null) {
            String refDisplayField = stringOf(dto.getExtension().get("refDisplayField"));
            if (refDisplayField != null) {
                canonical.put("displayField", refDisplayField);
            }
        }
        return canonical;
    }

    private static String stringOf(Object v) {
        return (v instanceof String s && !s.isBlank()) ? s : null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
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
            log.info("Skipping user-modified resource: {} {}", ResourceType.MODEL_FIELD_BINDING, logSafe(bindingCode));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                    bindingPid, null, bindingCode, bindingCode, ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing binding (may be soft-deleted, so also clear deleted_flag)
            var existingBinding = metaModelService.getFieldBinding(modelId, fieldId).orElse(null);
            boolean searchabilityChanged = false;
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
                searchabilityChanged = applyBindingDisplayConfig(existingBinding, dto);
                // Clear soft-delete flag (entity doesn't have deletedFlag to avoid MyBatis Plus global logical delete)
                fieldBindingMapper.clearDeletedFlag(modelId, fieldId);

                metaModelService.updateFieldBinding(existingBinding);
                metaModelService.clearAllCache();
            }
            syncBindingIndexesWhenRequired(model, dto.getModelCode(), searchabilityChanged);

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

                boolean searchabilityChanged = metaModelService.getFieldBinding(modelId, fieldId)
                        .map(binding -> {
                            boolean changed = applyBindingDisplayConfig(binding, dto);
                            metaModelService.updateFieldBinding(binding);
                            return changed;
                        })
                        .orElse(false);

                bindingPid = fieldBindingMapper.getPidByModelAndField(modelId, fieldId);
                log.info("Resurrected soft-deleted binding: {}", logSafe(bindingCode));
                metaModelService.clearAllCache();
                syncBindingIndexesWhenRequired(model, dto.getModelCode(), searchabilityChanged);

                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                        bindingPid, null, bindingCode, bindingCode, ResourceAction.CREATE, null, null);
            }

            // Create new binding via service
            ModelFieldBinding createdBinding = metaModelService.bindFieldToModel(
                    modelId, fieldId, dto.getSequence(), dto.getRequired(),
                    dto.getVisible(), dto.getEditable(), dto.getDefaultValue(),
                    dto.getValidationOverride(), bindingDisplayConfig(dto), dto.getRemarks()
            );

            if (createdBinding != null) {
                applyBindingDisplayConfig(createdBinding, dto);
                metaModelService.updateFieldBinding(createdBinding);
            }

            // Update additional fields via Mapper
            if (dto.getAliasCode() != null || dto.getDictOverrideCode() != null ||
                    dto.getUiHint() != null || dto.getIsSystemBinding() != null) {
                fieldBindingMapper.updateExtraFields(modelId, fieldId,
                        dto.getAliasCode(), dto.getDictOverrideCode(),
                        dto.getUiHint(), dto.getIsSystemBinding());
            }
            metaModelService.clearAllCache();

            // Get the newly created binding's pid
            bindingPid = fieldBindingMapper.getPidByModelAndField(modelId, fieldId);

            if (StatusConstants.PUBLISHED.equals(model.getStatus())) {
                // Auto-publish the field when binding to an already-published model
                if (!StatusConstants.PUBLISHED.equals(field.getStatus())) {
                    Field fieldEntity = metaFieldMapper.selectById(fieldId);
                    if (fieldEntity != null) {
                        fieldEntity.setStatus(StatusConstants.PUBLISHED);
                        metaFieldMapper.updateById(fieldEntity);
                        log.info("Auto-published field {} (bound to published model {})",
                                logSafe(dto.getFieldCode()), logSafe(dto.getModelCode()));
                    }
                }

                syncBindingSchema(dto.getModelCode(), dto.getFieldCode());
            } else {
                syncBindingIndexesWhenRequired(model, dto.getModelCode(),
                        Boolean.TRUE.equals(bindingSearchable(dto)));
            }

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                    bindingPid, null, bindingCode, bindingCode, ResourceAction.CREATE, null, null);
        }
    }

    private boolean applyBindingDisplayConfig(ModelFieldBinding binding, ModelFieldBindingDTO dto) {
        if (dto.getDisplayConfig() != null) {
            binding.setDisplayConfig(bindingDisplayConfig(dto));
        }
        if (dto.getValidationOverride() != null) {
            binding.setValidationRules(dto.getValidationOverride());
        }
        if (dto.getRemarks() != null) {
            binding.setRemarks(dto.getRemarks());
        }

        Boolean configuredSearchable = bindingSearchable(dto);
        if (configuredSearchable == null || Objects.equals(binding.getSearchable(), configuredSearchable)) {
            return false;
        }
        binding.setSearchable(configuredSearchable);
        return true;
    }

    private String bindingDisplayConfig(ModelFieldBindingDTO dto) {
        return dto.getDisplayConfig() == null ? null : toJson(dto.getDisplayConfig());
    }

    private Boolean bindingSearchable(ModelFieldBindingDTO dto) {
        if (dto.getDisplayConfig() == null || !dto.getDisplayConfig().containsKey("searchable")) {
            return null;
        }
        Object value = dto.getDisplayConfig().get("searchable");
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new PluginException("Binding displayConfig.searchable must be boolean for "
                + dto.getModelCode() + "." + dto.getFieldCode());
    }

    private void syncBindingIndexesWhenRequired(MetaModelDTO model, String modelCode,
                                                 boolean searchabilityChanged) {
        if (searchabilityChanged && StatusConstants.PUBLISHED.equals(model.getStatus())) {
            syncBindingSchema(modelCode, null);
        }
    }

    private void syncBindingSchema(String modelCode, String fieldCode) {
        SchemaOperationResult schemaResult = schemaManagementService.updateTableByModel(modelCode);
        if (schemaResult == null || !schemaResult.isSuccess()) {
            String errorMessage = schemaResult != null && schemaResult.getErrorMessage() != null
                    ? schemaResult.getErrorMessage()
                    : "unknown schema sync error";
            String fieldContext = fieldCode == null ? "searchable binding" : "field " + fieldCode;
            throw new PluginException("Failed to sync schema after binding " + fieldContext
                    + " to model " + modelCode + ": " + errorMessage);
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
                    logSafe(dto.getCode()));
        }

        boolean exists = checkCommandExists(tenantId, dto.getCode());
        if (!exists) {
            // A previous uninstall leaves the command soft-deleted; the idempotent
            // insert (ON CONFLICT (tenant_id, code, version) DO NOTHING) would then
            // silently skip the insert and the auto-publish would fail on the
            // never-persisted pid. Resurrect the retired row so the update path
            // refreshes and republishes it in place.
            String resurrectedPid = resurrectSoftDeleted("ab_command_definition", "code",
                    dto.getCode(), tenantId, pluginPid, dto.getEffectiveExtension());
            if (resurrectedPid != null) {
                log.info("Resurrected soft-deleted command {} as {} for plugin reimport",
                        logSafe(dto.getCode()), logSafe(resurrectedPid));
                exists = checkCommandExists(tenantId, dto.getCode());
            }
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Command already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.COMMAND,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.COMMAND, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.COMMAND, logSafe(dto.getCode()));
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
            String extensionJson = toJson(dto.getEffectiveExtension());
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
                     logSafe(dto.getCode()), logSafe(existingCmd.getPid()));

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
            request.setExtension(toJson(dto.getEffectiveExtension()));

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

        if (shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.BINDING_RULE, ruleCode)) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.BINDING_RULE, logSafe(ruleCode));
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
            log.info("Skipping user-modified resource: {} {}", ResourceType.PERMISSION, logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing permission
            PermissionDTO existingPerm = permissionService.findByCode(dto.getCode());

            String dataScopeConfigJson = dto.getDataScopeConfig() != null ? toJson(dto.getDataScopeConfig()) : null;
            String extensionJson = dto.getExtension() != null ? toJson(dto.getExtension()) : null;
            String tagsArray = dto.getTags() != null ? "{" + String.join(",", dto.getTags()) + "}" : null;
            String policySchemaJson = dto.getPolicySchema() != null ? toJson(dto.getPolicySchema()) : null;

            permissionMapper.updateForPluginImport(
                    dto.getEffectiveName(), dto.getDescription(), dto.getCategory(),
                    dto.getResourceType(), dto.getResourceCode(), dto.getAction(),
                    dto.getDataScopeType(), dataScopeConfigJson, extensionJson, tagsArray,
                    policySchemaJson,
                    pluginPid, tenantId, dto.getCode());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    existingPerm.getPid(), null, dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Check for soft-deleted permission and resurrect if found
            String resurrectPid = resurrectSoftDeleted("ab_permission", "code", dto.getCode(), tenantId, pluginPid, null);
            if (resurrectPid != null) {
                log.info("Resurrected soft-deleted permission: code={}, pid={}",
                        logSafe(dto.getCode()), logSafe(resurrectPid));
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
            request.setPolicySchema(dto.getPolicySchema());
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
            log.info("Skipping user-modified resource: {} {}", ResourceType.ROLE, logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing role
            String scopeContentJson = dto.getScopeContent() != null ? toJson(dto.getScopeContent()) : null;

            roleMapper.updateForPluginImport(
                    dto.getEffectiveName(), dto.getDescription(), dto.getType(),
                    dto.getPriority(), dto.getIsDefault(), dto.getIsSystem(),
                    dto.getScopeType(), scopeContentJson, dto.getDefaultDataScopeType(),
                    pluginPid, tenantId, dto.getCode());

            // Reconcile role-permission bindings and data-scope defaults.
            Long roleId = roleMapper.findIdByCode(tenantId, dto.getCode());
            updateRolePermissions(roleId, dto, tenantId, pluginPid);
            applyRoleDataScopes(roleId, dto, tenantId);
            assignSeedMembers(roleId, dto, tenantId);

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
            role.setDefaultDataScopeType(dto.getDefaultDataScopeType());
            role.setStatus(StatusConstants.ACTIVE);
            role.setDeletedFlag(false);
            role.setCreatedAt(Instant.now());
            role.setUpdatedAt(Instant.now());

            Role created = roleService.createRole(role);

            // Update plugin_pid
            roleMapper.updatePluginPidById(pluginPid, created.getId());

            // Create role-permission bindings
            updateRolePermissions(created.getId(), dto, tenantId, pluginPid);
            applyRoleDataScopes(created.getId(), dto, tenantId);
            assignSeedMembers(created.getId(), dto, tenantId);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    private void assignSeedMembers(Long roleId, RoleDefinitionDTO dto, Long tenantId) {
        if (roleId == null || dto.getSeedMembers() == null || dto.getSeedMembers().isEmpty()) {
            return;
        }
        for (String seedMember : dto.getSeedMembers()) {
            if (seedMember == null || seedMember.isBlank()) {
                continue;
            }
            String normalized = seedMember.trim().toUpperCase(java.util.Locale.ROOT);
            if (!"CURRENT_MEMBER".equals(normalized)) {
                throw new PluginException("Unsupported role seedMembers value '" + seedMember
                        + "' for role " + dto.getCode() + ". Supported: CURRENT_MEMBER");
            }
            Long memberId = MetaContext.getCurrentMemberId();
            if (memberId == null) {
                throw new PluginException("Role " + dto.getCode()
                        + " declares seedMembers CURRENT_MEMBER but no current member is available");
            }
            userRoleService.assignRolesToMember(memberId, List.of(roleId), tenantId, MetaContext.getCurrentUserId());
        }
    }

    /**
     * Reconcile declared role permissions for an installed plugin directory: validate
     * the directory and installed version, then strictly check every declared role and
     * permission resolves and is bound for the current tenant. The caller owns the
     * transaction boundary.
     */
    @Override
    public int reconcileDirectoryRolePermissions(String directoryPath,
                                                 PluginDirectoryLoader directoryLoader,
                                                 PluginRecordMapper pluginRecordMapper) {
        Path directory = Path.of(directoryPath).normalize();
        if (!directory.isAbsolute() || directory.toString().contains("..")) {
            throw new PluginException("Role reconciliation requires an absolute plugin directory");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new PluginException("Tenant context is required for role reconciliation");
        }
        PluginManifestExtended manifest = directoryLoader.loadFromDirectory(directory);
        PluginRecord installed = pluginRecordMapper.findByTenantAndPluginId(manifest.getPluginId());
        if (installed == null || !tenantId.equals(installed.getTenantId())
                || !Objects.equals(installed.getVersion(), manifest.getVersion())) {
            throw new PluginException("Role reconciliation requires the same installed plugin version");
        }
        return reconcileDeclaredRolesStrictly(manifest, tenantId);
    }

    int reconcileDeclaredRolesStrictly(PluginManifestExtended manifest, Long tenantId) {
        if (manifest.getRoles() == null || manifest.getRoles().isEmpty()) return 0;
        Map<String, Role> roles = roleService.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(Role::getCode, Function.identity()));
        int checked = 0;
        for (RoleDefinitionDTO declaration : manifest.getRoles()) {
            if (declaration == null || !declaration.isValid()) {
                throw new PluginException("Invalid role declaration during batch reconciliation");
            }
            Role role = roles.get(declaration.getCode());
            if (role == null) throw new PluginException("Imported role not found: " + declaration.getCode());
            List<PermissionDTO> permissions = new ArrayList<>();
            for (String code : Optional.ofNullable(declaration.getPermissions()).orElse(List.of())) {
                PermissionDTO permission = permissionService.findByCode(code);
                if (permission == null || permission.getId() == null) {
                    throw new PluginException("Unresolved declared permission: " + declaration.getCode() + ":" + code);
                }
                permissions.add(permission);
            }
            reconcileRolePermissions(declaration, tenantId);
            for (PermissionDTO permission : permissions) {
                if (rolePermissionMapper.countByRoleAndPermission(role.getId(), permission.getId(), tenantId) <= 0) {
                    throw new PluginException("Declared permission remains unbound: "
                            + declaration.getCode() + ":" + permission.getCode());
                }
            }
            checked++;
        }
        return checked;
    }

    public boolean reconcileRolePermissions(RoleDefinitionDTO dto, Long tenantId) {
        if (dto == null || dto.getCode() == null || dto.getCode().isBlank()) {
            return false;
        }
        Long roleId = roleMapper.findIdByCode(tenantId, dto.getCode());
        if (roleId == null) {
            // Fail-closed: a declared role that cannot be resolved during strict
            // reconciliation is an inconsistent import, not a skippable gap.
            throw new PluginException("Role permission reconciliation failed: declared role '"
                    + dto.getCode() + "' does not exist in this tenant");
        }
        int before = rolePermissionMapper.findPermissionIdsByRole(roleId).size();
        updateRolePermissions(roleId, dto, tenantId, null, true);
        int after = rolePermissionMapper.findPermissionIdsByRole(roleId).size();
        if (after > before) {
            log.info("Role permission reconciliation bound {} additional codes for role {}",
                    after - before, logSafe(dto.getCode()));
        }
        return after > before;
    }

    private void updateRolePermissions(Long roleId, RoleDefinitionDTO dto, Long tenantId, String pluginPid) {
        updateRolePermissions(roleId, dto, tenantId, pluginPid, false);
    }

    /**
     * Bind a role's declared permissions.
     *
     * <p>Two strictness modes:</p>
     * <ul>
     *   <li><b>Role-import stage (deferred, {@code strictResolution=false})</b>: permissions
     *       generated by this plugin's own model publication ({@code model.<code>.<action>})
     *       do not exist yet, because the ROLE import stage runs before auto-publish.
     *       Unresolved codes are logged with role and code and left to the strict
     *       post-publication reconcile; binding failures on resolvable codes are
     *       re-attempted there.</li>
     *   <li><b>Post-publication reconcile ({@code strictResolution=true})</b>: every declared
     *       code must resolve to a registered permission and bind, otherwise the plugin
     *       import fails with a locatable error (role + code + missing-provider analysis).
     *       A stack whose composition cannot satisfy the declarations is rejected instead of
     *       silently degrading the role — the historic warn+skip swallowed bad combinations
     *       until they surfaced rounds later as missing-access findings.</li>
     * </ul>
     *
     * <p>Platform-registered codes (source=system built-ins such as {@code meta.command.execute}
     * and {@code dashboard.read}, and data-view permissions with {@code resource_type=data})
     * need no exemption here: they are registered rows in ab_permission, so they resolve and
     * bind like any other code — declared codes are never exempted from binding.</p>
     */
    void updateRolePermissions(Long roleId, RoleDefinitionDTO dto, Long tenantId, String pluginPid,
                               boolean strictResolution) {
        List<String> permissionCodes = dto.getPermissions();
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return;
        }
        Map<String, RolePermissionPolicyDefinitionDTO> policyByPermission = rolePermissionPoliciesByCode(dto);

        List<String> deferred = new ArrayList<>();
        for (String permCode : permissionCodes) {
            PermissionDTO perm = findRegisteredPermissionOrNull(permCode);
            if (perm == null || perm.getId() == null) {
                if (strictResolution) {
                    throw new PluginException(unresolvedDeclaredPermissionMessage(dto.getCode(), permCode, tenantId));
                }
                deferred.add(permCode);
                continue;
            }

            try {
                // Check if binding exists
                int count = rolePermissionMapper.countByRoleAndPermission(roleId, perm.getId(), tenantId);

                if (count == 0) {
                    // Use role-permission service so role default data scope is inherited.
                    rolePermissionService.assignPermissionsToRole(roleId, List.of(perm.getId()));
                } else {
                    rolePermissionService.inheritDefaultDataScope(roleId, List.of(perm.getId()));
                }
                applyRolePermissionPolicy(roleId, perm, policyByPermission.get(permCode));
            } catch (Exception e) {
                if (strictResolution) {
                    throw new PluginException("Failed to bind declared permission '" + permCode
                            + "' to role '" + dto.getCode() + "': " + e.getMessage(), e);
                }
                // Role-import stage stays best-effort for already-resolvable codes: the
                // strict post-publication reconcile re-binds every declaration and fails
                // the import if anything remains unbound, so a transient race with another
                // import gets a second chance instead of aborting the whole plugin here.
                log.warn("Failed to bind permission {} to role {} at role-import stage; strict reconcile will re-attempt: {}",
                        logSafe(permCode), logSafe(dto.getCode()), logSafe(e.getMessage()), e);
            }
        }
        if (!deferred.isEmpty()) {
            log.info("Role {} defers {} declared permission code(s) that are not registered yet "
                            + "(own-plugin model actions are generated after the role stage); the strict "
                            + "post-publication reconcile must resolve them: {}",
                    logSafe(dto.getCode()), deferred.size(), String.join(", ", deferred));
        }
    }

    /**
     * Resolve a declared permission code to its registered row, or null when the code is
     * not registered for the tenant. The permission service signals an unregistered code
     * by throwing a not-found exception rather than returning null — both shapes mean
     * "not registered" here; any other failure inside the service is treated the same
     * way, so the strict reconcile reports the declaration as unresolvable (with the
     * provider analysis) instead of aborting with a bare service error.
     */
    private PermissionDTO findRegisteredPermissionOrNull(String permCode) {
        try {
            return permissionService.findByCode(permCode);
        } catch (Exception absent) {
            return null;
        }
    }

    /**
     * Build a locatable error for a declared role permission that does not resolve.
     * Names the role and code, and for the dominant {@code model.<code>.<action>} family
     * checks the backing model to separate "provider plugin missing from the stack" from
     * "model present but its permission never registered" — the former names the missing
     * provider gap, the latter points at the owning plugin.
     */
    private String unresolvedDeclaredPermissionMessage(String roleCode, String permCode, Long tenantId) {
        StringBuilder message = new StringBuilder("Role '").append(roleCode)
                .append("' declares permission '").append(permCode)
                .append("' which does not resolve to a registered permission in this tenant");
        String modelCode = modelCodeOfGeneratedActionPermission(permCode);
        if (modelCode != null) {
            List<Model> models = metaModelMapper.selectList(new QueryWrapper<Model>()
                    .eq("tenant_id", tenantId)
                    .eq("code", modelCode)
                    .eq("is_current", true)
                    .eq("deleted_flag", false));
            Model model = models.isEmpty() ? null : models.get(0);
            if (model == null) {
                message.append(". The referenced model '").append(modelCode)
                        .append("' does not exist either, so the provider plugin that publishes it is missing "
                        + "from this stack; import that provider plugin and declare it in plugin.json "
                        + "dependencies, or fix the declared permission code");
            } else {
                String ownerPluginId = null;
                if (model.getPluginPid() != null && pluginRecordMapper.findByPid(model.getPluginPid()) != null) {
                    ownerPluginId = pluginRecordMapper.findByPid(model.getPluginPid()).getPluginId();
                }
                message.append(". The referenced model '").append(modelCode).append("' exists");
                if (ownerPluginId != null) {
                    message.append(" (owned by plugin '").append(ownerPluginId).append("')");
                }
                message.append(" but its permission was never registered; re-import the owning plugin "
                        + "or fix the declared permission code");
            }
        } else {
            message.append("; the provider plugin that registers it is missing from this stack or the "
                    + "declared code is wrong; import the provider plugin and declare it in plugin.json "
                    + "dependencies, or fix the declared permission code");
        }
        return message.toString();
    }

    /**
     * Extract the model code from a generated model-action permission code
     * ({@code model.<modelCode>.<action>}); returns null for other code shapes.
     */
    static String modelCodeOfGeneratedActionPermission(String permCode) {
        if (permCode == null || !permCode.startsWith("model.")) {
            return null;
        }
        String rest = permCode.substring("model.".length());
        int lastDot = rest.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= rest.length() - 1) {
            return null;
        }
        return rest.substring(0, lastDot);
    }

    /**
     * Materialize security-sensitive per-permission scope declarations after role bindings exist.
     * Missing permissions or incomplete permission metadata fail the import instead of silently
     * widening the role to its default scope.
     */
    private void applyRoleDataScopes(Long roleId, RoleDefinitionDTO dto, Long tenantId) {
        List<RoleDataScopeDefinitionDTO> definitions = dto.getDataScopes();
        if (definitions == null || definitions.isEmpty()) {
            return;
        }
        if (roleId == null) {
            throw new PluginException("Cannot apply data scopes for unresolved role " + dto.getCode());
        }
        for (RoleDataScopeDefinitionDTO definition : definitions) {
            if (definition == null || !definition.isValid()) {
                throw new PluginException("Invalid data scope declaration for role " + dto.getCode());
            }
            PermissionDTO permission = permissionService.findByCode(definition.getPermissionCode());
            if (permission == null || permission.getId() == null
                    || permission.getResourceCode() == null || permission.getAction() == null) {
                throw new PluginException("Role " + dto.getCode()
                        + " declares data scope for an unresolved permission: "
                        + definition.getPermissionCode());
            }
            if (dto.getPermissions() == null
                    || !dto.getPermissions().contains(definition.getPermissionCode())
                    || rolePermissionMapper.countByRoleAndPermission(
                            roleId, permission.getId(), tenantId) <= 0) {
                throw new PluginException("Role " + dto.getCode()
                        + " declares data scope for a permission that is not bound to the role: "
                        + definition.getPermissionCode());
            }
            dataScopeService.setScope(
                    tenantId,
                    roleId,
                    permission.getResourceCode(),
                    permission.getAction(),
                    definition.getScopeType(),
                    definition.effectiveMergeStrategy());
        }
    }

    private Map<String, RolePermissionPolicyDefinitionDTO> rolePermissionPoliciesByCode(RoleDefinitionDTO dto) {
        if (dto.getPermissionPolicies() == null || dto.getPermissionPolicies().isEmpty()) {
            return Map.of();
        }
        return dto.getPermissionPolicies().stream()
                .filter(Objects::nonNull)
                .filter(RolePermissionPolicyDefinitionDTO::isValid)
                .collect(Collectors.toMap(
                        RolePermissionPolicyDefinitionDTO::getPermissionCode,
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new));
    }

    private void applyRolePermissionPolicy(
            Long roleId,
            PermissionDTO permission,
            RolePermissionPolicyDefinitionDTO policy) {
        if (policy == null || permission == null || permission.getId() == null) {
            return;
        }
        RolePermission binding = rolePermissionMapper.findByRoleAndPermission(roleId, permission.getId());
        if (binding == null || binding.getId() == null) {
            log.warn("Role-permission binding not found for seeded policy: roleId={}, permissionCode={}",
                    roleId, logSafe(policy.getPermissionCode()));
            return;
        }
        binding.setConditions(policy.getConditions());
        rolePermissionMapper.updateById(binding);
        refreshPermissionPolicyUsageIndex(binding);
    }

    private void refreshPermissionPolicyUsageIndex(RolePermission binding) {
        if (binding == null || binding.getPid() == null || binding.getPid().isBlank()) {
            return;
        }
        DecisionUsageIndexService usageIndexService = usageIndexServiceProvider.getIfAvailable();
        if (usageIndexService != null) {
            usageIndexService.refreshSource("PERMISSION_POLICY", binding.getPid());
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
            log.info("Skipping user-modified resource: {} {}", ResourceType.MENU, logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MENU,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        // Get parent ID if specified
        Long parentId = null;
        if (dto.getParentCode() != null) {
            // First check in-memory map (populated during same import session)
            parentId = menuCodeToIdMap.get(dto.getParentCode());
            if (parentId == null) {
                // Strict lookup by code only; cross-plugin parents resolve from tenants
                // whose provider plugin was imported earlier.
                parentId = menuMapper.findIdByCode(tenantId, dto.getParentCode());
                if (parentId == null) {
                    // Fail-closed: a menu silently re-parented to root hides the broken
                    // reference until navigation looks wrong rounds later.
                    throw new PluginException("Menu '" + dto.getCode() + "' declares parentCode '"
                            + dto.getParentCode() + "' but no menu with that code exists in this tenant; "
                            + "import the plugin that owns the parent menu first (declare it as a "
                            + "dependency) or fix the declaration");
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
                log.info("Skipping user-modified resource: {} {}", ResourceType.PAGE, logSafe(dto.getPageKey()));
                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                        null, null, dto.getPageKey(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
            }
            log.info("Forcing overwrite of auto-created stub page: {}", logSafe(dto.getPageKey()));
        }

        // V2 flat format: kind/profile/title/layout/blocks directly on DTO
        String kind = dto.getKind() != null ? dto.getKind() : "list";
        String profile = dto.getProfile() != null ? dto.getProfile() : "admin";
        String titleJson = dto.getTitle() != null ? toJson(dto.getTitle()) : null;
        String layoutJson = dto.getLayout() != null ? toJson(dto.getLayout()) : null;
        String blocksJson = dto.getBlocks() != null ? toJson(dto.getBlocks()) : "[]";
        String extensionJson = toJson(mergePageExtension(dto));
        String titleDisplay = dto.getEffectiveName();
        boolean isTemplate = dto.getIsTemplate() != null && dto.getIsTemplate();
        int sortWeight = dto.getSortWeight() != null ? dto.getSortWeight() : 0;
        int schemaVersion = dto.getSchemaVersion() != null
                ? dto.getSchemaVersion()
                : com.auraboot.framework.meta.constant.DslRegistry.PAGE_SCHEMA_CURRENT_VERSION;

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

    private Map<String, Object> mergePageExtension(PageSchemaDTO dto) {
        Map<String, Object> extension = new LinkedHashMap<>();
        if (dto.getExtension() != null) {
            extension.putAll(dto.getExtension());
        }
        if (dto.getDataSources() != null && !dto.getDataSources().isEmpty()) {
            extension.put("dataSources", dto.getDataSources());
        }
        if (dto.getLinkageRules() != null) {
            extension.put("linkageRules", dto.getLinkageRules());
        }
        if (dto.getMobileUx() != null && !dto.getMobileUx().isEmpty()) {
            extension.put("mobileUx", dto.getMobileUx());
        }
        return extension;
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
        log.info("Importing dashboard '{}' from config/dashboards/", logSafe(dto.getCode()));

        if (!dto.isValid()) {
            throw new PluginException(
                    "Invalid dashboard definition '%s': code, title and widgets are required".formatted(dto.getCode()));
        }

        // Build widgets JsonNode from the raw List<Object>
        com.fasterxml.jackson.databind.JsonNode widgetsNode = objectMapper.valueToTree(dto.getWidgets());
        com.fasterxml.jackson.databind.JsonNode layoutConfigNode = buildDashboardLayoutConfig(dto.getLayoutConfig());
        com.fasterxml.jackson.databind.JsonNode extensionNode = objectMapper.valueToTree(
                dto.getExtension() != null ? dto.getExtension() : Map.of());

        DashboardDTO existing = dashboardService.findByCode(dto.getCode());

        if (existing != null && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Dashboard already exists: " + dto.getCode());
        }

        if (existing != null && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            log.info("Dashboard '{}' already exists — skipping (SKIP strategy)", logSafe(dto.getCode()));
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
            updateReq.setIsDefault(Boolean.TRUE.equals(dto.getIsDefault()));
            updateReq.setLayoutConfig(layoutConfigNode);
            updateReq.setWidgets(widgetsNode);
            updateReq.setExtension(extensionNode);
            dashboardService.update(existing.getPid(), updateReq);
            // Symmetry with the create path: update() only touches the working
            // draft, so a re-imported dashboard would otherwise keep serving
            // the stale published layout while the import reports success.
            if (!"draft".equals(dto.getEffectiveStatus())) {
                dashboardService.publish(existing.getPid());
            }
            log.info("Dashboard updated from config/dashboards/: code={}, pid={}",
                    logSafe(dto.getCode()), logSafe(existing.getPid()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PAGE,
                    existing.getPid(), null, dto.getCode(), dto.getTitle(),
                    ResourceAction.UPDATE, null, null);
        } else {
            DashboardCreateRequest createReq = new DashboardCreateRequest();
            createReq.setCode(dto.getCode());
            createReq.setTitle(dto.getTitle());
            createReq.setDescription(dto.getDescription());
            createReq.setScope(dto.getEffectiveScope());
            createReq.setIsDefault(Boolean.TRUE.equals(dto.getIsDefault()));
            createReq.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
            createReq.setLayoutConfig(layoutConfigNode);
            createReq.setWidgets(widgetsNode);
            createReq.setExtension(extensionNode);
            DashboardDTO created = dashboardService.create(createReq);
            // Plugin dashboards are published immediately unless status=draft
            if (!"draft".equals(dto.getEffectiveStatus())) {
                dashboardService.publish(created.getPid());
            }
            log.info("Dashboard created from config/dashboards/: code={}, pid={}",
                    logSafe(dto.getCode()), logSafe(created.getPid()));
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
            log.info("Skipping user-modified resource: {} {}", ResourceType.DICT, logSafe(dto.getCode()));
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
                    .map(this::toDictItemRequest)
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
                log.info("Resurrected soft-deleted dict: code={}, pid={}",
                        logSafe(dto.getCode()), logSafe(resurrectPid));
                // Now update the resurrected dict with current data
                DictDTO resurrected = dictService.findByCode(dto.getCode());
                if (resurrected != null && dto.getItems() != null && !dto.getItems().isEmpty()) {
                    List<DictCreateRequest.DictItemCreateRequest> itemRequests = dto.getItems().stream()
                        .map(this::toDictItemRequest)
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
                    .map(this::toDictItemRequest)
                    .toList());
            }

            DictDTO created = dictService.create(request);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.DICT,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    /**
     * Maps one manifest dict item onto its persistence request. The manifest's
     * localized "label:*" entries ride along as a "labels" map inside the item
     * extension so the render-time dict reads can serve the label for the
     * request locale (see DictLabelLocalizationHelper); the stored label keeps
     * the effective single-locale value for dictionaries without translations.
     */
    private DictCreateRequest.DictItemCreateRequest toDictItemRequest(DictDefinitionDTO.DictItemDTO item) {
        DictCreateRequest.DictItemCreateRequest itemReq = new DictCreateRequest.DictItemCreateRequest();
        itemReq.setValue(item.getValue());
        itemReq.setLabel(item.getEffectiveLabel());
        itemReq.setSortOrder(item.getSortNo());
        itemReq.setParentValue(item.getParentValue());
        itemReq.setDisabled(StatusConstants.DISABLED.equals(item.getStatus()));
        Map<String, String> localizedLabels = item.getAllLocalizedLabels();
        if (item.getExtra() != null || (localizedLabels != null && !localizedLabels.isEmpty())) {
            com.fasterxml.jackson.databind.node.ObjectNode extension = item.getExtra() != null
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.convertValue(
                            item.getExtra(), JsonNode.class)
                    : objectMapper.createObjectNode();
            if (localizedLabels != null && !localizedLabels.isEmpty()) {
                extension.set("labels", objectMapper.valueToTree(localizedLabels));
            }
            itemReq.setExtension(extension);
        }
        return itemReq;
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
            log.info("Skipping user-modified resource: {} {}", ResourceType.NAMED_QUERY, logSafe(dto.getCode()));
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
            updateRequest.setResourceCode(dto.getResourceCode());
            updateRequest.setActionCode(dto.getActionCode());
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

            // A6: the status dance (ensure editable → update → republish) can restore
            // from_sql on ab_named_query from a stale version snapshot, silently
            // reverting the imported SQL. Re-assert the imported fromSql after the
            // transition so the executed query matches the plugin source; skip the
            // write when they already agree.
            NamedQuery updated = namedQueryMapper.findByCode(dto.getCode());
            if (updated != null
                    && dto.getFromSql() != null
                    && !dto.getFromSql().equals(updated.getFromSql())) {
                updated.setFromSql(dto.getFromSql());
                namedQueryMapper.updateById(updated);
            }

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.NAMED_QUERY,
                    updated != null ? updated.getPid() : null,
                    updated != null ? updated.getId() : null,
                    dto.getCode(), dto.getEffectiveTitle(),
                    ResourceAction.UPDATE, null, currentState);
            }
        }

        NamedQueryCreateRequest createRequest = new NamedQueryCreateRequest();
        createRequest.setCode(dto.getCode());
        createRequest.setTitle(dto.getEffectiveTitle());
        createRequest.setDescription(dto.getDescription());
        createRequest.setResourceCode(dto.getResourceCode());
        createRequest.setActionCode(dto.getActionCode());
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
        state.put("resourceCode", dto.getResourceCode());
        state.put("actionCode", dto.getActionCode());
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
            log.info("Skipping user-modified resource: {} {}", ResourceType.AGENT_DEFINITION, logSafe(dto.getAgentCode()));
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

            replaceEvalCases(tenantId, dto, pluginPid);

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

        replaceEvalCases(tenantId, dto, pluginPid);

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
        target.setModel(dto.getModel());
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
        target.setKnowledgeBaseIds(dto.getKnowledgeBaseIds() != null
                ? dto.getKnowledgeBaseIds()
                : List.of());
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
        state.put("model", dto.getModel());
        state.put("systemPrompt", dto.getSystemPrompt());
        state.put("tools", dto.getTools());
        state.put("skills", dto.getSkills());
        state.put("guardrails", dto.getGuardrails());
        state.put("soulProfile", dto.getSoulProfile());
        state.put("allowedModels", dto.getAllowedModels());
        state.put("allowedOperations", dto.getAllowedOperations());
        state.put("knowledgeBaseIds", dto.getKnowledgeBaseIds());
        state.put("maxTools", dto.getMaxTools());
        state.put("maxConcurrentRuns", dto.getMaxConcurrentRuns());
        state.put("executionTimeoutSeconds", dto.getExecutionTimeoutSeconds());
        state.put("eventTriggers", dto.getEventTriggers());
        state.put("autoReplyMode", defaultString(dto.getAutoReplyMode(), "mention"));
        state.put("status", defaultString(dto.getStatus(), "active"));
        state.put("visibility", defaultString(dto.getVisibility(), "private"));
        return state;
    }

    /**
     * Replace all eval cases for {@code (tenantId, dto.agentCode)} with the cases
     * declared in the DTO (physical DELETE + INSERT). This is an overwrite-on-import
     * sub-resource: the caller's transaction absorbs the DELETE+INSERT atomically with
     * the agent row write that precedes this call.
     *
     * <p>Validates case structure before touching the DB; throws {@link PluginException}
     * (import fails, {@code success:false}) if any violation is found.
     */
    private void replaceEvalCases(Long tenantId, AgentDefinitionDTO dto, String pluginPid) {
        List<CapabilityEvalCase> cases =
                dto.getEvalCases() == null ? List.of() : dto.getEvalCases();
        List<String> violations = EvalCaseStructureValidator.validate(cases);
        if (!violations.isEmpty()) {
            throw new PluginException(
                    "Invalid eval cases for agent " + dto.getAgentCode() + ": " + violations);
        }
        // Clean slate for this agent — cases are an overwrite-on-import sub-resource.
        jdbcTemplate.update(
                "DELETE FROM ab_agent_eval_case WHERE tenant_id = ? AND agent_code = ?",
                tenantId, dto.getAgentCode());
        Instant now = Instant.now();
        for (CapabilityEvalCase c : cases) {
            AgentEvalCase row = new AgentEvalCase();
            row.setPid(UlidGenerator.generate());
            row.setTenantId(tenantId);
            row.setAgentCode(dto.getAgentCode());
            row.setCaseId(c.getCaseId());
            row.setCategory(c.getCategory());
            row.setTaskDescription(c.getTaskDescription());
            row.setExpectedToolCodes(c.getExpectedToolCodes());
            row.setForbiddenToolCodes(c.getForbiddenToolCodes());
            row.setExpectedInputKeys(c.getExpectedInputKeys());
            row.setExpectedRiskLevel(c.getExpectedRiskLevel());
            row.setExpectsConfirmation(c.isExpectsConfirmation());
            row.setPluginSource(pluginPid);
            row.setDeletedFlag(false);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            agentEvalCaseMapper.insert(row);
        }
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
                        log.warn("Service delete failed for model {}: {}",
                                logSafe(resource.getResourcePid()), logSafe(e.getMessage()));
                        metaModelMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case FIELD -> {
                if (resource.getResourcePid() != null) {
                    try {
                        metaFieldService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for field {}: {}",
                                logSafe(resource.getResourcePid()), logSafe(e.getMessage()));
                        metaFieldMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case COMMAND -> {
                if (resource.getResourcePid() != null) {
                    try {
                        commandService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for command {}: {}",
                                logSafe(resource.getResourcePid()), logSafe(e.getMessage()));
                        commandDefinitionMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case PERMISSION -> {
                if (resource.getResourceId() != null) {
                    try {
                        permissionService.delete(resource.getResourceId());
                    } catch (Exception e) {
                        log.warn("Service delete failed for permission {}: {}",
                                logSafe(resource.getResourceId()), logSafe(e.getMessage()));
                        permissionMapper.softDelete(resource.getResourceId());
                    }
                }
            }
            case ROLE -> {
                try {
                    roleMapper.softDeleteByPid(resource.getResourcePid());
                } catch (Exception e) {
                    log.warn("Failed to delete role {}: {}",
                            logSafe(resource.getResourcePid()), logSafe(e.getMessage()));
                }
            }
            case MENU -> {
                if (resource.getResourceId() != null) {
                    try {
                        menuService.deleteMenu(resource.getResourceId());
                    } catch (Exception e) {
                        log.warn("Service delete failed for menu {}: {}",
                                logSafe(resource.getResourceId()), logSafe(e.getMessage()));
                        menuMapper.softDeleteById(resource.getResourceId());
                    }
                }
            }
            case PAGE -> {
                if (resource.getResourcePid() != null) {
                    try {
                        pageSchemaService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for page {}: {}",
                                logSafe(resource.getResourcePid()), logSafe(e.getMessage()));
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
                                logSafe(resource.getResourcePid()), logSafe(e.getMessage()));
                        dictMapper.softDeleteByPid(resource.getResourcePid());
                    }
                }
            }
            case NAMED_QUERY -> {
                if (resource.getResourcePid() != null) {
                    try {
                        namedQueryService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Failed to delete named query {}: {}",
                                logSafe(resource.getResourcePid()), logSafe(e.getMessage()));
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
                jdbcTemplate.update("""
                        UPDATE ab_agent_eval_case
                        SET deleted_flag = TRUE, updated_at = NOW()
                        WHERE tenant_id = ? AND agent_code = ?
                        """, resource.getTenantId(), resource.getResourceCode());
            }
            default -> log.warn("Rollback not implemented for resource type: {}", type);
        }
    }

    @Override
    public void restoreResource(PluginResource resource) {
        log.info("Restoring resource: {} ({})", logSafe(resource.getResourceCode()), resource.getResourceType());
        ResourceType type = resource.getResourceTypeEnum();
        if (type == ResourceType.AGENT_DEFINITION) {
            if (resource.getResourcePid() != null) {
                jdbcTemplate.update("""
                        UPDATE ab_agent_definition
                        SET deleted_flag = FALSE, updated_at = NOW()
                        WHERE pid = ?
                        """, resource.getResourcePid());
            }
            jdbcTemplate.update("""
                    UPDATE ab_agent_eval_case
                    SET deleted_flag = FALSE, updated_at = NOW()
                    WHERE tenant_id = ? AND agent_code = ?
                    """, resource.getTenantId(), resource.getResourceCode());
            return;
        }
        if (resource.getPreviousState() == null) {
            return;
        }
    }

    // ==================== Stale Menu Reconciliation ====================

    @Override
    public int pruneStaleMenus(String pluginPid, String importId, Long tenantId,
                               java.util.Collection<String> configuredMenuCodes) {
        java.util.Set<String> configured = configuredMenuCodes == null
                ? java.util.Set.of()
                : new java.util.HashSet<>(configuredMenuCodes);
        List<String> staleCodes = trackedMenuCodes(pluginPid, tenantId).stream()
                .filter(code -> !configured.contains(code))
                .toList();
        if (staleCodes.isEmpty()) {
            return 0;
        }
        int pruned = deleteTrackedMenus(pluginPid, importId, tenantId, staleCodes);
        log.info("Pruned {} stale menu(s) dropped from plugin {} declaration: {}",
                pruned, logSafe(pluginPid), staleCodes);
        return pruned;
    }

    @Override
    public int deactivateAllPluginMenus(String pluginPid, Long tenantId) {
        List<String> codes = trackedMenuCodes(pluginPid, tenantId);
        if (codes.isEmpty()) {
            return 0;
        }
        int deactivated = deleteTrackedMenus(pluginPid, null, tenantId, codes);
        log.info("Deactivated {} imported menu(s) of uninstalled plugin {}: {}",
                deactivated, logSafe(pluginPid), codes);
        return deactivated;
    }

    /**
     * Menu codes this plugin family (same plugin_id across installs) previously imported,
     * per tenant. Scoped to ab_plugin_resource tracking and explicitly excludes
     * authoring-managed rows, so tenant-owned and dashboard-created menus are never touched.
     */
    private List<String> trackedMenuCodes(String pluginPid, Long tenantId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT r.resource_code
                FROM ab_plugin_resource r
                JOIN ab_plugin p ON p.pid = r.plugin_pid
                JOIN ab_menu m ON m.tenant_id = r.tenant_id AND m.code = r.resource_code
                               AND m.deleted_flag = FALSE
                WHERE p.plugin_id = (SELECT plugin_id FROM ab_plugin WHERE pid = ?)
                  AND r.tenant_id = ?
                  AND r.resource_type = ?
                  AND COALESCE(m.extension ->> 'authoringManaged', 'false') <> 'true'
                """, String.class, pluginPid, tenantId, ResourceType.MENU.code()).stream()
                .filter(Objects::nonNull)
                .filter(code -> !code.isBlank())
                .toList();
    }

    /**
     * Soft-delete tracked menu rows through {@link #rollbackResource}, appending an audit
     * row per pruned code. rollbackResource falls back to a direct soft delete when the
     * menu service refuses a parent with active children, so a stale subtree converges in
     * one pass. A code whose active menu row is already gone is silently accepted
     * (idempotent re-run).
     */
    private int deleteTrackedMenus(String pluginPid, String importId, Long tenantId, List<String> codes) {
        int pruned = 0;
        for (String code : codes) {
            Long menuId = menuMapper.findIdByCode(tenantId, code);
            if (menuId == null) {
                continue;
            }
            PluginResource record = new PluginResource();
            record.setResourceType(ResourceType.MENU.code());
            record.setResourceCode(code);
            record.setResourceId(menuId);
            record.setResourcePid(menuMapper.findPidByCode(tenantId, code));
            try {
                rollbackResource(record);
            } catch (Exception e) {
                log.warn("Failed to prune stale menu {}: {}", logSafe(code),
                        logSafe(e.getMessage()));
                continue;
            }
            pruned++;
            if (pluginPid != null) {
                upsertResourceRecord(createResourceRecord(pluginPid, importId, tenantId,
                        ResourceType.MENU, record.getResourcePid(), menuId, code, null,
                        ResourceAction.DELETE, null, null));
            }
        }
        return pruned;
    }

    /**
     * ab_plugin_resource enforces one tracking row per (tenant, plugin, type, code), so
     * prune audits follow the import chain's persistence contract: update the existing
     * row in place (re-homing it to the current plugin install), insert only when the
     * code was never tracked.
     */
    private void upsertResourceRecord(PluginResource record) {
        PluginResource existing = pluginResourceMapper.findByTenantPluginAndResource(
                record.getTenantId(), record.getPluginPid(), record.getResourceType(), record.getResourceCode());
        if (existing == null) {
            existing = pluginResourceMapper.findByTypeAndCode(
                    record.getTenantId(), record.getResourceType(), record.getResourceCode());
        }
        if (existing == null) {
            pluginResourceMapper.insert(record);
            return;
        }
        existing.setPluginPid(record.getPluginPid());
        existing.setImportId(record.getImportId());
        existing.setResourcePid(record.getResourcePid());
        existing.setResourceId(record.getResourceId());
        existing.setResourceName(record.getResourceName());
        existing.setAction(record.getAction());
        existing.setPreviousState(record.getPreviousState());
        existing.setCurrentState(record.getCurrentState());
        existing.setImportSnapshot(record.getImportSnapshot());
        existing.setUserModified(false);
        existing.setUserModifiedAt(null);
        existing.setUpdatedAt(record.getUpdatedAt());
        pluginResourceMapper.updateById(existing);
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

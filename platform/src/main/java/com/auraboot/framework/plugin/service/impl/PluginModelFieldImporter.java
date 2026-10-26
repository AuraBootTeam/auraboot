package com.auraboot.framework.plugin.service.impl;

import static com.auraboot.framework.plugin.service.PluginResourceRecords.createResourceRecord;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.entity.payload.FieldRefTargetBean;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.common.constant.StatusConstants;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import java.util.function.Supplier;

/** Imports model, field and binding metadata using the existing service boundaries. */
final class PluginModelFieldImporter {
    private static final ThreadLocal<Set<String>> FIELD_BATCH_MODELS = new ThreadLocal<>();
    // Preserve virtual calls on the original facade receiver.
    private final PluginResourceImporterImpl facadeReceiver;
    private final MetaModelService metaModelService;
    private final MetaFieldService metaFieldService;
    private final SchemaManagementService schemaManagementService;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final ObjectMapper objectMapper;
    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final ExtensionConverter extensionConverter;
    private final Supplier<CacheManager> cacheManagerProvider;
    private final Logger log;
    private final PluginImportSupport support;

    PluginModelFieldImporter(
            PluginResourceImporterImpl facadeReceiver,
            MetaModelService metaModelService,
            MetaFieldService metaFieldService,
            SchemaManagementService schemaManagementService,
            MetaModelFieldBindingMapper fieldBindingMapper,
            ObjectMapper objectMapper,
            MetaModelMapper metaModelMapper,
            MetaFieldMapper metaFieldMapper,
            ExtensionConverter extensionConverter,
            Supplier<CacheManager> cacheManagerProvider,
            Logger log,
            PluginImportSupport support) {
        this.facadeReceiver = facadeReceiver;
        this.metaModelService = metaModelService;
        this.metaFieldService = metaFieldService;
        this.schemaManagementService = schemaManagementService;
        this.fieldBindingMapper = fieldBindingMapper;
        this.objectMapper = objectMapper;
        this.metaModelMapper = metaModelMapper;
        this.metaFieldMapper = metaFieldMapper;
        this.extensionConverter = extensionConverter;
        this.cacheManagerProvider = cacheManagerProvider;
        this.log = log;
        this.support = support;
    }

    public void beginFieldImportBatch() {
        if (FIELD_BATCH_MODELS.get() != null) {
            throw new PluginException("Nested field import schema batch");
        }
        FIELD_BATCH_MODELS.set(new LinkedHashSet<>());
    }

    public void endFieldImportBatch(boolean completed) {
        Set<String> models = FIELD_BATCH_MODELS.get();
        FIELD_BATCH_MODELS.remove();
        if (completed && models != null && !models.isEmpty()) {
            syncReimportedModels(new ArrayList<>(models), "field batch");
        }
    }

    public boolean checkModelExists(Long tenantId, String code) {
        // Deliberately a DB-current probe, not the runtime definition lookup: the
        // definition cache and the application-release catalog can both report a
        // model this import cannot see or load (a draft row cached by a
        // rolled-back import attempt, a catalog-only model), which deadlocked
        // importModel into "Model exists but cannot be loaded for update" on the
        // retry. The probe must resolve exactly what the update branch resolves.
        return metaModelMapper.findCurrentByCodeForTenant(tenantId, code) != null;
    }

    public boolean checkFieldExists(Long tenantId, String code) {
        // Same DB-current contract as checkModelExists; updateFieldForReimport
        // resolves the existing row through this very query.
        return metaFieldMapper.findCurrentByCode(code) != null;
    }

    public PluginResource importModel(ModelDefinitionDTO dto, String pluginPid, String importId,
                                       Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                       Boolean autoPublish) {
        facadeReceiver.registerModelCacheRollbackEviction(dto.getCode());
        boolean exists = facadeReceiver.checkModelExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Model already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.MODEL,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.MODEL, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.MODEL, PluginImportSupport.logSafe(dto.getCode()));
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
        com.auraboot.framework.meta.security.RecordCommandWriterDeclaration.validate(extension.get("recordCommandWriters"));
        com.auraboot.framework.meta.security.RecordMutationLockDeclaration.validate(extension.get("recordMutationLock"));
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
            String extensionJson = support.toJson(wrappedExtension);
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
            String resurrectPid = support.resurrectSoftDeleted("ab_meta_model", "code", dto.getCode(), tenantId, pluginPid, extension);
            if (resurrectPid != null) {
                // Also set the first-class table_name column
                if (effectiveTableName != null) {
                    metaModelMapper.updateTableNameByPid(effectiveTableName, resurrectPid);
                }
                log.info("Resurrected soft-deleted model: code={}, pid={}",
                        PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(resurrectPid));
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
                            PluginImportSupport.logSafe(modelCode));
                    metaModelService.clearAllCache();
                }
            }
        });
    }

    public PluginResource importField(FieldDefinitionDTO dto, String pluginPid, String importId,
                                       Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                       Boolean autoPublish) {
        boolean exists = facadeReceiver.checkFieldExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Field already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.FIELD,
                    null, null, dto.getCode(), dto.getEffectiveDisplayName(), ResourceAction.SKIP, null, null);
        }

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.FIELD, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.FIELD, PluginImportSupport.logSafe(dto.getCode()));
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
            String resurrectPid = support.resurrectSoftDeleted("ab_meta_field", "code", dto.getCode(), tenantId, pluginPid, extension);
            if (resurrectPid != null) {
                log.info("Resurrected soft-deleted field: code={}, pid={}",
                        PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(resurrectPid));
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
                    log.info("Auto-published field after import: {}", PluginImportSupport.logSafe(dto.getCode()));
                } catch (Exception e) {
                    // Auto-publish is best-effort: import succeeded, the field exists in
                    // draft state, an admin can publish manually later. Don't fail the
                    // whole import for a publish hiccup.
                    log.warn("Failed to auto-publish field {}: {}", PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(e.getMessage()), e);
                }
            }
        }

        // Bind dictionary if dictCode is specified
        if (dto.getDictCode() != null && !dto.getDictCode().isBlank()) {
            MetaFieldDTO field = metaFieldService.findCurrentByCode(dto.getCode()).orElse(null);
            if (field != null) {
                metaFieldService.bindDictionary(field.getPid(), dto.getDictCode());
                log.info("Bound dictionary to field: fieldCode={}, dictCode={}",
                        PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(dto.getDictCode()));
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
                 PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(existingField.getPid()));

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

        Set<String> batch = FIELD_BATCH_MODELS.get();
        if (batch != null) {
            batch.addAll(modelCodes);
            return;
        }
        syncReimportedModels(modelCodes, fieldCode);
    }

    private void syncReimportedModels(List<String> modelCodes, String fieldCode) {
        for (String modelCode : modelCodes) {
            SchemaOperationResult schemaResult = schemaManagementService.updateTableByModel(modelCode);
            if (schemaResult == null || !schemaResult.isSuccess()) {
                String errorMessage = schemaResult != null && schemaResult.getErrorMessage() != null
                        ? schemaResult.getErrorMessage()
                        : "unknown schema sync error";
                throw new PluginException("Failed to sync schema after updating field " + fieldCode
                        + " for model " + modelCode + ": " + errorMessage);
            }
            facadeReceiver.verifyPublishedModelGuards(modelCode);
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
        // The DSL's top-level default is persisted in the canonical field feature used by DDL.
        if (dto.getDefaultValue() != null) feature.put("defaultValue", dto.getDefaultValue());
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
        if (cacheManagerProvider.get() != null) {
            Cache cache = cacheManagerProvider.get().getCache("metaField");
            if (cache != null) {
                cache.evict(pid);
            }
        }
    }

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

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.MODEL_FIELD_BINDING, bindingCode)) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.MODEL_FIELD_BINDING, PluginImportSupport.logSafe(bindingCode));
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
                log.info("Resurrected soft-deleted binding: {}", PluginImportSupport.logSafe(bindingCode));
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
                                PluginImportSupport.logSafe(dto.getFieldCode()), PluginImportSupport.logSafe(dto.getModelCode()));
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
        return dto.getDisplayConfig() == null ? null : support.toJson(dto.getDisplayConfig());
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

}

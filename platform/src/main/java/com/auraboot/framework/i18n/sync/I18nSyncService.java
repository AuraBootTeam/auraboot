package com.auraboot.framework.i18n.sync;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.i18n.compiler.I18nCompiler;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * I18n Sync Service - Synchronizes i18n resources from Model/Field metadata
 *
 * This service extracts displayName, placeholder, description from Model/Field
 * and creates corresponding i18n entries in the database.
 *
 * @author AuraBoot
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class I18nSyncService {

    private final I18nResourceService i18nResourceService;
    private final I18nService i18nService;
    private final I18nCompiler i18nCompiler;
    private final MetaModelService metaModelService;
    private final MetaFieldService metaFieldService;

    private static final int PAGE_SIZE = 100;

    /**
     * Sync i18n from a single model
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncFromModel(MetaModelDTO model) {
        if (model == null) {
            return;
        }

        String modelCode = model.getCode();
        String displayName = model.getDisplayName();

        if (StringUtils.hasText(displayName)) {
            i18nResourceService.syncFromModel(model.getId(), modelCode, displayName);
        }

        log.info("Synced i18n from model: {}", modelCode);
    }

    /**
     * Sync i18n from a single field
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncFromField(MetaFieldDTO field, String modelCode) {
        if (field == null || !StringUtils.hasText(modelCode)) {
            return;
        }

        String fieldCode = field.getCode();
        String displayName = field.getDisplayName();
        String placeholder = getExtensionValue(field.getExtension(), "placeholder");
        String description = field.getDescription();

        i18nResourceService.syncFromField(
            field.getId(),
            modelCode,
            fieldCode,
            displayName,
            placeholder,
            description
        );

        log.debug("Synced i18n from field: {}.{}", modelCode, fieldCode);
    }

    /**
     * Sync all models and their fields
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncAll() {
        log.info("Starting full i18n sync from all models and fields...");

        SyncResult result = new SyncResult();

        try {
            // Paginate through all models
            int page = 1;
            boolean hasMore = true;

            while (hasMore) {
                PageResult<MetaModelDTO> pageResult = metaModelService.searchModels(
                    page, PAGE_SIZE, null, null, null, null, null, true
                );

                List<MetaModelDTO> models = pageResult.getRecords();
                if (models == null || models.isEmpty()) {
                    hasMore = false;
                    continue;
                }

                for (MetaModelDTO model : models) {
                    try {
                        // Sync model itself
                        syncFromModel(model);
                        result.setModelsProcessed(result.getModelsProcessed() + 1);

                        // Get fields for this model using getModelFields
                        List<FieldDefinition> fields = metaModelService.getModelFields(model.getCode());

                        if (fields != null) {
                            for (FieldDefinition field : fields) {
                                try {
                                    syncFromFieldDefinition(field, model.getCode());
                                    result.setFieldsProcessed(result.getFieldsProcessed() + 1);
                                } catch (Exception e) {
                                    log.warn("Failed to sync field: {}.{}", model.getCode(), field.getCode(), e);
                                    result.setFieldsFailed(result.getFieldsFailed() + 1);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to sync model: {}", model.getCode(), e);
                        result.setModelsFailed(result.getModelsFailed() + 1);
                    }
                }

                hasMore = page * PAGE_SIZE < pageResult.getTotal();
                page++;
            }

            result.setSuccess(true);

            // Clear cache after sync
            i18nService.clearCache(null);

            log.info("Full i18n sync completed. Models: {}, Fields: {}",
                result.getModelsProcessed(), result.getFieldsProcessed());

        } catch (Exception e) {
            log.error("Full i18n sync failed", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        }

        return result;
    }

    /**
     * Sync i18n from a FieldDefinition
     */
    @Transactional(rollbackFor = Exception.class)
    public void syncFromFieldDefinition(FieldDefinition field, String modelCode) {
        if (field == null || !StringUtils.hasText(modelCode)) {
            return;
        }

        String fieldCode = field.getCode();
        String displayName = field.getDisplayName();
        String description = field.getDescription();

        // Placeholder might be in extraProps
        String placeholder = null;
        if (field.getExtraProps() != null) {
            Object placeholderObj = field.getExtraProps().get("placeholder");
            if (placeholderObj instanceof String) {
                placeholder = (String) placeholderObj;
            }
        }

        // FieldDefinition doesn't have ID, so we use null for refId
        i18nResourceService.syncFromField(
            null,
            modelCode,
            fieldCode,
            displayName,
            placeholder,
            description
        );

        log.debug("Synced i18n from field definition: {}.{}", modelCode, fieldCode);
    }

    /**
     * Sync and compile - sync all then compile
     */
    @Async
    public void syncAndCompileAsync() {
        log.info("Starting async i18n sync and compile...");

        SyncResult syncResult = syncAll();

        if (syncResult.isSuccess()) {
            I18nCompiler.CompileResult compileResult = i18nCompiler.compileAll();
            log.info("Async sync and compile completed. Sync: {} models, {} fields. Compile: {} keys",
                syncResult.getModelsProcessed(), syncResult.getFieldsProcessed(), compileResult.getTotalKeys());
        } else {
            log.error("Async sync failed, skipping compile: {}", syncResult.getError());
        }
    }

    /**
     * Get a value from extension map
     */
    @SuppressWarnings("unchecked")
    private String getExtensionValue(Map<String, Object> extension, String key) {
        if (extension == null) {
            return null;
        }

        Object value = extension.get(key);
        if (value instanceof String) {
            return (String) value;
        }

        return null;
    }

    /**
     * Sync result container
     */
    @lombok.Data
    public static class SyncResult {
        private boolean success;
        private int modelsProcessed;
        private int modelsFailed;
        private int fieldsProcessed;
        private int fieldsFailed;
        private String error;
    }
}

package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that caches childModel → List&lt;RollUpTarget&gt; mappings.
 * Used by CommandExecutorImpl to auto-trigger roll-up recalculations
 * when child records are created/updated/deleted.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RollUpFieldRegistry {

    private final MetaFieldMapper metaFieldMapper;
    private final MetaModelFieldBindingMapper modelFieldBindingMapper;

    /**
     * Cache: childModelCode → list of roll-up targets on parent models.
     * Lazily populated per child model; invalidated on model metadata changes.
     */
    private final Map<String, List<RollUpTarget>> cache = new ConcurrentHashMap<>();

    private volatile boolean fullScanDone = false;

    /**
     * Describes a single roll-up field on a parent model that depends on a child model.
     */
    @Data
    public static class RollUpTarget {
        private final String parentModelCode;
        private final String parentFieldCode;   // field code on parent (column name derived at runtime)
        private final String childField;         // field code in child to aggregate
        private final String childFk;            // FK field in child pointing to parent
        private final String function;           // SUM, COUNT, AVG, MIN, MAX
        private final String childFilter;        // optional SQL filter
    }

    /**
     * Get all roll-up targets that should be recalculated when a child model record changes.
     *
     * @param childModelCode the child model code (e.g. "order_line")
     * @return list of roll-up targets, empty if none
     */
    public List<RollUpTarget> getTargets(String childModelCode) {
        if (!fullScanDone) {
            synchronized (this) {
                if (!fullScanDone) {
                    buildFullCache();
                    fullScanDone = true;
                }
            }
        }
        return cache.getOrDefault(childModelCode, Collections.emptyList());
    }

    /**
     * Invalidate the entire cache. Called when model metadata changes (publish/unpublish/import).
     */
    public void invalidate() {
        cache.clear();
        fullScanDone = false;
        log.info("RollUpFieldRegistry cache invalidated");
    }

    /**
     * Invalidate cache entries related to a specific model (as parent or child).
     */
    public void invalidateModel(String modelCode) {
        // Remove entries where this model is a child
        cache.remove(modelCode);
        // Also clear entries where this model is a parent (requires full rescan)
        // For simplicity, just invalidate all
        cache.clear();
        fullScanDone = false;
        log.debug("RollUpFieldRegistry cache invalidated for model: {}", modelCode);
    }

    /**
     * Scan all current fields across all models to find rollUp configurations.
     * Uses the model-field binding to determine which model each field belongs to.
     */
    private void buildFullCache() {
        cache.clear();
        try {
            // Query all current fields that have a non-null feature column
            List<Field> allFields = metaFieldMapper.findCurrentByTenant();
            int count = 0;
            for (Field field : allFields) {
                FieldFeatureBean feature = field.getFeature();
                if (feature == null || feature.getRollUp() == null) {
                    continue;
                }
                FieldFeatureBean.RollUpConfig rollUp = feature.getRollUp();
                if (rollUp.getChildModel() == null || rollUp.getChildFk() == null) {
                    log.warn("Field '{}' has incomplete rollUp config (missing childModel or childFk), skipping", field.getCode());
                    continue;
                }

                // Determine which parent model this field belongs to via binding
                String parentModelCode = modelFieldBindingMapper.findModelCodeByFieldId(field.getId());
                if (parentModelCode == null) {
                    log.warn("Field '{}' has rollUp config but no model binding found, skipping", field.getCode());
                    continue;
                }

                String function = rollUp.getFunction() != null ? rollUp.getFunction().toLowerCase() : "sum";
                RollUpTarget target = new RollUpTarget(
                        parentModelCode,
                        field.getCode(),
                        rollUp.getChildField(),
                        rollUp.getChildFk(),
                        function,
                        rollUp.getChildFilter()
                );

                cache.computeIfAbsent(rollUp.getChildModel(), k -> new ArrayList<>()).add(target);
                count++;
            }
            log.info("RollUpFieldRegistry built: {} roll-up targets across {} child models", count, cache.size());
        } catch (Exception e) {
            log.error("Failed to build RollUpFieldRegistry cache", e);
        }
    }
}

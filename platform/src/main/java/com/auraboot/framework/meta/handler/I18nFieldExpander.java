package com.auraboot.framework.meta.handler;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Handles automatic expansion of i18n-enabled fields during model publish.
 * <p>
 * When a model containing fields with {@code feature.i18nEnabled = true} is published,
 * this handler auto-creates locale companion fields for each such field:
 * <ul>
 *   <li>{@code {fieldCode}_en_us} — English (US)</li>
 *   <li>{@code {fieldCode}_ja_jp} — Japanese</li>
 *   <li>{@code {fieldCode}_ko_kr} — Korean</li>
 * </ul>
 * <p>
 * The primary field holds the default locale value (zh-CN).
 * Frontend uses {@code SmartI18nTextInput} for multi-locale editing and
 * {@code SmartI18nText} for locale-aware display with fallback.
 * <p>
 * This method is idempotent — safe to call multiple times on the same model.
 *
 * @author AuraBoot Team
 * @since 7.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class I18nFieldExpander {

    /** Supported locale suffixes (locale tag → field code suffix). */
    public static final Map<String, String> LOCALE_SUFFIXES = Map.of(
            "en-US", "_en_us",
            "ja-JP", "_ja_jp",
            "ko-KR", "_ko_kr"
    );

    /** Ordered locale tags (for predictable field creation order). */
    public static final List<String> ORDERED_LOCALES = List.of("en-US", "ja-JP", "ko-KR");

    /** Marker used in extension to identify auto-created fields. */
    private static final String AUTO_CREATED_BY = "I18nFieldExpander";

    private final MetaFieldMapper metaFieldMapper;
    private final MetaModelFieldBindingMapper fieldBindingMapper;

    /**
     * Expand i18n-enabled fields for a model being published.
     *
     * @param model the model being published
     * @return list of newly created field codes (empty if no expansion needed)
     */
    @Transactional
    public List<String> expandI18nFields(Model model) {
        log.info("Checking i18n field expansion for model: code={}, pid={}", model.getCode(), model.getPid());

        Long tenantId = MetaContext.getCurrentTenantId();
        List<String> createdFields = new ArrayList<>();

        // Load all field bindings for this model
        List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(model.getId());
        if (bindings == null || bindings.isEmpty()) {
            log.debug("No field bindings found for model {}, skipping i18n expansion", model.getCode());
            return createdFields;
        }

        // Load all bound fields
        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .collect(Collectors.toList());
        List<Field> fields = metaFieldMapper.findByIds(fieldIds);

        // Find fields with i18nEnabled = true
        List<Field> i18nFields = fields.stream()
                .filter(f -> f.getFeature() != null && Boolean.TRUE.equals(f.getFeature().getI18nEnabled()))
                .collect(Collectors.toList());

        if (i18nFields.isEmpty()) {
            log.debug("No i18n-enabled fields in model {}, skipping expansion", model.getCode());
            return createdFields;
        }

        log.info("Found {} i18n-enabled field(s) in model {}: {}",
                i18nFields.size(), model.getCode(),
                i18nFields.stream().map(Field::getCode).collect(Collectors.joining(", ")));

        // Collect existing field codes for idempotency
        Set<String> existingFieldCodes = fields.stream()
                .map(Field::getCode)
                .collect(Collectors.toSet());

        // Create companion fields for each i18n-enabled field
        for (Field sourceField : i18nFields) {
            for (String locale : ORDERED_LOCALES) {
                String suffix = LOCALE_SUFFIXES.get(locale);
                String companionCode = sourceField.getCode() + suffix;

                if (existingFieldCodes.contains(companionCode)) {
                    log.debug("Companion field {} already exists, skipping", companionCode);
                    continue;
                }

                Field companion = createLocaleCompanionField(sourceField, companionCode, locale, tenantId);
                bindFieldToModel(companion, model, tenantId);
                createdFields.add(companionCode);
                existingFieldCodes.add(companionCode);
                log.info("Created i18n companion field: {} (locale={}) for field: {}",
                        companionCode, locale, sourceField.getCode());
            }
        }

        if (!createdFields.isEmpty()) {
            log.info("i18n field expansion complete for model {}: created {} field(s): {}",
                    model.getCode(), createdFields.size(), String.join(", ", createdFields));
        }

        return createdFields;
    }

    /**
     * Create a locale companion field for an i18n-enabled source field.
     *
     * @param sourceField  the original i18n-enabled field
     * @param companionCode the new field code (e.g. product_name_en_us)
     * @param locale       the BCP-47 locale tag (e.g. "en-US")
     * @param tenantId     current tenant ID
     * @return the created (or re-fetched) field entity
     */
    private Field createLocaleCompanionField(Field sourceField, String companionCode,
                                              String locale, Long tenantId) {
        Field companion = new Field();
        companion.setPid(UniqueIdGenerator.generate());
        companion.setCode(companionCode);
        // Preserve same data type as the source field (TEXT, STRING, etc.)
        companion.setDataType(sourceField.getDataType());
        companion.setTenantId(tenantId);
        companion.setVersion(1);
        companion.setIsCurrent(true);
        companion.setRowVersion(1);
        companion.setStatus(StatusConstants.PUBLISHED);
        companion.setDeletedFlag(false);
        companion.setCreatedAt(Instant.now());
        companion.setUpdatedAt(Instant.now());

        // Feature: not required, editable (users can fill translations)
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        // Inherit precision/scale if present (e.g. for future non-TEXT types)
        if (sourceField.getFeature() != null) {
            feature.setPrecision(sourceField.getFeature().getPrecision());
            feature.setScale(sourceField.getFeature().getScale());
        }
        companion.setFeature(feature);

        // Extension: mark as auto-created + display name in English for clarity
        Map<String, Object> extMap = new LinkedHashMap<>();
        extMap.put("displayName", companionCode);
        extMap.put("autoCreatedBy", AUTO_CREATED_BY);
        extMap.put("i18nLocale", locale);
        extMap.put("i18nSourceField", sourceField.getCode());
        ExtensionBean ext = new ExtensionBean();
        ext.setExtension(extMap);
        companion.setExtension(ext);

        metaFieldMapper.insertIdempotent(companion);

        // Re-fetch to get the generated id (insertIdempotent returns 0 if already exists)
        if (companion.getId() == null || companion.getId() == 0) {
            Field existing = metaFieldMapper.findCurrentByCode(companionCode);
            if (existing != null) {
                companion.setId(existing.getId());
                companion.setPid(existing.getPid());
            }
        }

        return companion;
    }

    /**
     * Bind a companion field to the model.
     * Companion fields are editable but hidden from the default list view.
     */
    private void bindFieldToModel(Field field, Model model, Long tenantId) {
        if (field.getId() == null) {
            log.warn("Cannot bind field {} to model {}: field ID is null",
                    field.getCode(), model.getCode());
            return;
        }

        // Idempotency: skip if already bound
        int count = fieldBindingMapper.countByModelAndField(model.getId(), field.getId());
        if (count > 0) {
            log.debug("Field {} already bound to model {}, skipping", field.getCode(), model.getCode());
            return;
        }

        Integer maxOrder = fieldBindingMapper.getMaxFieldOrder(model.getId());
        int nextOrder = (maxOrder != null ? maxOrder : 0) + 1;

        ModelFieldBinding binding = new ModelFieldBinding(tenantId, model.getId(), field.getId(), nextOrder);
        binding.setRequired(false);
        binding.setVisible(false);   // hidden from default list view
        binding.setEditable(true);   // can be edited in the i18n tab
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());

        fieldBindingMapper.insert(binding);
        log.debug("Bound i18n companion field {} (id={}) to model {} (id={})",
                field.getCode(), field.getId(), model.getCode(), model.getId());
    }

    /**
     * Derive the companion field code for a given source field code and locale.
     *
     * @param sourceCode source field code (e.g. "product_name")
     * @param locale     BCP-47 locale tag (e.g. "en-US")
     * @return companion field code (e.g. "product_name_en_us"), or {@code sourceCode} if locale is zh-CN or unknown
     */
    public static String getCompanionFieldCode(String sourceCode, String locale) {
        if (locale == null || locale.startsWith("zh")) {
            return sourceCode;
        }
        String suffix = LOCALE_SUFFIXES.get(locale);
        return suffix != null ? sourceCode + suffix : sourceCode;
    }

    /**
     * Given a record map, return the best-matching value for the given locale.
     * Falls back to the primary field if the companion field is null/missing.
     *
     * @param record     data record (field code → value)
     * @param fieldCode  primary field code
     * @param locale     active locale (e.g. "en-US")
     * @return localized value or null
     */
    public static Object resolveLocalizedValue(Map<String, Object> record, String fieldCode, String locale) {
        if (record == null) return null;
        String companionCode = getCompanionFieldCode(fieldCode, locale);
        if (!companionCode.equals(fieldCode)) {
            Object companionValue = record.get(companionCode);
            if (companionValue != null && !companionValue.toString().isBlank()) {
                return companionValue;
            }
        }
        return record.get(fieldCode);
    }
}

package com.auraboot.module.finance.engine;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.BindingRuleDTO;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.service.CommandService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Handles automatic expansion of MONEY type fields during model publish.
 * <p>
 * When a model containing MONEY type fields is published, this handler:
 * <ol>
 *   <li>Auto-creates {@code {field}_base} companion fields (DECIMAL, readOnly) for each MONEY field</li>
 *   <li>Ensures the model has currency header fields (currency_code, exchange_rate, exchange_rate_id, base_currency_code)</li>
 *   <li>Auto-registers a BindingRule for the currencyConversionHandler on the model's CREATE command</li>
 * </ol>
 * <p>
 * Called from {@code MetaModelServiceImpl.publish()} before table creation.
 *
 * @author AuraBoot Team
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyFieldTypeHandler {

    private static final String MONEY_DATA_TYPE = "money";
    private static final String DECIMAL_DATA_TYPE = "decimal";
    private static final String BASE_SUFFIX = "_base";
    private static final String HANDLER_NAME = "currencyConversionHandler";
    private static final String HANDLER_RULE_TYPE = "handler";

    private static final int DEFAULT_PRECISION = 18;
    private static final int DEFAULT_SCALE = 4;

    /**
     * Currency header field definitions: code suffix -> data type
     */
    private static final List<CurrencyHeaderFieldDef> CURRENCY_HEADER_FIELDS = List.of(
            new CurrencyHeaderFieldDef("currency_code", "string", "Currency Code", "币种"),
            new CurrencyHeaderFieldDef("exchange_rate", "decimal", "Exchange Rate", "汇率"),
            new CurrencyHeaderFieldDef("exchange_rate_id", "reference", "Exchange Rate ID", "汇率ID"),
            new CurrencyHeaderFieldDef("base_currency_code", "string", "Base Currency Code", "本位币")
    );

    private final MetaFieldMapper metaFieldMapper;
    private final MetaModelMapper metaModelMapper;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final CommandDefinitionMapper commandDefinitionMapper;
    private final BindingRuleMapper bindingRuleMapper;
    private final CommandService commandService;
    private final ObjectMapper objectMapper;

    /**
     * Expand MONEY fields for a model being published.
     * <p>
     * This method is idempotent — safe to call multiple times on the same model.
     *
     * @param model the model being published
     * @return list of newly created field codes (empty if no expansion needed)
     */
    @Transactional
    public List<String> expandMoneyFields(Model model) {
        log.info("Checking MONEY field expansion for model: code={}, pid={}", model.getCode(), model.getPid());

        Long tenantId = MetaContext.getCurrentTenantId();
        List<String> createdFields = new ArrayList<>();

        // Load all field bindings for this model
        List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(model.getId());
        if (bindings == null || bindings.isEmpty()) {
            log.debug("No field bindings found for model {}, skipping MONEY expansion", model.getCode());
            return createdFields;
        }

        // Load all bound fields
        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .collect(Collectors.toList());
        List<Field> fields = metaFieldMapper.findByIds(fieldIds);

        // Find MONEY type fields
        List<Field> moneyFields = fields.stream()
                .filter(f -> MONEY_DATA_TYPE.equalsIgnoreCase(f.getDataType()))
                .collect(Collectors.toList());

        if (moneyFields.isEmpty()) {
            log.debug("No MONEY fields in model {}, skipping expansion", model.getCode());
            return createdFields;
        }

        log.info("Found {} MONEY field(s) in model {}: {}",
                moneyFields.size(), model.getCode(),
                moneyFields.stream().map(Field::getCode).collect(Collectors.joining(", ")));

        // Collect existing field codes for this model
        Set<String> existingFieldCodes = fields.stream()
                .map(Field::getCode)
                .collect(Collectors.toSet());

        // Step 1: Create _base companion fields for each MONEY field
        List<String> amountFieldCodes = new ArrayList<>();
        for (Field moneyField : moneyFields) {
            amountFieldCodes.add(moneyField.getCode());
            String baseFieldCode = moneyField.getCode() + BASE_SUFFIX;

            if (existingFieldCodes.contains(baseFieldCode)) {
                log.debug("Base field {} already exists, skipping", baseFieldCode);
                continue;
            }

            Field baseField = createBaseCompanionField(moneyField, tenantId);
            bindFieldToModel(baseField, model, tenantId);
            createdFields.add(baseFieldCode);
            existingFieldCodes.add(baseFieldCode);
            log.info("Created base companion field: {} for MONEY field: {}", baseFieldCode, moneyField.getCode());
        }

        // Step 2: Ensure currency header fields exist
        String modelPrefix = deriveFieldPrefix(model.getCode(), moneyFields);
        boolean hasCurrencyField = existingFieldCodes.stream()
                .anyMatch(code -> code.endsWith("_currency_code"));

        if (!hasCurrencyField) {
            for (CurrencyHeaderFieldDef headerDef : CURRENCY_HEADER_FIELDS) {
                String fieldCode = modelPrefix + headerDef.codeSuffix();
                if (existingFieldCodes.contains(fieldCode)) {
                    log.debug("Currency header field {} already exists, skipping", fieldCode);
                    continue;
                }

                Field headerField = createCurrencyHeaderField(fieldCode, headerDef, tenantId);
                bindFieldToModel(headerField, model, tenantId);
                createdFields.add(fieldCode);
                existingFieldCodes.add(fieldCode);
                log.info("Created currency header field: {}", fieldCode);
            }
        } else {
            log.debug("Model {} already has currency_code field, skipping header field creation", model.getCode());
        }

        // Step 3: Register BindingRule for currencyConversionHandler on CREATE command
        registerCurrencyConversionRule(model, modelPrefix, amountFieldCodes);

        if (!createdFields.isEmpty()) {
            log.info("MONEY field expansion complete for model {}: created {} field(s): {}",
                    model.getCode(), createdFields.size(), String.join(", ", createdFields));
        }

        return createdFields;
    }

    /**
     * Create a _base companion DECIMAL field mirroring the MONEY field's precision/scale.
     */
    private Field createBaseCompanionField(Field moneyField, Long tenantId) {
        String baseCode = moneyField.getCode() + BASE_SUFFIX;

        // Extract precision/scale from the money field's feature
        int precision = DEFAULT_PRECISION;
        int scale = DEFAULT_SCALE;
        if (moneyField.getFeature() != null) {
            if (moneyField.getFeature().getPrecision() != null) {
                precision = moneyField.getFeature().getPrecision();
            }
            if (moneyField.getFeature().getScale() != null) {
                scale = moneyField.getFeature().getScale();
            }
        }

        Field baseField = new Field();
        baseField.setPid(UniqueIdGenerator.generate());
        baseField.setCode(baseCode);
        baseField.setDataType(DECIMAL_DATA_TYPE);
        baseField.setTenantId(tenantId);
        baseField.setVersion(1);
        baseField.setIsCurrent(true);
        baseField.setRowVersion(1);
        baseField.setStatus(StatusConstants.PUBLISHED);
        baseField.setDeletedFlag(false);
        baseField.setCreatedAt(Instant.now());
        baseField.setUpdatedAt(Instant.now());

        // Set feature with precision/scale and readonly
        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setPrecision(precision);
        feature.setScale(scale);
        feature.setReadonly(true);
        baseField.setFeature(feature);

        // Set extension with displayName
        String displayNameSuffix = " (Base)";
        Map<String, Object> extMap = new LinkedHashMap<>();
        extMap.put("displayName", baseCode + displayNameSuffix);
        extMap.put("autoCreatedBy", "MoneyFieldTypeHandler");
        ExtensionBean ext = new ExtensionBean();
        ext.setExtension(extMap);
        baseField.setExtension(ext);

        metaFieldMapper.insertIdempotent(baseField);

        // Re-fetch to get the generated id (insertIdempotent may return 0 if already exists)
        if (baseField.getId() == null || baseField.getId() == 0) {
            Field existing = metaFieldMapper.findCurrentByCode(baseCode);
            if (existing != null) {
                baseField.setId(existing.getId());
                baseField.setPid(existing.getPid());
            }
        }

        return baseField;
    }

    /**
     * Create a currency header field (currency_code, exchange_rate, etc.).
     */
    private Field createCurrencyHeaderField(String fieldCode, CurrencyHeaderFieldDef def, Long tenantId) {
        Field field = new Field();
        field.setPid(UniqueIdGenerator.generate());
        field.setCode(fieldCode);
        field.setDataType(def.dataType());
        field.setTenantId(tenantId);
        field.setVersion(1);
        field.setIsCurrent(true);
        field.setRowVersion(1);
        field.setStatus(StatusConstants.PUBLISHED);
        field.setDeletedFlag(false);
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());

        // Set feature for decimal type (exchange_rate)
        if ("decimal".equals(def.dataType())) {
            FieldFeatureBean feature = new FieldFeatureBean();
            feature.setPrecision(18);
            feature.setScale(8); // exchange rates need higher precision
            field.setFeature(feature);
        }

        // Set extension with displayName
        Map<String, Object> extMap = new LinkedHashMap<>();
        extMap.put("displayName", def.displayNameEn());
        extMap.put("autoCreatedBy", "MoneyFieldTypeHandler");
        ExtensionBean ext = new ExtensionBean();
        ext.setExtension(extMap);
        field.setExtension(ext);

        metaFieldMapper.insertIdempotent(field);

        // Re-fetch to get the generated id
        if (field.getId() == null || field.getId() == 0) {
            Field existing = metaFieldMapper.findCurrentByCode(fieldCode);
            if (existing != null) {
                field.setId(existing.getId());
                field.setPid(existing.getPid());
            }
        }

        return field;
    }

    /**
     * Bind a field to the model with appropriate defaults.
     */
    private void bindFieldToModel(Field field, Model model, Long tenantId) {
        if (field.getId() == null) {
            log.warn("Cannot bind field {} to model {}: field ID is null", field.getCode(), model.getCode());
            return;
        }

        // Check if already bound
        int count = fieldBindingMapper.countByModelAndField(model.getId(), field.getId());
        if (count > 0) {
            log.debug("Field {} already bound to model {}, skipping", field.getCode(), model.getCode());
            return;
        }

        Integer maxOrder = fieldBindingMapper.getMaxFieldOrder(model.getId());
        int nextOrder = (maxOrder != null ? maxOrder : 0) + 1;

        ModelFieldBinding binding = new ModelFieldBinding(tenantId, model.getId(), field.getId(), nextOrder);
        binding.setRequired(false);
        binding.setVisible(true);
        binding.setEditable(false); // auto-created fields are not directly editable
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());

        fieldBindingMapper.insert(binding);
        log.debug("Bound field {} (id={}) to model {} (id={})", field.getCode(), field.getId(), model.getCode(), model.getId());
    }

    /**
     * Register a HANDLER BindingRule for currencyConversionHandler on the model's CREATE command.
     */
    private void registerCurrencyConversionRule(Model model, String fieldPrefix,
                                                 List<String> amountFieldCodes) {
        // Find CREATE command for this model
        String createCommandCode = model.getCode() + "_CREATE";
        CommandDefinition createCommand = commandDefinitionMapper.findCurrentByCode(createCommandCode);

        if (createCommand == null) {
            // Try alternative naming: "create_{modelCode}"
            createCommand = commandDefinitionMapper.findCurrentByCode("create_" + model.getCode());
        }

        if (createCommand == null) {
            log.info("No CREATE command found for model {}, skipping BindingRule registration. " +
                    "Command will be registered when the command is created.", model.getCode());
            return;
        }

        // Check if a currencyConversionHandler rule already exists
        List<com.auraboot.framework.meta.entity.BindingRule> existingRules =
                bindingRuleMapper.findByCommandIdAndType(createCommand.getId(), HANDLER_RULE_TYPE);

        boolean alreadyRegistered = existingRules.stream()
                .anyMatch(r -> HANDLER_NAME.equals(r.getHandlerClass()));

        if (alreadyRegistered) {
            log.debug("CurrencyConversionHandler rule already registered for command {}", createCommandCode);
            return;
        }

        // Build handler config
        String config = buildHandlerConfig(fieldPrefix, amountFieldCodes);

        BindingRuleDTO ruleDTO = new BindingRuleDTO();
        ruleDTO.setRuleType(HANDLER_RULE_TYPE);
        ruleDTO.setHandlerClass(HANDLER_NAME);
        ruleDTO.setConfig(config);
        ruleDTO.setSequence(50); // mid-priority: after field mapping, before effects
        ruleDTO.setEnabled(true);
        ruleDTO.setEventType("before_save");

        commandService.addBindingRule(createCommand.getPid(), ruleDTO);
        log.info("Registered currencyConversionHandler BindingRule for command: {}", createCommandCode);

        // Also register for UPDATE command if it exists
        registerForUpdateCommand(model, fieldPrefix, amountFieldCodes);
    }

    /**
     * Also register the handler for the UPDATE command if one exists.
     */
    private void registerForUpdateCommand(Model model, String fieldPrefix,
                                            List<String> amountFieldCodes) {
        String updateCommandCode = model.getCode() + "_UPDATE";
        CommandDefinition updateCommand = commandDefinitionMapper.findCurrentByCode(updateCommandCode);

        if (updateCommand == null) {
            updateCommand = commandDefinitionMapper.findCurrentByCode("update_" + model.getCode());
        }

        if (updateCommand == null) {
            log.debug("No UPDATE command found for model {}", model.getCode());
            return;
        }

        List<com.auraboot.framework.meta.entity.BindingRule> existingRules =
                bindingRuleMapper.findByCommandIdAndType(updateCommand.getId(), HANDLER_RULE_TYPE);

        boolean alreadyRegistered = existingRules.stream()
                .anyMatch(r -> HANDLER_NAME.equals(r.getHandlerClass()));

        if (alreadyRegistered) {
            return;
        }

        String config = buildHandlerConfig(fieldPrefix, amountFieldCodes);

        BindingRuleDTO ruleDTO = new BindingRuleDTO();
        ruleDTO.setRuleType(HANDLER_RULE_TYPE);
        ruleDTO.setHandlerClass(HANDLER_NAME);
        ruleDTO.setConfig(config);
        ruleDTO.setSequence(50);
        ruleDTO.setEnabled(true);
        ruleDTO.setEventType("before_save");

        commandService.addBindingRule(updateCommand.getPid(), ruleDTO);
        log.info("Registered currencyConversionHandler BindingRule for UPDATE command: {}", updateCommandCode);
    }

    /**
     * Build the JSON config for the currencyConversionHandler.
     */
    private String buildHandlerConfig(String fieldPrefix, List<String> amountFieldCodes) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", "header");
        config.put("currencyField", fieldPrefix + "currency_code");
        config.put("rateField", fieldPrefix + "exchange_rate");
        config.put("rateIdField", fieldPrefix + "exchange_rate_id");
        config.put("baseCurrencyField", fieldPrefix + "base_currency_code");
        config.put("amountFields", amountFieldCodes);

        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize handler config", e);
            return "{}";
        }
    }

    /**
     * Derive a field prefix from the model code and existing money fields.
     * <p>
     * For example, if model code is "sl_sales_order" and money field is "sl_so_total_amount",
     * the prefix would be "sl_so_". If no common prefix is found, uses the model code + "_".
     */
    private String deriveFieldPrefix(String modelCode, List<Field> moneyFields) {
        if (moneyFields.isEmpty()) {
            return modelCode + "_";
        }

        // Try to extract prefix from the first money field
        String firstMoneyCode = moneyFields.get(0).getCode();
        int lastUnderscore = firstMoneyCode.lastIndexOf('_');
        if (lastUnderscore > 0) {
            String candidate = firstMoneyCode.substring(0, lastUnderscore + 1);
            // Verify all money fields share this prefix (minus the specific part)
            // We use the prefix up to the second-to-last segment
            String[] parts = firstMoneyCode.split("_");
            if (parts.length >= 3) {
                // Use first N-1 underscore-separated segments as prefix
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) prefix.append('_');
                    prefix.append(parts[i]);
                }
                prefix.append('_');
                return prefix.toString();
            }
            return candidate;
        }

        return modelCode + "_";
    }

    /**
     * Definition for a currency header field.
     */
    private record CurrencyHeaderFieldDef(
            String codeSuffix,
            String dataType,
            String displayNameEn,
            String displayNameZh
    ) {}
}

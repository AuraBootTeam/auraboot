package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.entity.payload.ComputedFieldOverride;
import com.auraboot.framework.meta.entity.payload.ViewModelConfig;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.NamedQueryFieldMapper;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.ViewModelService;
import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ViewModel service implementation.
 * Handles three-layer field resolution and data query proxying
 * for VIEW type models.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ViewModelServiceImpl extends BaseMetaService implements ViewModelService {

    private final MetaModelMapper metaModelMapper;
    private final MetaFieldMapper metaFieldMapper;
    private final MetaModelFieldBindingMapper fieldBindingMapper;
    private final NamedQueryFieldMapper namedQueryFieldMapper;
    private final NamedQueryService namedQueryService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    @Cacheable(
        value = "viewModelFields",
        key = "#viewModelCode + '_' + T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix()",
        unless = "#result == null || #result.isEmpty()"
    )
    public List<ResolvedFieldDTO> resolveViewFields(String viewModelCode) {
        Model viewModel = loadViewModel(viewModelCode);
        ViewModelConfig config = parseViewModelConfig(viewModel);

        List<ResolvedFieldDTO> resolvedFields;

        if (config.isInheritMode()) {
            resolvedFields = resolveInheritFields(config);
        } else {
            resolvedFields = resolveQueryFields(config);
        }

        // Apply Layer 3: computed field overrides
        applyComputedOverrides(resolvedFields, config);

        return resolvedFields;
    }

    @Override
    @Transactional(readOnly = true)
    public PaginationResult<Map<String, Object>> queryViewData(String viewModelCode, NamedQueryTestRequest request) {
        Model viewModel = loadViewModel(viewModelCode);
        ViewModelConfig config = parseViewModelConfig(viewModel);

        if (config.isInheritMode()) {
            // For inherit mode, proxy to the base entity's named query or dynamic query
            // Use the base entity code to build a query
            String baseCode = config.getBaseEntityCode();
            if (baseCode == null) {
                throw new MetaServiceException("Inherit mode requires baseEntityCode");
            }
            // If a namedQueryCode is also specified, use it; otherwise build dynamic query
            if (config.getNamedQueryCode() != null) {
                return namedQueryService.executeQuery(config.getNamedQueryCode(), request);
            }
            // Fallback: query the base entity table directly
            throw new MetaServiceException("Inherit mode without namedQueryCode requires DynamicDataService integration");
        } else {
            // compose/free mode: delegate to NamedQueryService
            String queryCode = config.getNamedQueryCode();
            if (queryCode == null) {
                throw new MetaServiceException("Compose/free mode requires namedQueryCode");
            }
            return namedQueryService.executeQuery(queryCode, request);
        }
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(
        value = "viewModelSummary",
        key = "#viewModelCode + '_' + T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix()",
        unless = "#result == null"
    )
    public ViewModelSummaryDTO getSummary(String viewModelCode) {
        Model viewModel = loadViewModel(viewModelCode);
        ViewModelConfig config = parseViewModelConfig(viewModel);

        List<ResolvedFieldDTO> fields = resolveViewFields(viewModelCode);

        return ViewModelSummaryDTO.builder()
                .code(viewModel.getCode())
                .displayName(viewModel.getDisplayName())
                .description(viewModel.getDescription())
                .mode(config.getMode())
                .baseEntityCode(config.getBaseEntityCode())
                .namedQueryCode(config.getNamedQueryCode())
                .fieldCount(fields.size())
                .status(viewModel.getStatus())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ViewModelValidationResult validateConfig(String viewModelCode) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Model viewModel;
        try {
            viewModel = loadViewModel(viewModelCode);
        } catch (MetaServiceException e) {
            return ViewModelValidationResult.failure(List.of(e.getMessage()));
        }

        ViewModelConfig config;
        try {
            config = parseViewModelConfig(viewModel);
        } catch (Exception e) {
            return ViewModelValidationResult.failure(List.of("Failed to parse viewModel config: " + e.getMessage()));
        }

        if (!config.isValid()) {
            errors.add("Invalid ViewModel config: mode=" + config.getMode());
        }

        // Validate based on mode
        if (config.isInheritMode()) {
            Model baseEntity = metaModelMapper.findCurrentByCode(config.getBaseEntityCode());
            if (baseEntity == null) {
                errors.add("Base entity not found: " + config.getBaseEntityCode());
            }
        } else if (config.isComposeMode() || config.isFreeMode()) {
            try {
                namedQueryService.findByCode(config.getNamedQueryCode());
            } catch (Exception e) {
                errors.add("Named query not found or invalid: " + config.getNamedQueryCode());
            }
        }

        // Check computed fields reference validity
        if (config.getComputedFields() != null) {
            for (Map.Entry<String, ComputedFieldOverride> entry : config.getComputedFields().entrySet()) {
                ComputedFieldOverride override = entry.getValue();
                if (override.isVirtual() && override.getExpression() == null) {
                    warnings.add("Virtual field '" + entry.getKey() + "' has no expression");
                }
            }
        }

        if (!errors.isEmpty()) {
            return ViewModelValidationResult.failure(errors);
        }
        if (!warnings.isEmpty()) {
            return ViewModelValidationResult.withWarnings(warnings);
        }
        return ViewModelValidationResult.success();
    }

    @Override
    @CacheEvict(value = {"viewModelFields", "viewModelSummary"}, allEntries = true)
    public void evictAllCache() {
        log.info("Evicting all ViewModel caches");
    }

    // ==================== Private Helper Methods ====================

    private Model loadViewModel(String viewModelCode) {
        validateModelCode(viewModelCode);
        Model model = metaModelMapper.findCurrentByCode(viewModelCode);
        if (model == null) {
            throw new MetaServiceException("ViewModel not found: " + viewModelCode);
        }
        if (!model.isViewType()) {
            throw new MetaServiceException("Model is not a VIEW type: " + viewModelCode);
        }
        return model;
    }

    private ViewModelConfig parseViewModelConfig(Model viewModel) {
        if (viewModel.getExtension() == null || viewModel.getExtension().getExtension() == null) {
            throw new MetaServiceException("ViewModel has no extension config: " + viewModel.getCode());
        }

        Object viewModelObj = viewModel.getExtension().getExtension().get("viewModel");
        if (viewModelObj == null) {
            throw new MetaServiceException("ViewModel extension missing 'viewModel' key: " + viewModel.getCode());
        }

        try {
            return objectMapper.convertValue(viewModelObj, ViewModelConfig.class);
        } catch (Exception e) {
            throw new MetaServiceException("Failed to parse ViewModelConfig for: " + viewModel.getCode() + ", error: " + e.getMessage());
        }
    }

    /**
     * Resolve fields for inherit mode:
     * Load base entity's field bindings → exclude specified fields → build ResolvedFieldDTO list
     */
    private List<ResolvedFieldDTO> resolveInheritFields(ViewModelConfig config) {
        String baseEntityCode = config.getBaseEntityCode();
        Model baseModel = metaModelMapper.findCurrentByCode(baseEntityCode);
        if (baseModel == null) {
            throw new MetaServiceException("Base entity not found: " + baseEntityCode);
        }

        // Load bindings for the base model
        List<ModelFieldBinding> bindings = fieldBindingMapper.findByModelId(baseModel.getId());

        // Get the exclude set
        Set<String> excludeSet = config.getExcludeFields() != null
                ? new HashSet<>(config.getExcludeFields())
                : Collections.emptySet();

        // Load all field entities by their IDs
        List<Long> fieldIds = bindings.stream()
                .map(ModelFieldBinding::getFieldId)
                .collect(Collectors.toList());

        Map<Long, Field> fieldMap = new HashMap<>();
        if (!fieldIds.isEmpty()) {
            List<Field> fields = metaFieldMapper.selectBatchIds(fieldIds);
            for (Field f : fields) {
                fieldMap.put(f.getId(), f);
            }
        }

        // Build resolved fields, skipping excluded ones
        List<ResolvedFieldDTO> result = new ArrayList<>();
        for (ModelFieldBinding binding : bindings) {
            Field field = fieldMap.get(binding.getFieldId());
            if (field == null) continue;
            if (excludeSet.contains(field.getCode())) continue;

            ResolvedFieldDTO dto = ResolvedFieldDTO.from(field, binding);
            result.add(dto);
        }

        return result;
    }

    /**
     * Resolve fields for compose/free mode:
     * Load named query's field definitions → build ResolvedFieldDTO list
     */
    private List<ResolvedFieldDTO> resolveQueryFields(ViewModelConfig config) {
        String queryCode = config.getNamedQueryCode();
        Long tenantId = getCurrentTenantId();

        List<NamedQueryField> queryFields = namedQueryFieldMapper.findByQueryCode(tenantId, queryCode);

        return queryFields.stream()
                .map(ResolvedFieldDTO::fromNamedQueryField)
                .collect(Collectors.toList());
    }

    /**
     * Apply Layer 3 computed field overrides:
     * - For existing fields: merge override properties
     * - For virtual-only fields: add new ResolvedFieldDTO entries
     */
    private void applyComputedOverrides(List<ResolvedFieldDTO> fields, ViewModelConfig config) {
        Map<String, ComputedFieldOverride> overrides = config.getComputedFields();
        if (overrides == null || overrides.isEmpty()) return;

        // Build a code→index map for existing fields
        Map<String, Integer> codeIndex = new HashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            codeIndex.put(fields.get(i).getCode(), i);
        }

        for (Map.Entry<String, ComputedFieldOverride> entry : overrides.entrySet()) {
            String code = entry.getKey();
            ComputedFieldOverride override = entry.getValue();

            Integer idx = codeIndex.get(code);
            if (idx != null) {
                // Existing field: merge override
                fields.get(idx).mergeOverride(override);
            } else {
                // New virtual field
                ResolvedFieldDTO virtualField = ResolvedFieldDTO.fromVirtual(code, override);
                fields.add(virtualField);
            }
        }
    }
}

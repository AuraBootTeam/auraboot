package com.auraboot.framework.aisearch.service;

import com.auraboot.framework.aisearch.dto.GlobalSearchCandidates;
import com.auraboot.framework.aisearch.dto.GlobalSearchPreference;
import com.auraboot.framework.aisearch.dto.GlobalSearchResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.user.dao.entity.UserPreference;
import com.auraboot.framework.user.mapper.UserPreferenceMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cross-model global search over models the caller may read.
 * <p>
 * Permission is the model-level read check identical to the dynamic list
 * endpoint ({@code model.<code>.read} via {@link UserPermissionService});
 * unauthorized models are dropped from the candidate set before any query, so
 * their existence is not disclosed. Keyword execution reuses
 * {@link DynamicDataService#list}, inheriting tenant isolation and the data
 * scope pipeline.
 *
 * @author AuraBoot Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalSearchServiceImpl implements GlobalSearchService {

    private static final Set<String> SKIP_MODEL_TYPES = Set.of("view", "meta");
    private static final int DEFAULT_PER_MODEL_LIMIT = 3;
    private static final int MAX_PER_MODEL_LIMIT = 10;
    private static final int DEFAULT_MAX_MODELS = 12;
    private static final int HARD_MAX_MODELS = 40;
    private static final String PREFERENCE_KEY = "search.global.enabled-models";
    private static final int MAX_SAVED_MODELS = 250;

    private final MetaModelMapper metaModelMapper;
    private final DynamicDataService dynamicDataService;
    private final UserPermissionService userPermissionService;
    private final UserPreferenceMapper userPreferenceMapper;

    @Override
    public List<Model> readableCandidateModels(Long userId) {
        if (userId == null) {
            throw new IllegalStateException("Global search requires an authenticated user");
        }
        return metaModelMapper.findCurrentByTenant().stream()
                .filter(this::isSearchableModelType)
                .filter(model -> userPermissionService.hasPermission(
                        userId, "model." + model.getCode() + ".read"))
                .toList();
    }

    @Override
    public GlobalSearchResult search(Long userId, String keyword, Integer perModelLimit, Integer maxModels) {
        return search(userId, null, keyword, perModelLimit, maxModels);
    }

    @Override
    public GlobalSearchResult search(
            Long userId, Long tenantId, String keyword, Integer perModelLimit, Integer maxModels) {
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("Global search keyword must not be blank");
        }
        int perModel = clamp(perModelLimit, DEFAULT_PER_MODEL_LIMIT, 1, MAX_PER_MODEL_LIMIT);
        int modelBudget = clamp(maxModels, DEFAULT_MAX_MODELS, 1, HARD_MAX_MODELS);

        List<GlobalSearchResult.Group> groups = new ArrayList<>();
        boolean truncated = false;
        for (Model model : configuredReadableModels(userId, tenantId)) {
            if (groups.size() >= modelBudget) {
                truncated = true;
                break;
            }
            // Metadata for a single model can be unqueryable (no searchable
            // fields, no backing table); one degraded model must not fail the
            // whole cross-model search, so it is skipped with a debug record.
            try {
                DynamicQueryRequest queryRequest = DynamicQueryRequest.builder()
                        .pageNum(1)
                        .pageSize(perModel)
                        .keyword(keyword.trim())
                        .build();
                PaginationResult<Map<String, Object>> result = dynamicDataService.list(model.getCode(), queryRequest);
                if (result.getRecords() == null || result.getRecords().isEmpty()) {
                    continue;
                }
                groups.add(GlobalSearchResult.Group.builder()
                        .modelCode(model.getCode())
                        .modelLabel(model.getDisplayName() != null ? model.getDisplayName() : model.getCode())
                        .total(result.getTotal())
                        .records(result.getRecords())
                        .build());
            } catch (Exception e) {
                log.debug("Global search skipped model {}: {}", model.getCode(), e.getMessage());
            }
        }

        return GlobalSearchResult.builder()
                .keyword(keyword.trim())
                .truncated(truncated)
                .groups(groups)
                .build();
    }

    @Override
    public GlobalSearchCandidates listCandidates(Long userId, Long tenantId) {
        List<Model> readable = readableCandidateModels(userId);
        GlobalSearchPreference preference = readPreference(tenantId, userId);
        Set<String> enabled = preference.getEnabledModelCodes() == null
                ? Set.of()
                : new HashSet<>(preference.getEnabledModelCodes());
        List<GlobalSearchCandidates.ModelCandidate> candidates = readable.stream()
                .map(model -> GlobalSearchCandidates.ModelCandidate.builder()
                        .modelCode(model.getCode())
                        .modelLabel(displayLabel(model))
                        .enabled(!preference.isConfigured() || enabled.contains(model.getCode()))
                        .build())
                .toList();
        return GlobalSearchCandidates.builder()
                .models(candidates)
                .preference(preference)
                .build();
    }

    @Override
    public GlobalSearchPreference getSearchPreference(Long userId, Long tenantId) {
        return readPreference(tenantId, userId);
    }

    @Override
    @Transactional
    public GlobalSearchPreference saveSearchPreference(Long userId, Long tenantId, List<String> modelCodes) {
        requireTenant(tenantId);
        if (userId == null) {
            throw new IllegalStateException("Global search requires an authenticated user");
        }
        if (modelCodes == null) {
            throw new IllegalArgumentException("Global search modelCodes must not be null");
        }
        if (modelCodes.size() > MAX_SAVED_MODELS) {
            throw new IllegalArgumentException("Global search preference exceeds " + MAX_SAVED_MODELS + " models");
        }

        Map<String, Model> readable = readableCandidateModels(userId).stream()
                .collect(Collectors.toMap(Model::getCode, Function.identity(), (left, right) -> left));
        Set<String> distinct = new HashSet<>();
        List<String> saved = new ArrayList<>();
        for (String code : modelCodes) {
            if (code == null || code.isBlank() || !distinct.add(code)) {
                continue;
            }
            if (!readable.containsKey(code)) {
                throw new IllegalArgumentException("Model is not readable or does not exist: " + code);
            }
            saved.add(code);
        }

        UserPreference existing = findPreference(tenantId, userId);
        ArrayNode value = JsonNodeFactory.instance.arrayNode();
        saved.forEach(value::add);
        if (existing == null) {
            UserPreference preference = new UserPreference();
            preference.setPid(com.auraboot.framework.common.util.UlidGenerator.generate());
            preference.setTenantId(tenantId);
            preference.setUserId(userId);
            preference.setPreferenceKey(PREFERENCE_KEY);
            preference.setPreferenceValue(value);
            userPreferenceMapper.insert(preference);
        } else {
            existing.setPreferenceValue(value);
            userPreferenceMapper.updateById(existing);
        }
        return GlobalSearchPreference.builder()
                .configured(true)
                .enabledModelCodes(List.copyOf(saved))
                .build();
    }

    private List<Model> configuredReadableModels(Long userId, Long tenantId) {
        List<Model> readable = readableCandidateModels(userId);
        GlobalSearchPreference preference = readPreference(tenantId, userId);
        if (!preference.isConfigured() || preference.getEnabledModelCodes() == null) {
            return readable;
        }

        Map<String, Model> readableByCode = readable.stream()
                .collect(Collectors.toMap(Model::getCode, Function.identity(), (left, right) -> left));
        return preference.getEnabledModelCodes().stream()
                .map(readableByCode::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private GlobalSearchPreference readPreference(Long tenantId, Long userId) {
        UserPreference preference = tenantId == null ? null : findPreference(tenantId, userId);
        if (preference == null
                || preference.getPreferenceValue() == null
                || !preference.getPreferenceValue().isArray()) {
            return GlobalSearchPreference.builder()
                    .configured(false)
                    .enabledModelCodes(List.of())
                    .build();
        }
        List<String> codes = ((ArrayNode) preference.getPreferenceValue())
                .valueStream()
                .filter(TextNode.class::isInstance)
                .map(TextNode.class::cast)
                .map(TextNode::textValue)
                .filter(code -> code != null && !code.isBlank())
                .toList();
        return GlobalSearchPreference.builder()
                .configured(true)
                .enabledModelCodes(List.copyOf(codes))
                .build();
    }

    private UserPreference findPreference(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return null;
        }
        return userPreferenceMapper.selectOne(new QueryWrapper<UserPreference>()
                .eq("tenant_id", tenantId)
                .eq("user_id", userId)
                .eq("preference_key", PREFERENCE_KEY));
    }

    private String displayLabel(Model model) {
        return model.getDisplayName() != null ? model.getDisplayName() : model.getCode();
    }

    private void requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalStateException("Global search requires an active tenant context");
        }
    }
    private boolean isSearchableModelType(Model model) {
        String modelType = model.getModelType();
        return modelType == null || !SKIP_MODEL_TYPES.contains(modelType.toLowerCase());
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }
}

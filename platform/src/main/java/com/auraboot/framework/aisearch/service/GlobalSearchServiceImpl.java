package com.auraboot.framework.aisearch.service;

import com.auraboot.framework.aisearch.dto.GlobalSearchResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final MetaModelMapper metaModelMapper;
    private final DynamicDataService dynamicDataService;
    private final UserPermissionService userPermissionService;

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
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("Global search keyword must not be blank");
        }
        int perModel = clamp(perModelLimit, DEFAULT_PER_MODEL_LIMIT, 1, MAX_PER_MODEL_LIMIT);
        int modelBudget = clamp(maxModels, DEFAULT_MAX_MODELS, 1, HARD_MAX_MODELS);

        List<GlobalSearchResult.Group> groups = new ArrayList<>();
        boolean truncated = false;
        for (Model model : readableCandidateModels(userId)) {
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

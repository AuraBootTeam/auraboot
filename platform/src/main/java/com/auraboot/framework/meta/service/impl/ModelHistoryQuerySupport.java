package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.base.BaseMetaService;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.exception.MetaServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/** Reads model history, comparison, statistics and search results. */
@Slf4j
@RequiredArgsConstructor
final class ModelHistoryQuerySupport extends BaseMetaService {
    private final MetaModelMapper metaModelMapper;

    private final IsCodeUniqueOperation isCodeUniqueOperation;

    private final ConvertToMetaModelDTOOperation1 convertToMetaModelDTOOperation1;

    private final ConvertToMetaModelDTOOperation2 convertToMetaModelDTOOperation2;

    private final LoadUserFieldCountsByModelIdOperation loadUserFieldCountsByModelIdOperation;

    @FunctionalInterface
    interface IsCodeUniqueOperation { boolean execute(String code, String excludePid); }

    @FunctionalInterface
    interface ConvertToMetaModelDTOOperation1 { MetaModelDTO execute(Model model); }

    @FunctionalInterface
    interface ConvertToMetaModelDTOOperation2 { MetaModelDTO execute(Model model, Integer fieldCount); }

    @FunctionalInterface
    interface LoadUserFieldCountsByModelIdOperation { Map<Long, Integer> execute(List<Model> models); }

    private boolean isCodeUnique(String code, String excludePid) { return isCodeUniqueOperation.execute(code,excludePid); }

    private MetaModelDTO convertToMetaModelDTO(Model model) { return convertToMetaModelDTOOperation1.execute(model); }

    private MetaModelDTO convertToMetaModelDTO(Model model, Integer fieldCount) { return convertToMetaModelDTOOperation2.execute(model,fieldCount); }

    private Map<Long, Integer> loadUserFieldCountsByModelId(List<Model> models) { return loadUserFieldCountsByModelIdOperation.execute(models); }

    List<MetaModelDTO> getVersionHistory(String code){
        log.info("获取模型版本历史: code={}", logSafe(code));

        // 查询所有版本
        List<Model> versions = metaModelMapper.findAllVersionsByCode(code);

        // 转换为DTO
        return versions.stream()
                .map(this::convertToMetaModelDTO)
                .collect(Collectors.toList());
    }

    MetaModelDTO getVersionDetail(String code, Integer version){
        log.info("获取模型版本详情: code={}, version={}", logSafe(code), version);

        Model model = metaModelMapper.findByCodeAndVersion(code, version);
        if (model == null) {
            throw new MetaServiceException("模型版本不存在: code=" + code + ", version=" + version);
        }

        return convertToMetaModelDTO(model);
    }

    Map<String, Object> compareVersions(String code, Integer v1, Integer v2){
        log.info("对比模型版本: code={}, v1={}, v2={}", logSafe(code), v1, v2);

        // 获取两个版本的模型
        Model model1 = metaModelMapper.findByCodeAndVersion(code, v1);
        Model model2 = metaModelMapper.findByCodeAndVersion(code, v2);

        if (model1 == null || model2 == null) {
            throw new MetaServiceException("版本不存在");
        }

        // 构建差异对象
        Map<String, Object> diff = new HashMap<>();
        diff.put("code", code);
        diff.put("v1", v1);
        diff.put("v2", v2);

        // 对比基本信息
        List<Map<String, Object>> changes = new ArrayList<>();

        // 对比显示名称
        if (!Objects.equals(model1.getDisplayName(), model2.getDisplayName())) {
            changes.add(Map.of(
                "field", "displayName",
                "oldValue", model1.getDisplayName() != null ? model1.getDisplayName() : "",
                "newValue", model2.getDisplayName() != null ? model2.getDisplayName() : ""
            ));
        }

        // 对比描述
        if (!Objects.equals(model1.getDescription(), model2.getDescription())) {
            changes.add(Map.of(
                "field", "description",
                "oldValue", model1.getDescription() != null ? model1.getDescription() : "",
                "newValue", model2.getDescription() != null ? model2.getDescription() : ""
            ));
        }

        // 对比模型类型
        if (!Objects.equals(model1.getModelType(), model2.getModelType())) {
            changes.add(Map.of(
                "field", "modelType",
                "oldValue", model1.getModelType() != null ? model1.getModelType() : "",
                "newValue", model2.getModelType() != null ? model2.getModelType() : ""
            ));
        }

        // 对比状态
        if (!Objects.equals(model1.getStatus(), model2.getStatus())) {
            changes.add(Map.of(
                "field", "status",
                "oldValue", model1.getStatus() != null ? model1.getStatus() : "",
                "newValue", model2.getStatus() != null ? model2.getStatus() : ""
            ));
        }

        diff.put("changes", changes);
        diff.put("hasChanges", !changes.isEmpty());

        return diff;
    }

    Map<String, Object> getStatistics(){
        log.info("获取模型统计信息");

        // 查询所有当前版本的模型
        List<Model> currentModels = metaModelMapper.findCurrentByTenant();

        // 统计总数
        long totalModels = currentModels.size();

        // 按状态统计
        Map<String, Long> byStatus = currentModels.stream()
                .collect(Collectors.groupingBy(
                    m -> m.getStatus() != null ? m.getStatus() : "unknown",
                    Collectors.counting()
                ));

        // 按类型统计
        Map<String, Long> byType = currentModels.stream()
                .collect(Collectors.groupingBy(
                    m -> m.getModelType() != null ? m.getModelType() : "unknown",
                    Collectors.counting()
                ));

        // 统计活跃模型（已发布状态）
        long activeModels = currentModels.stream()
                .filter(m -> StatusConstants.PUBLISHED.equals(m.getStatus()))
                .count();

        Map<String, Object> statistics = new HashMap<>();
        statistics.put("totalModels", totalModels);
        statistics.put("activeModels", activeModels);
        statistics.put("modelsByStatus", byStatus);
        statistics.put("modelsByType", byType);
        statistics.put("timestamp", Instant.now().toString());

        return statistics;
    }

    PageResult<MetaModelDTO> searchModels(
            Integer page, Integer size, String keyword, String code, String displayName,
            String modelType, String status, String sourceType, String sortField, String sortOrder, Boolean currentOnly){

        log.info(
                "分页查询模型列表: page={}, size={}, keyword={}, code={}, displayName={}, modelType={}, status={}, sourceType={}, sortField={}, sortOrder={}",
                page, size, logSafe(keyword), logSafe(code), logSafe(displayName), logSafe(modelType),
                logSafe(status), logSafe(sourceType), logSafe(sortField), logSafe(sortOrder)
        );

        // Validate and set defaults
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 20;
        if (size > 1000) size = 1000; // Max size limit
        if (currentOnly == null) currentOnly = true;

        // If keyword is provided, use it for generic search; otherwise use specific filters
        String searchKeyword = keyword;
        if (searchKeyword == null || searchKeyword.trim().isEmpty()) {
            // Build keyword from code or displayName if provided
            if (code != null && !code.trim().isEmpty()) {
                searchKeyword = code;
            } else if (displayName != null && !displayName.trim().isEmpty()) {
                searchKeyword = displayName;
            }
        }

        // Calculate offset
        long offset = (long) (page - 1) * size;

        // Get total count
        long total = metaModelMapper.countByKeyword(
                searchKeyword, modelType, status, sourceType, currentOnly
        );

        // Get page data
        List<Model> models = metaModelMapper.searchByKeyword(
                searchKeyword, modelType, status, sourceType, sortField, sortOrder, currentOnly, offset, size
        );

        Map<Long, Integer> fieldCountsByModelId = loadUserFieldCountsByModelId(models);

        // Convert to DTOs
        List<MetaModelDTO> dtos = models.stream()
                .map(model -> convertToMetaModelDTO(
                        model,
                        model.getId() != null ? fieldCountsByModelId.getOrDefault(model.getId(), 0) : 0
                ))
                .collect(Collectors.toList());

        // Build page result
        PageResult<MetaModelDTO> result = new PageResult<>();
        result.setRecords(dtos);
        result.setPageInfo(total, (long) size, (long) page);

        log.info("模型列表查询完成: total={}, page={}, size={}", total, page, size);
        return result;
    }

    Map<String, Object> validateModelData(Map<String, Object> modelData){
        log.info("验证模型数据");

        Map<String, Object> result = new HashMap<>();
        Map<String, String> errors = new HashMap<>();

        // 验证必填字段
        if (!modelData.containsKey("code") || modelData.get("code") == null ||
            modelData.get("code").toString().trim().isEmpty()) {
            errors.put("code", "模型编码不能为空");
        }

        if (!modelData.containsKey("displayName") || modelData.get("displayName") == null ||
            modelData.get("displayName").toString().trim().isEmpty()) {
            errors.put("displayName", "显示名称不能为空");
        }

        // 验证编码格式（只能包含字母、数字和下划线）
        if (modelData.containsKey("code") && modelData.get("code") != null) {
            String code = modelData.get("code").toString();
            if (!code.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
                errors.put("code", "模型编码格式不正确，必须以字母开头，只能包含字母、数字和下划线");
            }
        }

        // 验证编码唯一性
        if (modelData.containsKey("code") && modelData.get("code") != null) {
            String code = modelData.get("code").toString();
            String excludePid = modelData.containsKey("pid") ? modelData.get("pid").toString() : null;

            if (!isCodeUnique(code, excludePid)) {
                errors.put("code", "模型编码已存在: " + code);
            }
        }

        // 验证模型类型
        if (modelData.containsKey("modelType") && modelData.get("modelType") != null) {
            String modelType = modelData.get("modelType").toString();
            List<String> validTypes = Arrays.asList("entity", "view", "aggregate", "value_object");
            if (!validTypes.contains(modelType)) {
                errors.put("modelType", "无效的模型类型: " + modelType);
            }
        }

        result.put("valid", errors.isEmpty());
        result.put("errors", errors);

        return result;
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}

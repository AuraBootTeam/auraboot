package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.DDLPreviewResult;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.MetaModelUpdateRequest;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.dto.SchemaSyncOptions;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelExportService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * 模型管理控制器
 * 提供模型的CRUD操作和元数据管理功能
 * 
 * Git-First架构：
 * - 模型属于核心层资源，影响运行语义
 * - Service层会根据Git-First路由决定是否走Git流程
 * - 查询操作可以直接执行
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/models")
@RequiredArgsConstructor
@Validated
@Tag(name = "模型管理", description = "模型定义的增删改查和元数据管理")
public class ModelController {

    private final MetaModelService metaModelService;
    private final PageSchemaService pageSchemaService;
    private final PluginResourceTracker pluginResourceTracker;
    private final ModelExportService modelExportService;
    private final SchemaManagementService schemaManagementService;

    // ==================== 基础CRUD操作 ====================

    @PostMapping
    @Operation(summary = "创建模型", description = "创建新的模型定义 ")
//    @RequirePermission(MetaPermissions.MODEL_CREATE) todo
    public ApiResponse<MetaModelDTO> createModel(
            @Valid @RequestBody MetaModelCreateRequest request) {
        log.info("创建模型: code={}, displayName={}", request.getCode(), request.getDisplayName());
        
        // 设置租户信息
        if (request.getTenantId() == null) {
            request.setTenantId(MetaContext.getCurrentTenantId());
        }
        
        // 检查模型编码唯一性
        if (!metaModelService.isCodeUnique(request.getCode(), null)) {
            return ApiResponse.error("模型编码已存在: " + request.getCode());
        }
        
        // 创建模型
        MetaModelDTO result = metaModelService.create(request);

        // Virtual-model wizard payload carries sourceType/sourceRef/primaryKey/
        // capabilities/fields/extension that are not persisted by the legacy
        // create() path. Route them through saveDefinition() so the model row
        // actually stores the declared source, capabilities, and primaryKey.
        boolean hasVirtualPayload =
                (request.getSourceType() != null && !request.getSourceType().isBlank()
                        && !"physical".equalsIgnoreCase(request.getSourceType()))
                || request.getSourceRef() != null
                || request.getPrimaryKey() != null
                || request.getCapabilities() != null
                || (request.getFields() != null && !request.getFields().isEmpty());
        if (hasVirtualPayload) {
            ModelDefinition def = ModelDefinition.builder()
                    .code(result.getCode())
                    .displayName(request.getDisplayName())
                    .description(request.getDescription())
                    .modelType(request.getModelType())
                    .modelCategory(request.getModelCategory())
                    .tableName(request.getTableName())
                    .sourceType(request.getSourceType())
                    .sourceRef(request.getSourceRef())
                    .primaryKey(request.getPrimaryKey())
                    .capabilities(request.getCapabilities())
                    .fields(request.getFields())
                    .extension(request.getExtension())
                    .build();
            metaModelService.saveDefinition(def);
            // Reload to pick up persisted source/capabilities/primaryKey.
            MetaModelDTO reloaded = metaModelService.findByPid(result.getPid());
            if (reloaded != null) {
                result = reloaded;
            }
        }

        log.info("模型创建成功: pid={}, code={}", result.getPid(), result.getCode());
        return ApiResponse.success("模型创建成功", result);
    }

    @GetMapping("/{pid}")
    @Operation(summary = "获取模型详情", description = "根据PID获取模型的详细信息")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<MetaModelDTO> getModel(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid) {
        log.info("获取模型详情: pid={}", pid);
        
        MetaModelDTO model = metaModelService.findByPid(pid);
        if (model == null) {
            return ApiResponse.error("模型不存在: " + pid);
        }
        
        return ApiResponse.success(model);
    }

    @PutMapping("/{pid}")
    @Operation(summary = "更新模型", description = "更新指定模型的信息 ")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<MetaModelDTO> updateModel(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody MetaModelUpdateRequest request) {
        log.info("更新模型: pid={}, displayName={}", pid, request.getDisplayName());
        
        // 检查模型是否存在
        MetaModelDTO existingModel = metaModelService.findByPid(pid);
        if (existingModel == null) {
            return ApiResponse.error("模型不存在: " + pid);
        }

        // Update via saveDefinition() — the legacy create() path would throw
        // "Model code already exists" because the model row is already there.
        // saveDefinition() looks up by code and routes to UPDATE for existing rows,
        // preserving sourceType/sourceRef/capabilities/primaryKey.
        Map<String, Object> mergedExtension = new java.util.LinkedHashMap<>();
        if (request.getExtension() != null) {
            mergedExtension.putAll(request.getExtension());
        }
        // displayName / description / modelType are stored as extension keys (Model entity getters read from extension)
        if (request.getDisplayName() != null) {
            mergedExtension.put("displayName", request.getDisplayName());
        }
        if (request.getDescription() != null) {
            mergedExtension.put("description", request.getDescription());
        }
        String effectiveModelType = request.getModelType() != null ? request.getModelType() : existingModel.getModelType();
        if (effectiveModelType != null) {
            mergedExtension.put("modelType", effectiveModelType);
        }

        ModelDefinition updateDef = ModelDefinition.builder()
                .code(existingModel.getCode())
                .displayName(request.getDisplayName())
                .description(request.getDescription())
                .modelType(effectiveModelType)
                .extension(mergedExtension)
                .build();
        metaModelService.saveDefinition(updateDef);

        // Reload by pid so the response reflects the freshly persisted row
        // (sourceType/sourceRef/extension/displayName).
        MetaModelDTO result = metaModelService.findByPid(pid);
        pluginResourceTracker.markAsUserModified(ResourceType.MODEL, existingModel.getCode());

        log.info("模型更新成功: pid={}, version={}", pid, result != null ? result.getVersion() : null);
        return ApiResponse.success("模型更新成功", result);
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "删除模型", description = "删除指定的模型定义 ")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<Void> deleteModel(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid) {
        log.info("删除模型: pid={}", pid);
        
        // 检查模型是否存在
        MetaModelDTO existingModel = metaModelService.findByPid(pid);
        if (existingModel == null) {
            return ApiResponse.error("模型不存在: " + pid);
        }
        
        // 删除模型
        pluginResourceTracker.markAsUserModified(ResourceType.MODEL, existingModel.getCode());
        metaModelService.delete(pid);

        log.info("模型删除成功: pid={}", pid);
        return ApiResponse.<Void>success("模型删除成功", null);
    }

    @GetMapping
    @Operation(summary = "查询模型列表", description = "分页查询模型列表，支持多种过滤条件")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<PageResult<MetaModelDTO>> listModels(
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "20") Integer size,
            @Parameter(description = "关键词（搜索code、displayName、description）") @RequestParam(required = false) String keyword,
            @Parameter(description = "模型编码（模糊查询）") @RequestParam(required = false) String code,
            @Parameter(description = "显示名称（模糊查询）") @RequestParam(required = false) String displayName,
            @Parameter(description = "模型类型") @RequestParam(required = false) String modelType,
            @Parameter(description = "状态") @RequestParam(required = false) String status,

            @Parameter(description = "是否只查询当前版本") @RequestParam(defaultValue = "true") Boolean currentOnly) {

        log.info("查询模型列表: page={}, size={}, keyword={}, code={}, displayName={}, modelType={}, status={}",
                page, size, keyword, code, displayName, modelType, status);

        PageResult<MetaModelDTO> result = metaModelService.searchModels(
                page, size, keyword, code, displayName, modelType, status,   currentOnly);

        log.info("模型列表查询完成: total={}", result.getTotal());
        return ApiResponse.success(result);
    }

    // ==================== 元数据操作 ====================

    @GetMapping("/{pid}/exists")
    @Operation(summary = "检查模型是否存在", description = "检查指定PID的模型是否存在")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Boolean> checkModelExists(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid) {
        log.info("检查模型是否存在: pid={}", pid);
        
        MetaModelDTO model = metaModelService.findByPid(pid);
        boolean exists = model != null;
        
        return ApiResponse.success(exists);
    }

    @GetMapping("/code/{code}/unique")
    @Operation(summary = "检查模型编码唯一性", description = "检查模型编码是否唯一")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Boolean> checkCodeUnique(
            @Parameter(description = "模型编码") @PathVariable @NotBlank String code,
            @Parameter(description = "排除的PID（用于更新时检查）") @RequestParam(required = false) String excludePid) {
        log.info("检查模型编码唯一性: code={}, excludePid={}", code, excludePid);
        
        boolean isUnique = metaModelService.isCodeUnique(code, excludePid);
        
        return ApiResponse.success(isUnique);
    }

    @PostMapping("/{pid}/refresh-cache")
    @Operation(summary = "刷新模型缓存", description = "刷新指定模型的元数据缓存")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<Void> refreshModelCache(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid) {
        log.info("刷新模型缓存: pid={}", pid);
        
        // 先获取模型信息以获取模型编码
        MetaModelDTO model = metaModelService.findByPid(pid);
        if (model == null) {
            return ApiResponse.error("模型不存在: " + pid);
        }
        
        // 刷新缓存
        metaModelService.refreshModelCache(model.getCode());
        
        log.info("模型缓存刷新成功: pid={}, code={}", pid, model.getCode());
        return ApiResponse.<Void>success("模型缓存刷新成功", null);
    }

    @PostMapping("/cache/clear")
    @Operation(summary = "清除所有模型缓存", description = "清除所有模型的元数据缓存")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<Void> clearAllCache() {
        log.info("清除所有模型缓存");
        
        metaModelService.clearAllCache();
        
        log.info("所有模型缓存清除成功");
        return ApiResponse.<Void>success("所有模型缓存清除成功", null);
    }

    // ==================== 版本管理操作 ====================

    @GetMapping("/code/{code}/versions")
    @Operation(summary = "获取模型版本历史", description = "获取指定模型的所有版本历史")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<List<MetaModelDTO>> getVersionHistory(
            @Parameter(description = "模型编码") @PathVariable @NotBlank String code) {
        log.info("获取模型版本历史: code={}", code);
        
        List<MetaModelDTO> versions = metaModelService.getVersionHistory(code);
        
        log.info("模型版本历史查询完成: code={}, count={}", code, versions.size());
        return ApiResponse.success(versions);
    }

    @GetMapping("/code/{code}/versions/{version}")
    @Operation(summary = "获取指定版本的模型详情", description = "根据编码和版本号获取模型的详细信息")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<MetaModelDTO> getVersionDetail(
            @Parameter(description = "模型编码") @PathVariable @NotBlank String code,
            @Parameter(description = "版本号") @PathVariable Integer version) {
        log.info("获取模型版本详情: code={}, version={}", code, version);
        
        MetaModelDTO model = metaModelService.getVersionDetail(code, version);
        
        log.info("模型版本详情查询完成: code={}, version={}", code, version);
        return ApiResponse.success(model);
    }

    @PostMapping("/code/{code}/versions/compare")
    @Operation(summary = "对比两个版本", description = "对比指定模型的两个版本之间的差异")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Map<String, Object>> compareVersions(
            @Parameter(description = "模型编码") @PathVariable @NotBlank String code,
            @Parameter(description = "版本1和版本2") @RequestBody Map<String, Integer> request) {
        Integer v1 = request.get("v1");
        Integer v2 = request.get("v2");
        log.info("对比模型版本: code={}, v1={}, v2={}", code, v1, v2);

        Map<String, Object> diff = metaModelService.compareVersions(code, v1, v2);
        
        log.info("模型版本对比完成: code={}", code);
        return ApiResponse.success(diff);
    }

    @PostMapping("/code/{code}/rollback")
    @Operation(summary = "回滚到指定版本", description = "将模型回滚到指定的历史版本")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<MetaModelDTO> rollbackToVersion(
            @Parameter(description = "模型编码") @PathVariable @NotBlank String code,
            @Parameter(description = "目标版本号") @RequestBody Map<String, Integer> request) {
        Integer version = request.get("version");
        log.info("回滚模型版本: code={}, targetVersion={}", code, version);

        try {
            MetaModelDTO result = metaModelService.rollbackToVersion(code, version);
            log.info("模型版本回滚成功: code={}, version={}", code, version);
            return ApiResponse.success("版本回滚成功", result);
        } catch (IllegalArgumentException e) {
            log.error("回滚失败: {}", e.getMessage());
            return ApiResponse.error(e.getMessage());
        } catch (Exception e) {
            log.error("回滚失败: code={}, version={}, error={}", code, version, e.getMessage(), e);
            return ApiResponse.error("回滚失败: " + e.getMessage());
        }
    }

    // ==================== 批量操作 ====================

    @PostMapping("/batch-delete")
    @Operation(summary = "批量删除模型", description = "批量删除多个模型定义")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<Map<String, Object>> batchDelete(
            @Parameter(description = "模型PID列表") @RequestBody Map<String, List<String>> request) {
        List<String> pids = request.get("pids");
        log.info("批量删除模型: count={}", pids != null ? pids.size() : 0);

        if (pids == null || pids.isEmpty()) {
            return ApiResponse.error("PID列表不能为空");
        }
        
        int successCount = 0;
        int failureCount = 0;
        List<String> failedPids = new java.util.ArrayList<>();
        
        for (String pid : pids) {
            try {
                metaModelService.delete(pid);
                successCount++;
            } catch (Exception e) {
                log.error("删除模型失败: pid={}, error={}", pid, e.getMessage());
                failureCount++;
                failedPids.add(pid);
            }
        }
        
        Map<String, Object> result = Map.of(
            "total", pids.size(),
            "success", successCount,
            "failure", failureCount,
            "failedPids", failedPids
        );
        
        log.info("批量删除模型完成: total={}, success={}, failure={}", 
                pids.size(), successCount, failureCount);
        return ApiResponse.success("批量删除完成", result);
    }

    // ==================== 导入导出操作 ====================

    @PostMapping("/export")
    @Operation(summary = "导出模型", description = "导出模型的DSL定义")
    @RequirePermission(MetaPermission.MODEL_READ)
    @SuppressWarnings("unchecked")
    public ApiResponse<Map<String, Object>> exportModels(
            @RequestBody Map<String, Object> exportRequest) {
        log.info("导出模型: request={}", exportRequest);

        Object raw = exportRequest.get("modelCodes");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return ApiResponse.error("modelCodes不能为空");
        }
        List<String> modelCodes = (List<String>) list;

        Map<String, Object> result = new java.util.LinkedHashMap<>(
                modelExportService.exportByModelCodes(modelCodes));
        result.put("exportTime", java.time.Instant.now().toString());
        try {
            result.put("exportedBy", MetaContext.getCurrentUserId());
        } catch (IllegalStateException e) {
            result.put("exportedBy", null);
        }

        log.info("模型导出完成: modelCodes={}", modelCodes);
        return ApiResponse.success(result);
    }

    // ==================== 统计信息 ====================

    @GetMapping("/statistics")
    @Operation(summary = "获取模型统计信息", description = "获取模型的使用统计信息")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Map<String, Object>> getStatistics() {
        log.info("获取模型统计信息");
        
        Map<String, Object> statistics = metaModelService.getStatistics();
        
        log.info("模型统计信息查询完成");
        return ApiResponse.success(statistics);
    }

    // ==================== 数据验证 ====================

    @PostMapping("/validate")
    @Operation(summary = "验证模型数据", description = "验证模型创建或更新请求的数据完整性")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Map<String, Object>> validateModel(
            @RequestBody Map<String, Object> modelData) {
        log.info("验证模型数据");
        
        Map<String, Object> result = metaModelService.validateModelData(modelData);
        
        log.info("模型数据验证完成");
        return ApiResponse.success(result);
    }

    // ==================== Release信息 ====================

    @GetMapping("/{pid}/release")
    @Operation(summary = "获取模型的Release信息", description = "获取模型关联的Git Release信息")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<Map<String, Object>> getReleaseInfo(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid) {
        log.info("获取模型Release信息: pid={}", pid);
        
        MetaModelDTO model = metaModelService.findByPid(pid);
        if (model == null) {
            return ApiResponse.error("模型不存在: " + pid);
        }
        
        // TODO: 实现Release信息查询
        // 暂时返回基本信息
        Map<String, Object> releaseInfo = Map.of(
            "pid", pid,
            "code", model.getCode(),
            "version", model.getVersion() != null ? model.getVersion() : 0,
            "status", model.getStatus() != null ? model.getStatus() : "unknown"
        );
        
        log.info("模型Release信息查询完成: pid={}", pid);
        return ApiResponse.success(releaseInfo);
    }

    // ==================== Publish/Unpublish ====================

    @PostMapping("/{pid}/publish")
    @Operation(summary = "发布模型", description = "发布模型，创建对应的数据库表")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<MetaModelDTO> publishModel(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid,
            @Parameter(description = "版本说明") @RequestParam(required = false) String versionNote) {
        log.info("发布模型: pid={}, versionNote={}", pid, versionNote);

        MetaModelDTO result = metaModelService.publish(pid, versionNote);
        return ApiResponse.success(result);
    }

    @PostMapping("/{pid}/unpublish")
    @Operation(summary = "取消发布模型", description = "取消模型的发布状态，不删除数据库表")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<MetaModelDTO> unpublishModel(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid) {
        log.info("取消发布模型: pid={}", pid);

        MetaModelDTO result = metaModelService.unpublish(pid);
        return ApiResponse.success(result);
    }

    @PostMapping("/{pid}/sync-schema")
    @Operation(summary = "同步模型表结构", description = "根据当前模型定义同步数据库表结构，可用于修复已发布模型缺表或缺列")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<SchemaOperationResult> syncModelSchema(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid) {
        log.info("同步模型表结构: pid={}", pid);

        MetaModelDTO model = metaModelService.findByPid(pid);
        if (model == null) {
            return ApiResponse.error("模型不存在: " + pid);
        }

        SchemaOperationResult result = schemaManagementService.syncModelToTable(
                model.getCode(),
                SchemaSyncOptions.builder()
                        .syncMode(SchemaSyncOptions.SyncMode.SAFE)
                        .createIndexes(true)
                        .build());
        if (!result.isSuccess()) {
            return ApiResponse.error("模型表结构同步失败: " + result.getErrorMessage());
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{pid}/publish/preview")
    @Operation(summary = "预览发布DDL", description = "预览发布模型时将要执行的DDL语句")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<DDLPreviewResult> previewPublishDDL(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid) {
        log.info("预览模型发布DDL: pid={}", pid);

        DDLPreviewResult result = metaModelService.previewPublishDDL(pid);
        return ApiResponse.success(result);
    }

    // ==================== 关联数据查询 ====================
    
    // 注意：/{pid}/fields 端点已由 ModelFieldBindingController 提供
    // 请使用 ModelFieldBindingController.getModelFields() 获取模型字段列表

    @GetMapping("/{pid}/pages")
    @Operation(summary = "获取模型关联的页面列表", description = "获取使用该模型的所有页面")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<List<PageSchemaDTO>> getRelatedPages(
            @Parameter(description = "模型PID") @PathVariable @NotBlank String pid) {
        log.info("获取模型关联页面: pid={}", pid);

        MetaModelDTO model = metaModelService.findByPid(pid);
        if (model == null) {
            return ApiResponse.error("模型不存在: " + pid);
        }

        // 根据模型编码查询关联页面
        List<PageSchemaDTO> pages = pageSchemaService.findByModelCode(model.getCode());

        log.info("模型关联页面查询完成: pid={}, modelCode={}, count={}", pid, model.getCode(), pages.size());
        return ApiResponse.success(pages);
    }

    @GetMapping("/code/{code}")
    @Operation(summary = "根据编码获取模型详情", description = "根据模型编码获取模型的详细信息")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<MetaModelDTO> getModelByCode(
            @Parameter(description = "模型编码") @PathVariable @NotBlank String code) {
        log.info("根据编码获取模型详情: code={}", code);
        
        MetaModelDTO model = metaModelService.findByCode(code);
        
        log.info("根据编码查询模型完成: code={}", code);
        return ApiResponse.success(model);
    }
}

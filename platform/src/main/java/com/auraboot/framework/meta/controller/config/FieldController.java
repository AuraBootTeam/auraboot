package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.MetaFieldService;
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
import java.util.Optional;

/**
 * 字段管理控制器
 * 提供字段定义的CRUD操作和元数据管理功能
 * 
 * Git-First架构：
 * - 字段属于核心层资源，影响运行语义
 * - 所有创建/更新/删除操作需要通过Git流程
 * - 查询操作可以直接执行
 *
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/fields")
@RequiredArgsConstructor
@Validated
@Tag(name = "字段管理", description = "字段定义的增删改查和元数据管理")
public class FieldController {

    private final MetaFieldService metaFieldService;
    private final PluginResourceTracker pluginResourceTracker;

    // ==================== 基础CRUD操作 ====================

    @PostMapping
    @Operation(summary = "创建字段", description = "创建新的字段定义 ")
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<MetaFieldDTO> createField(
            @Valid @RequestBody MetaFieldCreateRequest request) {
        log.info("创建字段: code={}, dataType={}", request.getCode(), request.getDataType());

        // 检查字段键唯一性
        if (!metaFieldService.isCodeUnique(request.getCode(), null)) {
            return ApiResponse.failure("字段键已存在: " + request.getCode());
        }
        
        // 创建字段
        MetaFieldDTO result = metaFieldService.create(request);
        
        log.info("字段创建成功: pid={}, code={}", result.getPid(), result.getCode());
        return ApiResponse.success("字段创建成功", result);
    }

    @GetMapping("/{pid}")
    @Operation(summary = "获取字段详情", description = "根据PID获取字段的详细信息")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<MetaFieldDTO> getField(
            @Parameter(description = "字段PID") @PathVariable @NotBlank String pid) {
        log.info("获取字段详情: pid={}", pid);
        
        MetaFieldDTO field = metaFieldService.findByPid(pid);
        if (field == null) {
            return ApiResponse.failure("字段不存在: " + pid);
        }
        
        return ApiResponse.success(field);
    }

    @PutMapping("/{pid}")
    @Operation(summary = "更新字段", description = "更新指定字段的信息 ")
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<MetaFieldDTO> updateField(
            @Parameter(description = "字段PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody MetaFieldUpdateRequest request) {
        log.info("更新字段: pid={}, dataType={}", pid, request.getDataType());
        
        // 检查字段是否存在
        MetaFieldDTO existingField = metaFieldService.findByPid(pid);
        if (existingField == null) {
            return ApiResponse.failure("字段不存在: " + pid);
        }
        
        // 更新字段
        MetaFieldDTO result = metaFieldService.update(pid, request);
        pluginResourceTracker.markAsUserModified(ResourceType.FIELD, existingField.getCode());

        log.info("字段更新成功: pid={}, newVersion={}", pid, result.getVersion());
        return ApiResponse.success("字段更新成功", result);
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "删除字段", description = "删除指定的字段定义 ")
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<Void> deleteField(
            @Parameter(description = "字段PID") @PathVariable @NotBlank String pid) {
        log.info("删除字段: pid={}", pid);
        
        // 检查字段是否存在
        MetaFieldDTO existingField = metaFieldService.findByPid(pid);
        if (existingField == null) {
            return ApiResponse.failure("字段不存在: " + pid);
        }
        
        // 删除字段
        pluginResourceTracker.markAsUserModified(ResourceType.FIELD, existingField.getCode());
        metaFieldService.delete(pid);

        log.info("字段删除成功: pid={}", pid);
        return ApiResponse.<Void>success("字段删除成功", null);
    }

    @GetMapping
    @Operation(summary = "查询字段列表", description = "分页查询字段列表，支持多种过滤条件")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<PageResult<MetaFieldDTO>> listFields(
            @Parameter(description = "页码，从1开始") @RequestParam(defaultValue = "1") Integer page,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "20") Integer size,
            @Parameter(description = "字段键（模糊查询）") @RequestParam(required = false) String code,
            @Parameter(description = "数据类型") @RequestParam(required = false) String dataType,
            @Parameter(description = "状态") @RequestParam(required = false) String status,

            @Parameter(description = "是否只查询当前版本") @RequestParam(defaultValue = "true") Boolean currentOnly) {
        
        log.info("查询字段列表: page={}, size={}, code={}, dataType={}, status={}", 
                page, size, code, dataType, status);
        
        PageResult<MetaFieldDTO> result = metaFieldService.listFields(
            page, size, code, dataType, status,   currentOnly);
        
        log.info("字段列表查询完成: total={}", result.getTotal());
        return ApiResponse.success(result);
    }

    // ==================== 字段查询 ====================

    @GetMapping("/key/{code}")
    @Operation(summary = "根据字段键获取当前版本字段", description = "根据字段键获取当前版本的字段信息")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<MetaFieldDTO> getFieldByKey(
            @Parameter(description = "字段键") @PathVariable @NotBlank String code) {
        log.info("根据字段键获取字段: code={}", code);
        
        Optional<MetaFieldDTO> field = metaFieldService.findCurrentByCode(code);
        if (field.isEmpty()) {
            return ApiResponse.failure("字段不存在: " + code);
        }
        
        return ApiResponse.success(field.get());
    }

    @GetMapping("/key/{code}/versions")
    @Operation(summary = "获取字段的所有版本", description = "获取指定字段键的所有版本信息")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<MetaFieldDTO>> getFieldVersions(
            @Parameter(description = "字段键") @PathVariable @NotBlank String code) {
        log.info("获取字段的所有版本: code={}", code);
        
        List<MetaFieldDTO> versions = metaFieldService.findAllVersionsByCode(code);
        
        return ApiResponse.success(versions);
    }

    @GetMapping("/key/{code}/version/{version}")
    @Operation(summary = "获取字段的指定版本", description = "获取字段的指定版本信息")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<MetaFieldDTO> getFieldByVersion(
            @Parameter(description = "字段键") @PathVariable @NotBlank String code,
            @Parameter(description = "版本号") @PathVariable Integer version) {
        log.info("获取字段的指定版本: code={}, version={}", code, version);
        
        Optional<MetaFieldDTO> field = metaFieldService.findByCodeAndVersion(code, version);
        if (field.isEmpty()) {
            return ApiResponse.failure("字段版本不存在: " + code + " v" + version);
        }
        
        return ApiResponse.success(field.get());
    }

    @GetMapping("/dataType/{dataType}")
    @Operation(summary = "根据数据类型查询字段", description = "根据数据类型查询字段列表")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<MetaFieldDTO>> getFieldsByDataType(
            @Parameter(description = "数据类型") @PathVariable @NotBlank String dataType) {
        log.info("根据数据类型查询字段: dataType={}", dataType);
        
        List<MetaFieldDTO> fields = metaFieldService.findByDataType(dataType);
        
        return ApiResponse.success(fields);
    }

    @GetMapping("/dataSource/{dataSourceId}")
    @Operation(summary = "根据数据源查询字段", description = "根据数据源ID查询字段列表")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<MetaFieldDTO>> getFieldsByDataSource(
            @Parameter(description = "数据源ID") @PathVariable Long dataSourceId) {
        log.info("根据数据源查询字段: dataSourceId={}", dataSourceId);
        
        List<MetaFieldDTO> fields = metaFieldService.findByDataSource(dataSourceId);
        
        return ApiResponse.success(fields);
    }

    // ==================== 字段验证 ====================

    @GetMapping("/{pid}/exists")
    @Operation(summary = "检查字段是否存在", description = "检查指定PID的字段是否存在")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<Boolean> checkFieldExists(
            @Parameter(description = "字段PID") @PathVariable @NotBlank String pid) {
        log.info("检查字段是否存在: pid={}", pid);
        
        MetaFieldDTO field = metaFieldService.findByPid(pid);
        boolean exists = field != null;
        
        return ApiResponse.success(exists);
    }

    @GetMapping("/key/{code}/unique")
    @Operation(summary = "检查字段键唯一性", description = "检查字段键是否唯一")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<Boolean> checkCodeUnique(
            @Parameter(description = "字段键") @PathVariable @NotBlank String code,
            @Parameter(description = "排除的PID（用于更新时检查）") @RequestParam(required = false) String excludePid) {
        log.info("检查字段键唯一性: code={}, excludePid={}", code, excludePid);
        
        boolean isUnique = metaFieldService.isCodeUnique(code, excludePid);
        
        return ApiResponse.success(isUnique);
    }

    @PostMapping("/key/{code}/validate")
    @Operation(summary = "验证字段定义", description = "验证字段定义的完整性和正确性")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<MetaFieldValidationResult> validateField(
            @Parameter(description = "字段键") @PathVariable @NotBlank String code) {
        log.info("验证字段定义: code={}", code);
        
        MetaFieldValidationResult result = metaFieldService.validateField(code);
        
        return ApiResponse.success(result);
    }

    // ==================== 字典绑定 ====================

    @PostMapping("/{fieldId}/bind-dict")
    @Operation(summary = "绑定字典", description = "将字典绑定到字段")
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<Void> bindDictionary(
            @Parameter(description = "字段PID") @PathVariable @NotBlank String fieldId,
            @Valid @RequestBody DictBindRequest request) {
        log.info("绑定字典到字段: fieldId={}, dictCode={}", fieldId, request.getDictCode());
        
        // 检查字段是否存在
        MetaFieldDTO field = metaFieldService.findByPid(fieldId);
        if (field == null) {
            return ApiResponse.failure("字段不存在: " + fieldId);
        }
        
        // 绑定字典
        boolean success = metaFieldService.bindDictionary(fieldId, request.getDictCode());
        if (!success) {
            return ApiResponse.failure("绑定字典失败");
        }
        
        log.info("字典绑定成功: fieldId={}, dictCode={}", fieldId, request.getDictCode());
        return ApiResponse.<Void>success("字典绑定成功", null);
    }

    @DeleteMapping("/{fieldId}/unbind-dict")
    @Operation(summary = "解绑字典", description = "解除字段与字典的绑定关系")
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<Void> unbindDictionary(
            @Parameter(description = "字段PID") @PathVariable @NotBlank String fieldId) {
        log.info("解绑字段的字典: fieldId={}", fieldId);
        
        // 检查字段是否存在
        MetaFieldDTO field = metaFieldService.findByPid(fieldId);
        if (field == null) {
            return ApiResponse.failure("字段不存在: " + fieldId);
        }
        
        // 解绑字典
        boolean success = metaFieldService.unbindDictionary(fieldId);
        if (!success) {
            return ApiResponse.failure("解绑字典失败");
        }
        
        log.info("字典解绑成功: fieldId={}", fieldId);
        return ApiResponse.<Void>success("字典解绑成功", null);
    }

    @GetMapping("/{fieldId}/bound-dict")
    @Operation(summary = "获取绑定的字典", description = "获取字段绑定的字典信息")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<DictDTO> getBoundDictionary(
            @Parameter(description = "字段PID") @PathVariable @NotBlank String fieldId) {
        log.info("获取字段绑定的字典: fieldId={}", fieldId);
        
        Optional<DictDTO> dict = metaFieldService.getBoundDictionary(fieldId);
        if (dict.isEmpty()) {
            return ApiResponse.failure("字段未绑定字典: " + fieldId);
        }
        
        return ApiResponse.success(dict.get());
    }

    // ==================== 版本管理 ====================

    @PostMapping("/{pid}/publish")
    @Operation(summary = "发布字段版本", description = "发布指定的字段版本")
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<MetaFieldDTO> publishVersion(
            @Parameter(description = "字段PID") @PathVariable @NotBlank String pid) {
        log.info("发布字段版本: pid={}", pid);
        
        MetaFieldDTO result = metaFieldService.publishVersion(pid);
        
        log.info("字段版本发布成功: pid={}, version={}", pid, result.getVersion());
        return ApiResponse.success("字段版本发布成功", result);
    }

    @PostMapping("/key/{code}/rollback/{version}")
    @Operation(summary = "回滚字段版本", description = "将字段回滚到指定版本")
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<MetaFieldDTO> rollbackToVersion(
            @Parameter(description = "字段键") @PathVariable @NotBlank String code,
            @Parameter(description = "目标版本") @PathVariable Integer version) {
        log.info("回滚字段版本: code={}, version={}", code, version);
        
        MetaFieldDTO result = metaFieldService.rollbackToVersion(code, version);
        
        log.info("字段版本回滚成功: code={}, version={}", code, version);
        return ApiResponse.success("字段版本回滚成功", result);
    }

    // ==================== 缓存管理 ====================

    @PostMapping("/key/{code}/cache/refresh")
    @Operation(summary = "刷新字段缓存", description = "刷新指定字段的元数据缓存")
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<Void> refreshFieldCache(
            @Parameter(description = "字段键") @PathVariable @NotBlank String code) {
        log.info("刷新字段缓存: code={}", code);
        
        metaFieldService.refreshFieldCache(code);
        
        log.info("字段缓存刷新成功: code={}", code);
        return ApiResponse.<Void>success("字段缓存刷新成功", null);
    }

    @PostMapping("/cache/clear")
    @Operation(summary = "清除所有字段缓存", description = "清除所有字段的元数据缓存")
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<Void> clearAllFieldCache() {
        log.info("清除所有字段缓存");
        
        metaFieldService.clearAllFieldCache();
        
        log.info("所有字段缓存清除成功");
        return ApiResponse.<Void>success("所有字段缓存清除成功", null);
    }
}
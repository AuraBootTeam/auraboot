package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.auraboot.framework.meta.service.DictVersionService;
import com.auraboot.framework.meta.service.DictCascadeService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

/**
 * 字典管理控制器
 * 提供字典的CRUD操作、版本管理和级联查询功能
 * 
 * Git-First架构：
 * - 字典定义（DICT）属于核心层资源，影响运行语义，必须通过Git流程
 * - 字典项（DICT_ITEM）属于配置层资源，允许在线管理
 * - 查询操作可以直接执行
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/dict")
@RequiredArgsConstructor
@Validated
@Tag(name = "字典管理", description = "字典数据的增删改查、版本管理和级联查询")
public class DictController {

    private final DictService dictService;
    private final DictVersionService dictVersionService;
    private final DictCascadeService dictCascadeService;
    private final PluginResourceTracker pluginResourceTracker;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    // ==================== 基础CRUD操作 ====================

    @PostMapping
    @Operation(summary = "创建字典", description = "创建新的字典定义 ")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<DictDTO> createDict(
            @Valid @RequestBody DictCreateRequest request) {
        log.info("创建字典: code={}, name={}", logSafe(request.getCode()), logSafe(request.getName()));
        
        DictDTO result = dictService.create(request);
        return ApiResponse.success(result);
    }

    @PutMapping("/{pid}")
    @Operation(summary = "更新字典", description = "更新字典信息 ")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<DictDTO> updateDict(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody DictUpdateRequest request) {
        log.info("更新字典: pid={}", logSafe(pid));
        
        DictDTO result = dictService.update(pid, request);
        pluginResourceTracker.markAsUserModified(ResourceType.DICT, result.getCode());
        return ApiResponse.success(result);
    }

    @PutMapping("/{pid}/items")
    @Operation(summary = "替换字典项", description = "一次性替换字典下的全部字典项")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<DictDTO> replaceDictItems(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid,
            @Valid @RequestBody List<DictCreateRequest.DictItemCreateRequest> items) {
        log.info("替换字典项: pid={}, count={}", logSafe(pid), items != null ? items.size() : 0);

        DictDTO result = dictService.replaceItems(pid, items);
        pluginResourceTracker.markAsUserModified(ResourceType.DICT, result.getCode());
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{pid}")
    @Operation(summary = "删除字典", description = "删除字典 ")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<Void> deleteDict(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid) {
        log.info("删除字典: pid={}", logSafe(pid));
        DictDTO existing = dictService.findByPid(pid);
        if (existing != null) {
            pluginResourceTracker.markAsUserModified(ResourceType.DICT, existing.getCode());
        }
        dictService.delete(pid);
        return ApiResponse.success();
    }

    @GetMapping("/{pid}")
    @Operation(summary = "获取字典详情", description = "根据PID获取字典详细信息")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<DictDTO> getDictByPid(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid) {
        log.info("获取字典详情: pid={}", logSafe(pid));
        
        DictDTO result = dictService.findByPid(pid);
        if (result == null) {
            return ApiResponse.failure("字典不存在: " + pid);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/by-code/{code}")
    @Operation(summary = "根据编码获取字典", description = "根据字典编码获取当前版本的字典")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<DictDTO> getDictByCode(
            @Parameter(description = "字典编码") @PathVariable @NotBlank String code) {
        log.info("根据编码获取字典: code={}", logSafe(code));

        DictDTO result = dictService.findByCode(code);
        if (result == null) {
            return ApiResponse.failure("字典不存在: " + code);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/by-code/{code}/data")
    @Operation(summary = "根据编码加载字典数据", description = "根据字典编码加载字典数据（用于表单下拉框）")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<DictDataResult> loadDictDataByCode(
            @Parameter(description = "字典编码") @PathVariable @NotBlank String code,
            @Parameter(description = "版本策略") @RequestParam(defaultValue = "latest") String versionStrategy,
            @Parameter(description = "固定版本号") @RequestParam(required = false) String pinnedVersion) {
        log.info("根据编码加载字典数据: code={}, strategy={}, version={}",
                logSafe(code), logSafe(versionStrategy), logSafe(pinnedVersion));

        // 验证字典存在
        DictDTO dict = dictService.findByCode(code);
        if (dict == null) {
            return ApiResponse.failure("字典不存在: " + code);
        }

        // 加载数据
        DictDataResult result = dictService.loadDictData(code, versionStrategy, pinnedVersion);
        return ApiResponse.success(result);
    }



    @GetMapping
    @Operation(summary = "分页查询字典", description = "分页查询字典列表")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<PageResult<DictDTO>> queryDicts(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "页面大小") @RequestParam(defaultValue = "20") Integer pageSize,
            @Parameter(description = "字典编码") @RequestParam(required = false) String code,
            @Parameter(description = "字典名称") @RequestParam(required = false) String name,
            @Parameter(description = "字典类型") @RequestParam(required = false) String dictType,
            @Parameter(description = "状态") @RequestParam(required = false) String status
             
            ) {
        log.info("分页查询字典: pageNum={}, pageSize={}, code={}", pageNum, pageSize, logSafe(code));
        
        DictQueryRequest request = new DictQueryRequest();
        request.setPageNum(pageNum);
        request.setPageSize(pageSize);
        request.setCode(code);
        request.setName(name);
        request.setDictType(dictType);
        request.setStatus(status);

        Page<DictDTO> result = dictService.findPage(request);
        
        // 转换为PageResult
        PageResult<DictDTO> pageResult = new PageResult<>();
        pageResult.setRecords(result.getRecords());
        pageResult.setTotal(result.getTotal());
        pageResult.setSize(result.getSize());
        pageResult.setCurrent(result.getCurrent());
        pageResult.setPages(result.getPages());
        
        return ApiResponse.success(pageResult);
    }

    // ==================== 字典数据加载 ====================

    @GetMapping("/{pid}/data")
    @Operation(summary = "加载字典数据", description = "根据版本策略加载字典数据")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<DictDataResult> loadDictData(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid,
            @Parameter(description = "版本策略") @RequestParam(defaultValue = "latest") String versionStrategy,
            @Parameter(description = "固定版本号") @RequestParam(required = false) String pinnedVersion) {
        log.info("加载字典数据: pid={}, strategy={}, version={}",
                logSafe(pid), logSafe(versionStrategy), logSafe(pinnedVersion));
        
        // 通过 PID 获取字典
        DictDTO dict = dictService.findByPid(pid);
        if (dict == null) {
            return ApiResponse.failure("字典不存在: " + pid);
        }
        
        // 使用 code 加载数据
        DictDataResult result = dictService.loadDictData(dict.getCode(), versionStrategy, pinnedVersion);
        return ApiResponse.success(result);
    }


    @PostMapping("/data/batch")
    @Operation(summary = "批量加载字典数据", description = "批量根据版本策略加载字典数据")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<List<DictDataResult>> batchLoadDictData(
            @Valid @RequestBody List<DictLoadRequest> requests) {
        log.info("批量加载字典数据: count={}", requests.size());
        
        List<DictDataResult> results = dictService.batchLoadDictData(requests);
        return ApiResponse.success(results);
    }

    // ==================== 版本管理 ====================

    @PostMapping("/{pid}/publish")
    @Operation(summary = "发布字典", description = "发布字典到指定环境")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<DictDTO> publishDict(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid,
            @Parameter(description = "版本说明") @RequestParam(required = false) String versionNote) {
        log.info("发布字典: pid={}, versionNote={}", logSafe(pid), logSafe(versionNote));
        
        DictDTO result = dictService.publish(pid, versionNote);
        return ApiResponse.success(result);
    }

    @PostMapping("/{pid}/unpublish")
    @Operation(summary = "取消发布字典", description = "取消字典的发布状态")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<DictDTO> unpublishDict(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid) {
        log.info("取消发布字典: pid={}", logSafe(pid));
        
        DictDTO result = dictService.unpublish(pid);
        return ApiResponse.success(result);
    }

    @PostMapping("/{pid}/version")
    @Operation(summary = "创建字典版本", description = "创建字典的新版本")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<DictDTO> createDictVersion(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid,
            @Parameter(description = "版本说明") @RequestParam(required = false) String versionNote) {
        log.info("创建字典版本: pid={}, versionNote={}", logSafe(pid), logSafe(versionNote));
        
        DictDTO result = dictService.createVersion(pid, versionNote);
        return ApiResponse.success(result);
    }

    @GetMapping("/{code}/versions")
    @Operation(summary = "获取字典版本历史", description = "获取字典的版本历史列表")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<List<DictDTO>> getDictVersionHistory(
            @Parameter(description = "字典编码") @PathVariable @NotBlank String code
          ) {
        log.info("获取字典版本历史: code={}", logSafe(code));
        
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DictDTO> result = dictService.getVersionHistory(code);
        return ApiResponse.success(result);
    }

    // ==================== 级联字典 ====================

    @GetMapping("/{pid}/cascade/children")
    @Operation(summary = "获取级联字典子项", description = "获取级联字典的子项列表")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<List<DictItemData>> getCascadeChildren(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid,
            @Parameter(description = "父级值") @RequestParam(required = false) String parentValue) {
        log.info("获取级联字典子项: pid={}, parentValue={}", logSafe(pid), logSafe(parentValue));
        
        List<DictItemData> result = dictService.getCascadeChildren(pid, parentValue);
        return ApiResponse.success(result);
    }

    @GetMapping("/{pid}/cascade/tree")
    @Operation(summary = "构建级联字典树", description = "构建完整的级联字典树结构")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<DictTreeNode> buildCascadeTree(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String pid) {
        log.info("构建级联字典树: pid={}", logSafe(pid));
        
        DictTreeNode result = dictService.buildCascadeTree(pid);
        return ApiResponse.success(result);
    }

    @PostMapping("/cascade/query")
    @Operation(summary = "查询级联字典", description = "根据条件查询级联字典数据")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<CascadeDictResult> queryCascadeDict(
            @Valid @RequestBody CascadeDictRequest request) {
        log.info("查询级联字典: dictCode={}", logSafe(request.getDictCode()));
        
        CascadeDictResult result = dictCascadeService.queryCascadeDict(request);
        return ApiResponse.success(result);
    }

    // ==================== 字典导入导出 ====================

    @PostMapping("/import")
    @Operation(summary = "导入字典", description = "从JSON数据导入字典")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<DictImportResult> importDict(
            @Valid @RequestBody List<DictCreateRequest> dictData
             
            ) {
        log.info("导入字典: count={}", dictData != null ? dictData.size() : 0);
        
        Long tenantId = MetaContext.getCurrentTenantId();
        DictImportResult result = dictService.importDicts(   dictData);
        return ApiResponse.success(result);
    }

    @GetMapping("/export")
    @Operation(summary = "导出字典", description = "导出字典数据为JSON格式")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<DictExportResult> exportDict(
            @Parameter(description = "字典编码列表") @RequestParam(required = false) List<String> codes
             
            ,
            @Parameter(description = "是否包含字典项") @RequestParam(defaultValue = "true") Boolean includeItems) {
        log.info("导出字典: codes={}, includeItems={}", logSafe(codes), includeItems);
        
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DictDTO> dicts = dictService.exportDicts(   codes);
        
        // 构建导出结果
        DictExportResult result = new DictExportResult();
        result.setDicts(dicts);
        result.setDictCount(dicts.size());
        result.setFormat("json");
        result.setIncludeItems(includeItems);
        result.setSuccess();
        
        return ApiResponse.success(result);
    }

    // ==================== 统计和验证 ====================

    @GetMapping("/statistics")
    @Operation(summary = "获取字典统计信息", description = "获取字典的统计信息")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<DictStatistics> getDictStatistics(
             
            ) {
        log.info("获取字典统计信息");
        
        Long tenantId = MetaContext.getCurrentTenantId();
        DictStatistics result = dictService.getStatistics(  );
        return ApiResponse.success(result);
    }

    @GetMapping("/{code}/validate")
    @Operation(summary = "验证字典配置", description = "验证字典配置的正确性")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<DictValidationResult> validateDictConfig(
            @Parameter(description = "字典PID") @PathVariable @NotBlank String code) {
        log.info("验证字典配置: code={}", logSafe(code));
        
        DictValidationResult result = dictService.validateConfig(code);
        return ApiResponse.success(result);
    }

    @GetMapping("/code/{code}/unique")
    @Operation(summary = "检查字典编码唯一性", description = "检查字典编码是否唯一")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<Boolean> checkCodeUnique(
            @Parameter(description = "字典编码") @PathVariable @NotBlank String code,
            @Parameter(description = "排除的PID") @RequestParam(required = false) String excludePid
             
            ) {
        log.info("检查字典编码唯一性: code={}, excludePid={}", logSafe(code), logSafe(excludePid));
        
        Long tenantId = MetaContext.getCurrentTenantId();
        boolean result = dictService.isCodeUnique(   code, excludePid);
        return ApiResponse.success(result);
    }

    // ==================== 批量操作 ====================

    @PostMapping("/batch")
    @Operation(summary = "批量创建字典", description = "批量创建字典")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<List<DictDTO>> batchCreateDicts(
            @Valid @RequestBody List<DictCreateRequest> requests) {
        log.info("批量创建字典: count={}", requests.size());
        
        List<DictDTO> results = dictService.batchCreate(requests);
        return ApiResponse.success(results);
    }

    @PutMapping("/batch/status")
    @Operation(summary = "批量更新字典状态", description = "批量更新字典状态")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<Integer> batchUpdateStatus(
            @Parameter(description = "字典PID列表") @RequestParam List<String> pids,
            @Parameter(description = "新状态") @RequestParam @NotBlank String status) {
        log.info("批量更新字典状态: pids={}, status={}", logSafe(pids), logSafe(status));
        
        int result = dictService.batchUpdateStatus(pids, status);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/batch")
    @Operation(summary = "批量删除字典", description = "批量删除字典")
    @RequirePermission(MetaPermission.DICT_MANAGE)
    public ApiResponse<Integer> batchDeleteDicts(
            @Parameter(description = "字典PID列表") @RequestParam List<String> pids) {
        log.info("批量删除字典: pids={}", logSafe(pids));
        
        int result = dictService.batchDelete(pids);
        return ApiResponse.success(result);
    }

    // ==================== 查询操作 ====================

    @GetMapping("/tenant")
    @Operation(summary = "获取租户字典列表", description = "获取当前租户下的所有字典")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<List<DictDTO>> getTenantDicts(
             
            ) {
        log.info("获取租户字典列表");
        
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DictDTO> result = dictService.findByTenant(  );
        return ApiResponse.success(result);
    }

    @GetMapping("/status/{status}")
    @Operation(summary = "根据状态查询字典", description = "根据状态查询字典列表")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<List<DictDTO>> getDictsByStatus(
            @Parameter(description = "状态") @PathVariable @NotBlank String status
             
            ) {
        log.info("根据状态查询字典: status={}", logSafe(status));
        
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DictDTO> result = dictService.findByStatus(   status);
        return ApiResponse.success(result);
    }

    @GetMapping("/type/{dictType}")
    @Operation(summary = "根据类型查询字典", description = "根据类型查询字典列表")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<List<DictDTO>> getDictsByType(
            @Parameter(description = "字典类型") @PathVariable @NotBlank String dictType
             
            ) {
        log.info("根据类型查询字典: dictType={}", logSafe(dictType));
        
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DictDTO> result = dictService.findByType(   dictType);
        return ApiResponse.success(result);
    }

    @GetMapping("/search")
    @Operation(summary = "搜索字典", description = "根据关键词搜索字典")
    @RequirePermission(MetaPermission.DICT_READ)
    public ApiResponse<List<DictDTO>> searchDicts(
            @Parameter(description = "关键词") @RequestParam @NotBlank String keyword
             
            ) {
        log.info("搜索字典: keyword={}", logSafe(keyword));
        
        Long tenantId = MetaContext.getCurrentTenantId();
        List<DictDTO> result = dictService.search(   keyword);
        return ApiResponse.success(result);
    }
}

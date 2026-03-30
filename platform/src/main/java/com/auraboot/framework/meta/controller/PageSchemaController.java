package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaListDTO;
import com.auraboot.framework.meta.dto.PageSchemaSyncVersionDTO;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import com.auraboot.framework.meta.dto.PageSchemaVersionComparisonDTO;
import com.auraboot.framework.meta.dto.PageSchemaVersionCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaVersionDTO;
import com.auraboot.framework.meta.dto.PaginationRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.meta.service.PageSchemaVersionService;
import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 页面配置管理控制器
 * 提供页面配置的 CRUD 操作和版本管理功能
 * 
 * 注意: 页面属于应用层资源，支持草稿编辑，发布时走 Git-First 流程
 *
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/pages")
@Tag(name = "页面配置管理", description = "页面配置的增删改查和版本管理接口")
public class PageSchemaController {

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private PageSchemaVersionService pageSchemaVersionService;

    @Autowired
    private PluginResourceTracker pluginResourceTracker;

    /**
     * 分页查询页面配置列表
     *
     * @param kind 页面类型
     * @param isTemplate 是否为模板
     * @param isPublished 是否已发布 (maps to status=PUBLISHED/DRAFT internally)
     * @param keyword 关键词
     * @param request 分页请求参数
     * @return 分页结果（使用 PageSchemaListDTO，不包含 dslSchema 以提升性能）
     */
    @GetMapping
    @Operation(summary = "分页查询页面配置", description = "根据条件分页查询页面配置列表（不包含 dslSchema）")
    @RequirePermission("page.page.read")
    public ApiResponse<PaginationResult<PageSchemaListDTO>> list(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Boolean isTemplate,
            @RequestParam(required = false) Boolean isPublished,
            @RequestParam(required = false) String keyword,
            @Parameter(description = "分页请求参数") @Valid PaginationRequest request) {
        log.info("分页查询页面配置: kind={}, isTemplate={}, isPublished={}, keyword={}, request={}",
                kind, isTemplate, isPublished, keyword, request);
        // isPublished param is kept for API compatibility; internally maps to status filter
        PaginationResult<PageSchemaListDTO> result = pageSchemaService.findPageWithConditions(
                kind, isTemplate, isPublished, keyword, request);
        return ApiResponse.success(result);
    }

    /**
     * 根据PID查询页面配置详情
     *
     * @param pid 页面配置PID
     * @return 页面配置详情
     */
    @GetMapping("/{pid}")
    @Operation(summary = "查询页面配置详情", description = "根据PID查询页面配置的详细信息")
    @RequirePermission("page.page.read")
    public ApiResponse<PageSchemaDTO> getByPid(
            @Parameter(description = "页面配置PID") @PathVariable String pid) {
        log.info("查询页面配置详情，PID：{}", pid);
        PageSchemaDTO result = pageSchemaService.findByPid(pid);
        return ApiResponse.success(result);
    }

    /**
     * 查询指定实体和类型的最新版本页面配置
     *
     * @param entityCode 实体编码
     * @param type 页面类型
     * @return 最新版本的页面配置
     */
    @GetMapping("/latest/{pagePid}")
    @Operation(summary = "查询最新版本", description = "根据页面PID查询最新版本的页面配置")
    @RequirePermission("page.page.read")
    public ApiResponse<PageSchemaVersionDTO> getLatestVersion(
            @Parameter(description = "页面PID") @PathVariable String pagePid) {
        log.info("查询最新版本页面配置: pagePid={}", pagePid);
        PageSchemaVersionDTO result = pageSchemaVersionService.getLatestVersion(pagePid);
        return ApiResponse.success(result);
    }

    /**
     * 创建页面配置（草稿）
     * 应用层资源允许在线创建草稿，发布时走 Git-First 流程
     *
     * @param request 创建请求参数
     * @param userId 当前用户ID
     * @return 创建的页面配置
     */
    @PostMapping
    @Operation(summary = "创建页面配置", description = "创建新的页面配置（草稿状态）")
    @RequirePermission("page.page.manage")
    public ApiResponse<PageSchemaDTO> create(
            @Parameter(description = "创建请求参数") @Valid @RequestBody PageSchemaCreateRequest request,
            @Parameter(hidden = true) @CurrentUserId Long userId) {
        log.info("创建页面配置，参数：{}，用户ID：{}", request, userId);
        PageSchemaDTO result = pageSchemaService.create(request);
        return ApiResponse.success(result);
    }

    /**
     * 更新页面配置（草稿）
     * 应用层资源允许在线更新草稿
     *
     * @param pid 页面配置PID
     * @param request 更新请求参数
     * @param userId 当前用户ID
     * @return 更新后的页面配置
     */
    @PutMapping("/{pid}")
    @Operation(summary = "更新页面配置", description = "根据PID更新页面配置信息（草稿状态）")
    @RequirePermission("page.page.manage")
    public ApiResponse<PageSchemaDTO> update(
            @Parameter(description = "页面配置PID") @PathVariable String pid,
            @Parameter(description = "更新请求参数") @Valid @RequestBody PageSchemaUpdateRequest request,
            @Parameter(hidden = true) @CurrentUserId Long userId) {
        log.info("更新页面配置，PID：{}，参数：{}，用户ID：{}", pid, request, userId);
        PageSchemaDTO result = pageSchemaService.update(pid, request);
        pluginResourceTracker.markAsUserModified(ResourceType.PAGE, result.getPageKey());
        return ApiResponse.success(result);
    }

    /**
     * 发布页面配置
     * 发布时必须走 Git-First 流程
     *
     * @param pid 页面配置PID
     * @param userId 当前用户ID
     * @return 发布结果
     */
    @PostMapping("/{pid}/publish")
    @Operation(summary = "发布页面配置", description = "将页面配置发布为正式版本（Git-First流程）")
    @RequirePermission("page.page.manage")
    public ApiResponse<PageSchemaDTO> publish(
            @Parameter(description = "页面配置PID") @PathVariable String pid,
            @Parameter(hidden = true) @CurrentUserId Long userId) {
        log.info("发布页面配置，PID：{}，用户ID：{}", pid, userId);
        PageSchemaDTO result = pageSchemaService.publish(pid);
        return ApiResponse.success(result);
    }

    /**
     * 取消发布页面配置
     *
     * @param pid 页面配置PID
     * @param userId 当前用户ID
     * @return 取消发布结果
     */
    @PostMapping("/{pid}/unpublish")
    @Operation(summary = "取消发布页面配置", description = "取消页面配置的发布状态")
    @RequirePermission("page.page.manage")
    public ApiResponse<PageSchemaDTO> unpublish(
            @Parameter(description = "页面配置PID") @PathVariable String pid,
            @Parameter(hidden = true) @CurrentUserId Long userId) {
        log.info("取消发布页面配置，PID：{}，用户ID：{}", pid, userId);
        PageSchemaDTO result = pageSchemaService.unpublish(pid);
        return ApiResponse.success(result);
    }

    /**
     * 删除页面配置（软删除）
     *
     * @param pid 页面配置PID
     * @param userId 当前用户ID
     * @return 删除结果
     */
    @DeleteMapping("/{pid}")
    @Operation(summary = "删除页面配置", description = "软删除指定的页面配置")
    @RequirePermission("page.page.manage")
    public ApiResponse<Void> delete(
            @Parameter(description = "页面配置PID") @PathVariable String pid,
            @Parameter(hidden = true) @CurrentUserId Long userId) {
        log.info("删除页面配置，PID：{}，用户ID：{}", pid, userId);
        PageSchemaDTO existing = pageSchemaService.findByPid(pid);
        if (existing != null) {
            pluginResourceTracker.markAsUserModified(ResourceType.PAGE, existing.getPageKey());
        }
        pageSchemaService.delete(pid);
        return ApiResponse.success();
    }

    /**
     * 查询页面配置的版本历史
     *
     * @param pid 页面配置PID
     * @return 版本历史列表
     */
    @GetMapping("/{pid}/versions")
    @Operation(summary = "查询版本历史", description = "查询页面配置的版本历史记录")
    @RequirePermission("page.page.read")
    public ApiResponse<List<PageSchemaVersionDTO>> getVersionHistory(
            @Parameter(description = "页面配置PID") @PathVariable String pid) {
        log.info("查询版本历史: pid={}", pid);
        List<PageSchemaVersionDTO> result = pageSchemaVersionService.getVersionHistory(pid);
        return ApiResponse.success(result);
    }

    /**
     * 创建页面版本
     *
     * @param pid 页面配置PID
     * @param request 创建版本请求
     * @param currentUserId 当前用户ID
     * @return 创建的版本信息
     */
    @PostMapping("/{pid}/versions")
    @Operation(summary = "创建页面版本", description = "为页面配置创建新的版本记录")
    @RequirePermission("page.page.manage")
    public ApiResponse<PageSchemaVersionDTO> createVersion(
            @Parameter(description = "页面配置PID") @PathVariable String pid,
            @Parameter(description = "创建版本请求") @RequestBody PageSchemaVersionCreateRequest request,
            @CurrentUserId String currentUserId) {
        log.info("创建页面版本: pid={}, request={}, userId={}", pid, request, currentUserId);
        String operation = request.getOperation() != null ? request.getOperation() : "update";
        String description = request.getDescription() != null ? request.getDescription() : "Version created";
        PageSchemaVersionDTO result = pageSchemaVersionService.createVersion(pid, operation, currentUserId, description);
        return ApiResponse.success(result);
    }

    /**
     * 版本回滚
     *
     * @param pid 页面配置PID
     * @param historyId 历史版本ID
     * @param reason 回滚原因
     * @param userId 当前用户ID
     * @return 回滚后的页面配置版本
     */
    @PostMapping("/{pid}/rollback/{historyId}")
    @Operation(summary = "版本回滚", description = "将页面配置回滚到指定版本")
    @RequirePermission("page.page.manage")
    public ApiResponse<PageSchemaVersionDTO> rollbackToVersion(
            @Parameter(description = "页面配置PID") @PathVariable String pid,
            @Parameter(description = "历史版本ID") @PathVariable Long historyId,
            @Parameter(description = "回滚原因") @RequestParam String reason,
            @CurrentUserId String currentUserId) {
        log.info("版本回滚: pid={}, historyId={}, reason={}, userId={}", pid, historyId, reason, currentUserId);
        PageSchemaVersionDTO result = pageSchemaVersionService.rollbackToVersion(pid, historyId, currentUserId, reason);
        return ApiResponse.success(result);
    }

    /**
     * 比较两个版本的差异
     *
     * @param pid 页面配置PID
     * @param fromHistoryId 源版本历史ID
     * @param toHistoryId 目标版本历史ID
     * @return 版本差异信息
     */
    @GetMapping("/{pid}/versions/{fromHistoryId}/compare/{toHistoryId}")
    @Operation(summary = "比较版本差异", description = "比较两个版本之间的差异")
    @RequirePermission("page.page.read")
    public ApiResponse<PageSchemaVersionComparisonDTO> compareVersions(
            @Parameter(description = "页面配置PID") @PathVariable String pid,
            @Parameter(description = "源版本历史ID") @PathVariable Long fromHistoryId,
            @Parameter(description = "目标版本历史ID") @PathVariable Long toHistoryId) {
        log.info("比较页面配置版本差异: pid={}, fromHistoryId={}, toHistoryId={}", pid, fromHistoryId, toHistoryId);
        PageSchemaVersionComparisonDTO result = pageSchemaVersionService.compareVersions(fromHistoryId, toHistoryId);
        return ApiResponse.success(result);
    }

    /**
     * 根据页面名称查询配置
     *
     * @param name 页面名称
     * @return 页面配置
     */
    @GetMapping("/by-name")
    @Operation(summary = "根据名称查询", description = "根据页面名称查询页面配置")
    @RequirePermission("page.page.read")
    public ApiResponse<PageSchemaDTO> findByName(
            @Parameter(description = "页面名称") @RequestParam String name) {
        log.info("根据名称查询页面配置: name={}", name);
        PageSchemaDTO result = pageSchemaService.findByName(name);
        return ApiResponse.success(result);
    }

    /**
     * 根据页面类型查询配置
     *
     * @param kind 页面类型
     * @return 页面配置列表
     */
    @GetMapping("/by-kind/{kind}")
    @Operation(summary = "根据类型查询", description = "根据页面类型查询配置信息")
    @RequirePermission("page.page.read")
    public ApiResponse<List<PageSchemaDTO>> findByKind(
            @Parameter(description = "页面类型") @PathVariable String kind) {
        log.info("根据类型查询页面配置: kind={}", kind);
        List<PageSchemaDTO> result = pageSchemaService.findByKind(kind);
        return ApiResponse.success(result);
    }

    /**
     * 查询已发布的页面配置
     *
     * @return 已发布的页面配置列表
     */
    @GetMapping("/published")
    @Operation(summary = "查询已发布配置", description = "查询所有已发布的页面配置")
    @RequirePermission("page.page.read")
    public ApiResponse<List<PageSchemaDTO>> findPublished() {
        log.info("查询已发布的页面配置");
        List<PageSchemaDTO> result = pageSchemaService.findPublishedSchemas();
        return ApiResponse.success(result);
    }

    /**
     * 查询模板页面配置
     *
     * @return 模板页面配置列表
     */
    @GetMapping("/templates")
    @Operation(summary = "查询模板配置", description = "查询所有模板页面配置")
    @RequirePermission("page.page.read")
    public ApiResponse<List<PageSchemaDTO>> findTemplates(
            @Parameter(description = "模板分类") @RequestParam(required = false) String templateCategory) {
        log.info("查询模板页面配置: templateCategory={}", templateCategory);
        List<PageSchemaDTO> result = pageSchemaService.findTemplateSchemas(templateCategory);
        return ApiResponse.success(result);
    }

    /**
     * 统计页面配置总数
     *
     * @return 配置总数
     */
    @GetMapping("/count/total")
    @Operation(summary = "统计配置总数", description = "统计所有页面配置的总数量")
    @RequirePermission("page.page.read")
    public ApiResponse<Long> countTotal() {
        log.info("统计页面配置总数");
        Long result = pageSchemaService.countTotal();
        return ApiResponse.success(result);
    }

    /**
     * 统计已发布配置数量
     *
     * @return 已发布配置数量
     */
    @GetMapping("/count/published")
    @Operation(summary = "统计已发布配置数", description = "统计已发布页面配置的数量")
    @RequirePermission("page.page.read")
    public ApiResponse<Long> countPublished() {
        log.info("统计已发布页面配置数量");
        Long result = pageSchemaService.countPublished();
        return ApiResponse.success(result);
    }

    /**
     * 统计模板配置数量
     *
     * @return 模板配置数量
     */
    @GetMapping("/count/templates")
    @Operation(summary = "统计模板配置数", description = "统计模板类型页面配置的数量")
    @RequirePermission("page.page.read")
    public ApiResponse<Long> countTemplates() {
        log.info("统计模板页面配置数量");
        Long result = pageSchemaService.countTemplates();
        return ApiResponse.success(result);
    }

    /**
     * 验证页面名称是否唯一
     *
     * @param name 页面名称
     * @param excludePid 排除的PID（用于更新时验证）
     * @return 是否唯一
     */
    @GetMapping("/validate/name-unique")
    @Operation(summary = "验证名称唯一性", description = "验证页面名称是否已存在")
    @RequirePermission("page.page.read")
    public ApiResponse<Boolean> isNameUnique(
            @Parameter(description = "页面名称") @RequestParam String name,
            @Parameter(description = "排除的PID") @RequestParam(required = false) String excludePid) {
        log.info("验证页面名称唯一性，名称：{}，排除PID：{}", name, excludePid);
        Boolean result = pageSchemaService.isNameUnique(name, excludePid);
        return ApiResponse.success(result);
    }

    // ==================== 统一页面获取接口 ====================

    /**
     * 根据页面键获取页面Schema配置（推荐使用）
     *
     * 统一的页面获取端点，支持：
     * - Model 相关页面：pageKey 格式为 "{modelCode}_{pageType}"，如 "device_list", "store_form"
     * - Model 无关页面：pageKey 为自定义标识，如 "dashboard_main", "settings_general"
     *
     * @param pageKey 页面唯一标识
     * @return 页面配置
     */
    @GetMapping("/key/{pageKey}")
    @Operation(summary = "根据页面键获取Schema",
               description = "统一的页面获取端点。Model相关页面使用 {modelCode}_{pageType} 格式，如 device_list；独立页面使用自定义 key，如 dashboard_main")
    @RequirePermission("page.page.read")
    public ApiResponse<PageSchemaDTO> getByPageKey(
            @Parameter(description = "页面唯一标识，如 device_list, dashboard_main") @PathVariable String pageKey) {
        log.info("获取页面Schema: pageKey={}", pageKey);
        PageSchemaDTO schema = pageSchemaService.findByPageKey(pageKey);
        if (schema == null) {
            return ApiResponse.error("Page not found: " + pageKey);
        }
        return ApiResponse.success(schema);
    }

    // ==================== Mobile Sync Endpoints ====================

    /**
     * Get schema version metadata for schemas updated since a given timestamp.
     * Mobile clients use this to determine which schemas need re-fetching.
     *
     * @param since ISO-8601 timestamp (optional, defaults to epoch)
     * @return list of lightweight version metadata (no dslSchema)
     */
    @GetMapping("/versions")
    @Operation(summary = "Get schema versions since timestamp",
               description = "Returns lightweight version metadata for schemas updated after the given timestamp. Used by mobile clients for incremental sync.")
    @RequirePermission("page.page.read")
    public ApiResponse<List<PageSchemaSyncVersionDTO>> getVersionsSince(
            @Parameter(description = "ISO-8601 timestamp, e.g. 2026-03-25T00:00:00Z")
            @RequestParam(required = false) Instant since) {
        if (since == null) {
            since = Instant.EPOCH;
        }
        return ApiResponse.success(pageSchemaService.getVersionsSince(since));
    }

    /**
     * Batch fetch multiple page schemas by their page keys.
     * Mobile clients use this to fetch several schemas in a single round-trip.
     *
     * @param body JSON body with "pageKeys" array
     * @return list of full PageSchemaDTO objects
     */
    @PostMapping("/batch")
    @Operation(summary = "Batch fetch schemas by page keys",
               description = "Fetch multiple published schemas in a single request. Body: {\"pageKeys\": [\"key1\", \"key2\"]}")
    @RequirePermission("page.page.read")
    public ApiResponse<List<PageSchemaDTO>> batchGetByKeys(
            @RequestBody Map<String, List<String>> body) {
        List<String> pageKeys = body.getOrDefault("pageKeys", List.of());
        if (pageKeys.isEmpty()) {
            return ApiResponse.success(List.of());
        }
        return ApiResponse.success(pageSchemaService.batchGetByKeys(pageKeys));
    }
}
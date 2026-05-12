package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.meta.service.RecordCapabilityService;
import com.auraboot.framework.meta.util.PageKeyConverter;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;


/**
 * 动态CRUD控制器
 * 基于页面定义提供统一的CRUD操作接口
 * 
 * 注意: 动态数据操作基于运行时模型定义，不涉及 Git-First 流程
 *
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/dynamic")
@Tag(name = "动态CRUD管理", description = "基于页面定义的统一CRUD操作接口")
public class DynamicController {

    static final int MAX_BATCH_SIZE = 500;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    @Autowired
    private DynamicDataService dynamicDataService;

    @Autowired
    private NamedQueryService namedQueryService;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private RecordCapabilityService recordCapabilityService;

    @Autowired
    private com.auraboot.framework.meta.service.ModelFieldBindingService modelFieldBindingService;
    /**
     * 分页查询数据
     *
     * ✅ 修复: 改为使用 @RequestParam 接收分页参数,符合 RESTful 规范
     * 前端已使用 GET + query params,后端需对应修改
     */
    private static final ObjectMapper filterMapper = new ObjectMapper();

    @GetMapping("/{pageKey}/list")
    @Operation(summary = "分页查询数据", description = "根据页面定义进行分页查询。当页面DSL指定dataSource.type='namedQuery'时，前端传入queryCode参数直接执行NamedQuery。")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<PaginationResult<Map<String, Object>>> list(
            @Parameter(description = "页面Key")
            @PathVariable String pageKey,

            @Parameter(description = "页码")
            @RequestParam(defaultValue = "1") @Min(1) Integer pageNum,

            @Parameter(description = "每页大小")
            @RequestParam(defaultValue = "10") @Min(1) @Max(500) Integer pageSize,

            @Parameter(description = "关键词搜索")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "字段过滤条件 JSON 数组, e.g. [{\"fieldName\":\"status\",\"operator\":\"EQ\",\"value\":\"DRAFT\"}]")
            @RequestParam(required = false) String filters,

            @Parameter(description = "排序字段, e.g. created_at")
            @RequestParam(required = false) String sortField,

            @Parameter(description = "排序方向: ASC or DESC")
            @RequestParam(required = false) String sortOrder,

            @Parameter(description = "Multi-field sort, comma-separated field:direction pairs, e.g. created_at:desc,price:asc. Takes precedence over sortField/sortOrder when provided. Max 5 fields.")
            @RequestParam(required = false) String sortFields,

            @Parameter(description = "NamedQuery code — when provided, data is fetched from a NamedQuery instead of the model table")
            @RequestParam(required = false) String queryCode,

            @Parameter(description = "Cursor for keyset pagination. Pass the nextCursor value from the previous response to fetch the next page efficiently. When provided, pageNum is ignored and WHERE id > cursor is used instead of OFFSET.")
            @RequestParam(required = false) Long cursor) {

        log.info("分页查询数据: pageKey={}, pageNum={}, pageSize={}, keyword={}, filters={}, sortFields={}, queryCode={}, cursor={}",
            logSafe(pageKey), pageNum, pageSize, logSafe(keyword), logSafe(filters),
                logSafe(sortFields), logSafe(queryCode), cursor);

        List<QueryCondition> conditions = parseFilters(filters);
        List<SortField> parsedSortFields = parseSortFields(sortFields, sortField, sortOrder);

        DynamicQueryRequest queryRequest = DynamicQueryRequest.builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .keyword(keyword)
                .conditions(conditions.isEmpty() ? null : conditions)
                .sortFields(parsedSortFields.isEmpty() ? null : parsedSortFields)
                .cursor(cursor)
                .build();

        // Resolve model code from pageKey.
        // Priority: if the raw pageKey is itself a valid model code, use it as-is.
        // This handles model codes that end in page-type suffixes (e.g. sl_price_list)
        // which would be incorrectly stripped by PageKeyConverter (to sl_price).
        String modelCode = resolveModelCode(pageKey);

        // If queryCode is provided, delegate to NamedQuery execution.
        if (queryCode != null && !queryCode.isBlank()) {
            PaginationResult<Map<String, Object>> result = dynamicDataService.listByQueryCode(queryCode, queryRequest);
            return ApiResponse.success(result);
        }

        // For VIEW models (no physical table), fallback to same-code NamedQuery when available.
        // This keeps /api/dynamic/{pageKey}/list usable for view-style pages.
        try {
            Optional<ModelDefinition> modelOpt = metaModelService.getModelDefinition(modelCode);
            if (modelOpt.isPresent() && "view".equalsIgnoreCase(modelOpt.get().getModelType())) {
                NamedQueryDTO namedQuery = namedQueryService.findByCode(modelCode);
                if (namedQuery != null && namedQuery.getCode() != null && !namedQuery.getCode().isBlank()) {
                    PaginationResult<Map<String, Object>> result = dynamicDataService.listByQueryCode(namedQuery.getCode(), queryRequest);
                    return ApiResponse.success(result);
                }
            }
        } catch (Exception e) {
            log.debug("VIEW fallback to named query skipped for modelCode={}: {}",
                    logSafe(modelCode), logSafe(e.getMessage()));
        }

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, queryRequest);
        return ApiResponse.success(result);
    }

    /**
     * Get filter schema for a page.
     * When queryCode is provided, returns searchable fields from the NamedQuery's param-schema.
     * This allows the frontend to auto-generate filter panels for namedQuery-backed pages.
     */
    @GetMapping("/{pageKey}/filter-schema")
    @Operation(summary = "获取过滤器Schema", description = "返回页面的过滤字段定义，用于自动生成搜索面板")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<List<NamedQueryFieldDTO>> getFilterSchema(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @Parameter(description = "NamedQuery code") @RequestParam String queryCode) {

        log.info("Get filter schema: pageKey={}, queryCode={}", logSafe(pageKey), logSafe(queryCode));

        List<NamedQueryFieldDTO> fields = namedQueryService.getFields(queryCode);
        // Filter to searchable fields and sort by sort_order
        List<NamedQueryFieldDTO> schema = fields.stream()
                .filter(f -> Boolean.TRUE.equals(f.getSearchable()))
                .sorted(java.util.Comparator.comparingInt(f -> f.getSortOrder() != null ? f.getSortOrder() : 0))
                .toList();

        return ApiResponse.success(schema);
    }

    /**
     * Resolve the model code from a pageKey path variable.
     *
     * <p>When the DynamicController is called directly with a model code (e.g.
     * /api/dynamic/sl_price_list/list), the raw pageKey IS the model code.
     * {@link PageKeyConverter#toModelCode} would incorrectly strip the _list suffix,
     * turning "sl_price_list" into "sl_price" which does not exist.
     *
     * <p>Priority:
     * <ol>
     *   <li>Normalize hyphens → underscores and lowercase the raw pageKey.</li>
     *   <li>If a model with that exact code exists → use it.</li>
     *   <li>Otherwise → fall back to {@link PageKeyConverter#toModelCode} (strips suffix).</li>
     * </ol>
     */
    private String resolveModelCode(String pageKey) {
        String normalized = pageKey.replace("-", "_").toLowerCase();
        try {
            if (metaModelService.getModelDefinition(normalized).isPresent()) {
                return normalized;
            }
        } catch (Exception e) {
            log.debug("Model lookup failed for normalized pageKey '{}', falling back to PageKeyConverter: {}",
                    logSafe(normalized), logSafe(e.getMessage()));
        }
        return PageKeyConverter.toModelCode(pageKey);
    }

    private List<QueryCondition> parseFilters(String filters) {
        if (filters == null || filters.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return filterMapper.readValue(filters, new TypeReference<List<QueryCondition>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse filters: {}", logSafe(filters), e);
            throw new com.auraboot.framework.meta.exception.MetaServiceException(
                "Invalid filter format. Expected JSON array, e.g. [{\"fieldName\":\"status\",\"operator\":\"EQ\",\"value\":\"DRAFT\"}]");
        }
    }

    static final int MAX_SORT_FIELDS = 5;

    /**
     * Parse sort parameters into a list of SortField.
     * <p>
     * When {@code sortFields} is provided (comma-separated "field:direction" pairs),
     * it takes precedence over the legacy single {@code sortField}/{@code sortOrder} parameters.
     * Max {@value MAX_SORT_FIELDS} fields to prevent abuse.
     *
     * @param sortFields  multi-field sort string, e.g. "created_at:desc,price:asc"
     * @param sortField   legacy single sort field
     * @param sortOrder   legacy single sort direction
     * @return parsed list of SortField (may be empty)
     */
    private List<SortField> parseSortFields(String sortFields, String sortField, String sortOrder) {
        // Multi-field sort takes precedence
        if (sortFields != null && !sortFields.isBlank()) {
            return parseMultiSortFields(sortFields);
        }
        // Fallback to legacy single-field sort
        if (sortField == null || sortField.isBlank()) {
            return Collections.emptyList();
        }
        SortField sf = SortField.builder()
                .fieldName(sortField)
                .direction("asc".equalsIgnoreCase(sortOrder) ? SortField.SortDirection.ASC : SortField.SortDirection.DESC)
                .build();
        return List.of(sf);
    }

    /**
     * Parse multi-field sort string: "field1:dir1,field2:dir2,...".
     * Validates field count and direction values.
     */
    private List<SortField> parseMultiSortFields(String sortFields) {
        String[] pairs = sortFields.split(",");
        if (pairs.length > MAX_SORT_FIELDS) {
            throw new com.auraboot.framework.meta.exception.MetaServiceException(
                    "Too many sort fields. Maximum allowed: " + MAX_SORT_FIELDS);
        }
        List<SortField> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < pairs.length; i++) {
            String pair = pairs[i].trim();
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split(":");
            String fieldName = parts[0].trim();
            if (fieldName.isEmpty()) {
                throw new com.auraboot.framework.meta.exception.MetaServiceException(
                        "Empty field name in sortFields at position " + (i + 1));
            }
            // Validate field name: only allow alphanumeric and underscore to prevent SQL injection
            if (!fieldName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new com.auraboot.framework.meta.exception.MetaServiceException(
                        "Invalid sort field name: " + fieldName);
            }
            // Deduplicate: keep only the first occurrence of each field
            if (!seen.add(fieldName.toLowerCase())) {
                continue;
            }
            SortField.SortDirection direction = SortField.SortDirection.DESC;
            if (parts.length > 1) {
                String dir = parts[1].trim().toLowerCase();
                if ("asc".equals(dir)) {
                    direction = SortField.SortDirection.ASC;
                } else if (!"desc".equals(dir)) {
                    throw new com.auraboot.framework.meta.exception.MetaServiceException(
                            "Invalid sort direction '" + parts[1].trim() + "' for field '" + fieldName + "'. Must be 'asc' or 'desc'.");
                }
            }
            result.add(SortField.builder()
                    .fieldName(fieldName)
                    .direction(direction)
                    .priority(i)
                    .build());
        }
        return result;
    }
    /**
     * 根据ID获取单条数据
     */
    @GetMapping("/{pageKey}/{recordId}")
    @Operation(summary = "获取单条数据", description = "根据ID获取单条数据")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<Map<String, Object>> getById(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @Parameter(description = "记录ID") @PathVariable String recordId) {
        log.info("获取单条数据: {} - {}", logSafe(pageKey), logSafe(recordId));
        String modelCode = resolveModelCode(pageKey);
        Map<String, Object> result = dynamicDataService.getById(modelCode, recordId);
        return ApiResponse.success(result);
    }

    /**
     * ARCH-001: Get available actions for a specific record.
     * Returns actions filtered by: user permission + record state + platform + context.
     * Mobile Action Bar renders the top 2 (by priority) as primary buttons.
     */
    @GetMapping("/{pageKey}/{recordId}/capabilities")
    @Operation(summary = "Get record capabilities",
            description = "Returns available actions for a record, filtered by user permissions, record state, platform, and context")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<RecordCapabilities> getRecordCapabilities(
            @Parameter(description = "Page key") @PathVariable String pageKey,
            @Parameter(description = "Record ID") @PathVariable String recordId,
            @Parameter(description = "Platform: web or mobile") @RequestParam(defaultValue = "web") String platform,
            @Parameter(description = "Context: detail, list, or inbox") @RequestParam(defaultValue = "detail") String context) {

        log.info("Get record capabilities: pageKey={}, recordId={}, platform={}, context={}",
                logSafe(pageKey), logSafe(recordId), logSafe(platform), logSafe(context));
        String modelCode = resolveModelCode(pageKey);
        Long userId = MetaContext.getCurrentUserId();

        RecordCapabilities capabilities = recordCapabilityService.getRecordCapabilities(
                modelCode, recordId, platform, context, userId);
        return ApiResponse.success(capabilities);
    }

    /**
     * 创建数据
     */
    @PostMapping("/{pageKey}/create")
    @Operation(summary = "创建数据", description = "根据页面定义创建新数据")
    @RequirePermission("model.{pageKey}.create")
    public ApiResponse<Object> create(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody Map<String, Object> data) {
        log.info("创建数据: {}", logSafe(pageKey));
        String modelCode = resolveModelCode(pageKey);
        Map<String, Object> result = dynamicDataService.create(modelCode, data);
        return ApiResponse.success(result);
    }

    /**
     * 创建数据（兼容路由）
     * 支持 POST /api/dynamic/{pageKey} 格式
     */
    @PostMapping("/{pageKey}")
    @Operation(summary = "创建数据（兼容）", description = "根据页面定义创建新数据（兼容旧DSL格式）")
    @RequirePermission("model.{pageKey}.create")
    public ApiResponse<Object> createCompat(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody Map<String, Object> data) {
        return create(pageKey, data);
    }

    /**
     * 更新数据
     */
    @PutMapping("/{pageKey}/{recordId}")
    @Operation(summary = "更新数据", description = "根据页面定义更新数据")
    @RequirePermission("model.{pageKey}.update")
    public ApiResponse<Map<String, Object>> update(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @Parameter(description = "记录ID") @PathVariable String recordId,
            @RequestBody Map<String, Object> data) {
        log.info("更新数据: {} - {}", logSafe(pageKey), logSafe(recordId));
        String modelCode = resolveModelCode(pageKey);
        Map<String, Object> result = dynamicDataService.update(modelCode, recordId, data);
        return ApiResponse.success(result);
    }

    /**
     * 删除数据
     */
    @DeleteMapping("/{pageKey}/{recordId}")
    @Operation(summary = "删除数据", description = "根据页面定义删除数据")
    @RequirePermission("model.{pageKey}.delete")
    public ApiResponse<Void> delete(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @Parameter(description = "记录ID") @PathVariable String recordId) {
        log.info("删除数据: {} - {}", logSafe(pageKey), logSafe(recordId));
        String modelCode = resolveModelCode(pageKey);
        dynamicDataService.delete(modelCode, recordId);
        return ApiResponse.success(null);
    }

    /**
     * 批量创建数据
     */
    @PostMapping("/{pageKey}/batch")
    @Operation(summary = "批量创建数据", description = "根据页面定义批量创建数据")
    @RequirePermission("model.{pageKey}.create")
    public ApiResponse<DynamicBatchResponse> batchCreate(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody List<Map<String, Object>> dataList) {
        if (dataList.size() > MAX_BATCH_SIZE) {
            return ApiResponse.error("Batch size " + dataList.size() + " exceeds maximum " + MAX_BATCH_SIZE);
        }
        log.info("批量创建数据: {}, 数量: {}", logSafe(pageKey), dataList.size());
        String modelCode = resolveModelCode(pageKey);
        DynamicBatchResponse result = dynamicDataService.batchCreate(modelCode, dataList);
        return ApiResponse.success(result);
    }

    /**
     * 批量更新数据
     */
    @PutMapping("/{pageKey}/batch")
    @Operation(summary = "批量更新数据", description = "根据页面定义批量更新数据")
    @RequirePermission("model.{pageKey}.update")
    public ApiResponse<DynamicBatchResponse> batchUpdate(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody List<Map<String, Object>> dataList) {
        if (dataList.size() > MAX_BATCH_SIZE) {
            return ApiResponse.error("Batch size " + dataList.size() + " exceeds maximum " + MAX_BATCH_SIZE);
        }
        log.info("批量更新数据: {}, 数量: {}", logSafe(pageKey), dataList.size());
        String modelCode = resolveModelCode(pageKey);
        DynamicBatchResponse result = dynamicDataService.batchUpdate(modelCode, dataList);
        return ApiResponse.success(result);
    }

    /**
     * 批量删除数据
     */
    @DeleteMapping("/{pageKey}/batch")
    @Operation(summary = "批量删除数据", description = "根据页面定义批量删除数据")
    @RequirePermission("model.{pageKey}.delete")
    public ApiResponse<Void> batchDelete(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody List<String> recordIds) {
        if (recordIds.size() > MAX_BATCH_SIZE) {
            return ApiResponse.error("Batch size " + recordIds.size() + " exceeds maximum " + MAX_BATCH_SIZE);
        }
        log.info("批量删除数据: {}, 数量: {}", logSafe(pageKey), recordIds.size());
        String modelCode = resolveModelCode(pageKey);
        dynamicDataService.batchDelete(modelCode, recordIds);
        return ApiResponse.success(null);
    }

    /**
     * 验证数据
     */
    @PostMapping("/{pageKey}/validate")
    @Operation(summary = "验证数据", description = "根据页面定义验证数据")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<Map<String, Object>> validate(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody Map<String, Object> data) {
        log.info("验证数据: {}", logSafe(pageKey));
        
        // 转换为新的接口调用
        String modelCode = resolveModelCode(pageKey);
        ValidationContext context = ValidationContext.CREATE;
        
        ValidationResult result = dynamicDataService.validate(modelCode, data, context);
        
        // 转换为旧格式返回
        Map<String, Object> legacyResult = Map.of(
            "valid", result.getValid(),
            "errors", result.getErrors() != null ? result.getErrors() : Collections.emptyList()
        );
        
        return ApiResponse.success(legacyResult);
    }

    /**
     * 获取字段选项
     */
    @GetMapping("/{pageKey}/field-options/{fieldName}")
    @Operation(summary = "获取字段选项", description = "获取指定字段的选项列表")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<List<Map<String, Object>>> getFieldOptions(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @Parameter(description = "字段名称") @PathVariable String fieldName,
            @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword) {
        log.info("获取字段选项: {} - {}", logSafe(pageKey), logSafe(fieldName));
        
        // 转换为新的接口调用
        String modelCode = resolveModelCode(pageKey);
        FieldOptionRequest request = FieldOptionRequest.builder()
                .keyword(keyword)
                .limit(50)
                .build();
        
        List<FieldOption> options = dynamicDataService.getFieldOptions(modelCode, fieldName, request);
        
        // 转换为旧格式返回
        List<Map<String, Object>> legacyResult = options.stream()
                .map(option -> Map.of(
                    "value", option.getValue(),
                    "label", option.getLabel(),
                    "disabled", option.getDisabled()
                ))
                .toList();
        
        return ApiResponse.success(legacyResult);
    }

    /**
     * 执行自定义查询
     */
    @PostMapping("/{pageKey}/query/{queryName}")
    @Operation(summary = "执行自定义查询", description = "执行页面定义的自定义查询")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<List<Map<String, Object>>> executeCustomQuery(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @Parameter(description = "查询名称") @PathVariable String queryName,
            @RequestBody(required = false) Map<String, Object> queryParams) {
        log.info("执行自定义查询: {} - {}", logSafe(pageKey), logSafe(queryName));
        String modelCode = resolveModelCode(pageKey);
        List<Map<String, Object>> result = dynamicDataService.executeCustomQuery(modelCode, queryName, queryParams);
        return ApiResponse.success(result);
    }

    /**
     * 执行自定义操作
     */
    @PostMapping("/{pageKey}/action/{actionName}")
    @Operation(summary = "执行自定义操作", description = "执行页面定义的自定义操作")
    @RequirePermission("model.{pageKey}.update")
    public ApiResponse<Map<String, Object>> executeCustomAction(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @Parameter(description = "操作名称") @PathVariable String actionName,
            @RequestBody(required = false) Map<String, Object> actionParams) {
        log.info("执行自定义操作: {} - {}", logSafe(pageKey), logSafe(actionName));
        
        // 转换为新的接口调用
        String modelCode = resolveModelCode(pageKey);
        ActionExecutionResult result = dynamicDataService.executeCustomAction(modelCode, actionName, actionParams);
        
        // 转换为旧格式返回
        Map<String, Object> legacyResult = Map.of(
            "success", result.getSuccess(),
            "message", result.getMessage() != null ? result.getMessage() : "",
            "data", result.getResultData() != null ? result.getResultData() : Collections.emptyMap()
        );
        
        return ApiResponse.success(legacyResult);
    }

    /**
     * 导出数据（JSON响应，包含下载URL）
     */
    @PostMapping("/{pageKey}/export")
    @Operation(summary = "导出数据", description = "根据页面定义导出数据")
    @RequirePermission("model.{pageKey}.export")
    public ApiResponse<Map<String, Object>> exportData(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody(required = false) Map<String, Object> exportParams) {
        log.info("导出数据: {}", logSafe(pageKey));

        // Parse export params
        String modelCode = resolveModelCode(pageKey);
        DataExportRequest.ExportFormat format = DataExportRequest.ExportFormat.EXCEL;
        List<QueryCondition> conditions = null;

        if (exportParams != null) {
            // Parse format
            Object formatObj = exportParams.get("format");
            if (formatObj != null) {
                String formatStr = formatObj.toString().toUpperCase();
                if ("csv".equals(formatStr)) {
                    format = DataExportRequest.ExportFormat.CSV;
                }
            }

            // Parse conditions from frontend filters
            Object conditionsObj = exportParams.get("conditions");
            if (conditionsObj instanceof List<?> conditionsList) {
                conditions = new java.util.ArrayList<>();
                for (Object item : conditionsList) {
                    if (item instanceof Map<?, ?> condMap) {
                        String field = condMap.get("field") != null ? condMap.get("field").toString() : null;
                        String op = condMap.get("operator") != null ? condMap.get("operator").toString() : null;
                        Object val = condMap.get("value");
                        if (field != null && op != null) {
                            try {
                                QueryCondition.Operator operator = QueryCondition.Operator.fromCode(op);
                                if (operator == null) {
                                    throw new IllegalArgumentException("Unsupported operator: " + op);
                                }
                                conditions.add(QueryCondition.builder()
                                        .fieldName(field)
                                        .operator(operator)
                                        .value(val)
                                        .build());
                            } catch (IllegalArgumentException e) {
                                log.warn("Invalid operator in export condition: {}", logSafe(op));
                            }
                        }
                    }
                }
                if (conditions.isEmpty()) conditions = null;
            }
        }

        DataExportRequest request = DataExportRequest.builder()
                .format(format)
                .conditions(conditions)
                .includeHeader(true)
                .build();

        ExportResult result = dynamicDataService.exportData(modelCode, request);

        if (!result.getSuccess()) {
            return ApiResponse.error(result.getErrorMessage() != null ? result.getErrorMessage() : "导出失败");
        }

        // 生成下载URL
        String downloadUrl = "/api/dynamic/" + pageKey + "/download?file=" +
                java.net.URLEncoder.encode(result.getFilePath(), java.nio.charset.StandardCharsets.UTF_8);

        Map<String, Object> legacyResult = Map.of(
            "success", true,
            "downloadUrl", downloadUrl,
            "recordCount", result.getRecordCount() != null ? result.getRecordCount() : 0L
        );

        return ApiResponse.success(legacyResult);
    }

    /**
     * 下载导出文件
     */
    @GetMapping("/{pageKey}/download")
    @Operation(summary = "下载导出文件", description = "下载导出的CSV文件")
    @RequirePermission("model.{pageKey}.export")
    public void downloadExport(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @Parameter(description = "文件路径") @RequestParam String file,
            HttpServletResponse response) throws java.io.IOException {
        log.info("下载导出文件: pageKey={}, file={}", logSafe(pageKey), logSafe(file));

        // Security: validate file path is within temp directory to prevent path traversal
        java.nio.file.Path tempDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));
        java.nio.file.Path filePath = java.nio.file.Paths.get(file).normalize().toAbsolutePath();
        if (!filePath.startsWith(tempDir.normalize().toAbsolutePath())) {
            log.warn("Path traversal attempt blocked: {}", logSafe(file));
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied");
            return;
        }
        if (!java.nio.file.Files.exists(filePath)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "文件不存在");
            return;
        }

        String fileName = pageKey + "_export.xlsx";
        String encodedFileName = java.net.URLEncoder.encode(fileName, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");

        long fileSize = java.nio.file.Files.size(filePath);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setContentLengthLong(fileSize);
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + encodedFileName);
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");

        try (java.io.InputStream is = java.nio.file.Files.newInputStream(filePath);
             java.io.OutputStream os = response.getOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();
        }

        // 下载完成后删除临时文件
        try {
            java.nio.file.Files.deleteIfExists(filePath);
        } catch (Exception e) {
            log.warn("Failed to delete temp export file: {}", logSafe(file));
        }
    }

    /**
     * 导入数据
     */
    @PostMapping("/{pageKey}/import")
    @Operation(summary = "导入数据", description = "根据页面定义导入数据")
    @RequirePermission("model.{pageKey}.import")
    public ApiResponse<Map<String, Object>> importData(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody(required = false) Map<String, Object> importParams) {
        log.info("导入数据: {}", logSafe(pageKey));
        
        // 转换为新的接口调用
        String modelCode = resolveModelCode(pageKey);
        DataImportRequest request = DataImportRequest.builder()
                .format(DataImportRequest.ImportFormat.EXCEL)
                .mode(DataImportRequest.ImportMode.UPSERT)
                .batchSize(1000)
                .build();
        
        ImportResult result = dynamicDataService.importData(modelCode, request);
        
        // 转换为旧格式返回
        Map<String, Object> legacyResult = Map.of(
            "success", result.getSuccess(),
            "imported", result.getSuccessCount() != null ? result.getSuccessCount() : 0,
            "failed", result.getFailedCount() != null ? result.getFailedCount() : 0,
            "total", result.getTotalCount() != null ? result.getTotalCount() : 0
        );
        
        return ApiResponse.success(legacyResult);
    }

    /**
     * 获取统计信息
     */
    @PostMapping("/{pageKey}/stats")
    @Operation(summary = "获取统计信息", description = "获取页面数据的统计信息")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<Map<String, Object>> getStats(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody(required = false) Map<String, Object> statsParams) {
        log.info("获取统计信息: {}", logSafe(pageKey));
        
        // 转换为新的接口调用
        String modelCode = resolveModelCode(pageKey);
        Map<String, Object> result = dynamicDataService.getStats(modelCode, statsParams != null ? statsParams : Collections.emptyMap());
        return ApiResponse.success(result);
    }

    /**
     * 获取关联数据
     */
    @GetMapping("/{pageKey}/{recordId}/relations/{relationName}")
    @Operation(summary = "获取关联数据", description = "获取指定记录的关联数据")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<List<Map<String, Object>>> getRelationData(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @Parameter(description = "记录ID") @PathVariable String recordId,
            @Parameter(description = "关联名称") @PathVariable String relationName) {
        log.info("获取关联数据: {} - {} - {}", logSafe(pageKey), logSafe(recordId), logSafe(relationName));
        
        // 转换为新的接口调用
        String modelCode = resolveModelCode(pageKey);
        List<Map<String, Object>> result = dynamicDataService.getRelationData(modelCode, recordId, relationName, Collections.emptyMap());
        return ApiResponse.success(result);
    }

    /**
     * GAP-004: Rich metadata endpoint for mobile apps, external integrations,
     * and the AI modeling assistant.
     *
     * Returns model fields with type info and enum options (for DICT fields),
     * the full DSL page schema, current user's CRUD permissions, and which
     * view types are configured in the page schema.
     */
    @GetMapping("/{pageKey}/meta")
    @Operation(summary = "Rich page metadata for mobile/external integrations",
            description = "Returns model fields, DSL schema, user permissions, and available view types for a page")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<PageMetaResponse> getMeta(
            @Parameter(description = "页面Key") @PathVariable String pageKey) {

        log.info("Get rich meta: pageKey={}", logSafe(pageKey));
        String modelCode = resolveModelCode(pageKey);

        // 1. Resolve model definition
        Optional<ModelDefinition> modelOpt = metaModelService.getModelDefinition(modelCode);
        if (modelOpt.isEmpty()) {
            return ApiResponse.error("Model not found: " + modelCode);
        }
        ModelDefinition model = modelOpt.get();

        // 2. Build field descriptors
        List<PageMetaResponse.FieldMeta> fieldMetas = buildFieldMetas(modelCode, model);

        // 3. Page schema (may be null for models without explicit page definition)
        Map<String, Object> schemaJson = null;
        List<String> availableViews = new ArrayList<>(List.of("table")); // default
        try {
            PageSchemaDTO pageSchema = pageSchemaService.findByPageKey(pageKey);
            if (pageSchema != null) {
                // Build a composite schema map from V2 flat fields
                schemaJson = new LinkedHashMap<>();
                if (pageSchema.getBlocks() != null) schemaJson.put("blocks", pageSchema.getBlocks());
                if (pageSchema.getLayout() != null) schemaJson.put("layout", pageSchema.getLayout());
                if (pageSchema.getTitle() != null) schemaJson.put("title", pageSchema.getTitle());
                availableViews = deriveAvailableViews(pageSchema);
            }
        } catch (Exception e) {
            log.debug("No page schema found for pageKey={}: {}", logSafe(pageKey), logSafe(e.getMessage()));
        }

        // 4. Permissions — check programmatically using resolved codes
        PageMetaResponse.Permissions permissions = resolvePermissions(pageKey);

        return ApiResponse.success(PageMetaResponse.builder()
                .pageKey(pageKey)
                .modelCode(modelCode)
                .title(model.getDisplayName() != null ? model.getDisplayName() : model.getCode())
                .fields(fieldMetas)
                .schema(schemaJson)
                .permissions(permissions)
                .availableViews(availableViews)
                .build());
    }

    /** Build FieldMeta list from model definition, resolving dict options where available. */
    private List<PageMetaResponse.FieldMeta> buildFieldMetas(String modelCode, ModelDefinition model) {
        if (model.getFields() == null) return Collections.emptyList();

        List<PageMetaResponse.FieldMeta> result = new ArrayList<>();
        for (FieldDefinition field : model.getFields()) {
            List<PageMetaResponse.OptionItem> options = null;

            // For DICT and ENUM fields try to resolve options via DynamicDataService
            String dataType = field.getDataType();
            if (dataType != null && (dataType.equalsIgnoreCase("dict")
                    || dataType.equalsIgnoreCase("enum"))) {
                try {
                    FieldOptionRequest optionReq = FieldOptionRequest.builder().limit(500).build();
                    List<FieldOption> rawOptions = dynamicDataService.getFieldOptions(modelCode, field.getCode(), optionReq);
                    if (rawOptions != null && !rawOptions.isEmpty()) {
                        options = rawOptions.stream()
                                .map(o -> PageMetaResponse.OptionItem.builder()
                                        .value(o.getValue() != null ? o.getValue().toString() : null)
                                        .label(o.getLabel())
                                        .build())
                                .toList();
                    }
                } catch (Exception e) {
                    log.debug("Could not resolve options for field={}.{}: {}",
                            logSafe(modelCode), logSafe(field.getCode()), logSafe(e.getMessage()));
                }
            }

            result.add(PageMetaResponse.FieldMeta.builder()
                    .code(field.getCode())
                    .displayName(field.getDisplayName() != null ? field.getDisplayName() : field.getCode())
                    .fieldType(dataType)
                    .required(field.isRequired())
                    .options(options)
                    .build());
        }
        return result;
    }

    /**
     * Derive available view types from page schema by scanning top-level blocks for
     * recognized view-type indicators (table, kanban, calendar, gallery, gantt, tree).
     */
    private List<String> deriveAvailableViews(PageSchemaDTO pageSchema) {
        Set<String> views = new LinkedHashSet<>();
        views.add("table"); // TABLE is always available

        try {
            List<Object> blocksList = pageSchema.getBlocks();
            if (blocksList == null) return new ArrayList<>(views);

            Object blocksObj = blocksList;
            if (blocksObj instanceof List<?> blockList) {
                for (Object block : blockList) {
                    if (block instanceof Map<?, ?> blockMap) {
                        Object bt = blockMap.get("blockType");
                        if (bt instanceof String blockType) {
                            switch (blockType.toLowerCase()) {
                                case "kanban" -> views.add("kanban");
                                case "calendar" -> views.add("calendar");
                                case "gallery" -> views.add("gallery");
                                case "gantt" -> views.add("gantt");
                                case "tree" -> views.add("tree");
                                default -> {} // table → TABLE already included
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not derive view types from schema: {}", logSafe(e.getMessage()));
        }

        return new ArrayList<>(views);
    }

    /** Check current user's CRUD+export permissions for a given pageKey. */
    private PageMetaResponse.Permissions resolvePermissions(String pageKey) {
        Long userId = MetaContext.getCurrentUserId();
        if (userId == null) {
            return PageMetaResponse.Permissions.builder()
                    .canCreate(false).canUpdate(false).canDelete(false).canExport(false)
                    .build();
        }

        String permissionModelCode = PageKeyConverter.toModelCode(pageKey);
        boolean canRead   = userPermissionService.hasPermission(userId, "model." + permissionModelCode + ".read");
        boolean canCreate = userPermissionService.hasPermission(userId, "model." + permissionModelCode + ".create");
        boolean canUpdate = userPermissionService.hasPermission(userId, "model." + permissionModelCode + ".update");
        boolean canDelete = userPermissionService.hasPermission(userId, "model." + permissionModelCode + ".delete");
        boolean canExport = userPermissionService.hasPermission(userId, "model." + permissionModelCode + ".export");

        return PageMetaResponse.Permissions.builder()
                .canCreate(canCreate)
                .canUpdate(canUpdate)
                .canDelete(canDelete)
                .canExport(canRead || canExport)
                .build();
    }

    @GetMapping("/{pageKey}/metadata")
    @Operation(summary = "获取页面元数据", description = "根据模型元数据返回页面基础字段信息")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<Map<String, Object>> getPageMetadata(
            @Parameter(description = "页面Key") @PathVariable String pageKey) {
        log.info("获取页面元数据: {}", logSafe(pageKey));

        String modelCode = resolveModelCode(pageKey);
        Optional<ModelDefinition> modelOpt = metaModelService.getModelDefinition(modelCode);
        if (modelOpt.isEmpty()) {
            return ApiResponse.error("模型不存在: " + modelCode);
        }

        ModelDefinition model = modelOpt.get();
        List<Map<String, Object>> fields = model.getFields() == null
                ? Collections.emptyList()
                : model.getFields().stream()
                .map(field -> {
                    Map<String, Object> fieldMeta = new java.util.LinkedHashMap<>();
                    fieldMeta.put("code", field.getCode());
                    fieldMeta.put("name", field.getName());
                    fieldMeta.put("displayName", field.getDisplayName());
                    fieldMeta.put("columnName", field.getColumnName());
                    fieldMeta.put("dataType", field.getDataType());
                    fieldMeta.put("required", field.getRequired());
                    fieldMeta.put("primaryKey", field.getPrimaryKey());
                    fieldMeta.put("unique", field.getUnique());
                    fieldMeta.put("displayField", field.getDisplayField());
                    fieldMeta.put("sortOrder", field.getSortOrder());
                    return fieldMeta;
                })
                .toList();

        Map<String, Object> legacyResult = new java.util.LinkedHashMap<>();
        legacyResult.put("pageKey", pageKey);
        legacyResult.put("modelCode", modelCode);
        legacyResult.put("modelName", model.getDisplayName() != null ? model.getDisplayName() : model.getCode());
        legacyResult.put("tableName", model.getTableName());
        legacyResult.put("fields", fields);

        return ApiResponse.success(legacyResult);
    }

    /**
     * Get field metadata for rendering (extension, dictCode, refTarget, etc.)
     *
     * This endpoint provides field-level metadata needed by detail/form page renderers
     * to correctly display rich text, attachments, ratings, color pickers, etc.
     * Uses model-level read permission (not management permission).
     */
    @GetMapping("/{pageKey}/field-meta")
    @Operation(summary = "Get field metadata for rendering", description = "Returns field metadata including extension, dictCode, refTarget for page rendering")
    @RequirePermission("model.{pageKey}.read")
    public ApiResponse<List<com.auraboot.framework.meta.dto.MetaFieldDTO>> getFieldMeta(
            @Parameter(description = "Page key") @PathVariable String pageKey) {
        String modelCode = resolveModelCode(pageKey);
        com.auraboot.framework.meta.dto.MetaModelDTO model = metaModelService.findByCode(modelCode);
        if (model == null) {
            return ApiResponse.error("Model not found: " + modelCode);
        }
        List<com.auraboot.framework.meta.dto.MetaFieldDTO> fields = modelFieldBindingService.getModelFields(model.getPid());
        return ApiResponse.success(fields);
    }

    /**
     * 联合保存主表和子表数据
     *
     * 在单个事务中保存主表记录及其关联的子表记录。
     * 支持创建和更新操作（根据主表数据是否包含ID自动判断）。
     *
     * 请求格式示例:
     * {
     *   "masterData": { "customer_name": "张三", "total_amount": 1000 },
     *   "tables": {
     *     "items": [
     *       { "product_id": "P1", "quantity": 2, "unit_price": 100 },
     *       { "product_id": "P2", "quantity": 3, "unit_price": 200 }
     *     ],
     *     "payments": [
     *       { "payment_method": "credit_card", "amount": 500 }
     *     ]
     *   },
     *   "replaceExisting": true
     * }
     */
    @PostMapping("/{pageKey}/joint-save")
    @Operation(summary = "联合保存主表和子表", description = "在单个事务中保存主表及其关联的子表数据")
    @RequirePermission("model.{pageKey}.update")
    public ApiResponse<JointSubTableSaveResponse> saveWithRelations(
            @Parameter(description = "页面Key") @PathVariable String pageKey,
            @RequestBody JointSubTableSaveRequest request) {
        log.info("联合保存数据: pageKey={}, tables={}", logSafe(pageKey),
                logSafe(request.getTables() != null ? request.getTables().keySet() : "null"));

        String modelCode = resolveModelCode(pageKey);
        JointSubTableSaveResponse result = dynamicDataService.saveWithRelations(modelCode, request);
        return ApiResponse.success(result);
    }

}

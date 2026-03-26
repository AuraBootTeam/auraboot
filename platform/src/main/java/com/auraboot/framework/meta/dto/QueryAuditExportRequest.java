package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 查询审计导出请求DTO
 * 
 * @author AuraBoot Team
 * @since 2.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class QueryAuditExportRequest {

    /**
     * 租户ID
     */
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

    /**
     * 导出格式
     */
    @NotNull(message = "导出格式不能为空")
    private String exportFormat;

    /**
     * 导出文件名
     */
    private String fileName;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 用户ID过滤器
     */
    private List<Long> userIds;

    /**
     * 模型编码过滤器
     */
    private List<String> modelCodes;

    /**
     * 查询类型过滤器
     */
    private List<String> queryTypes;

    /**
     * 是否只导出成功查询
     */
    private Boolean successfulQueriesOnly = false;

    /**
     * 是否只导出失败查询
     */
    private Boolean failedQueriesOnly = false;

    /**
     * 慢查询阈值(毫秒)
     */
    private Integer slowQueryThreshold;

    /**
     * 最小执行时间(毫秒)
     */
    private Integer minExecutionTime;

    /**
     * 最大执行时间(毫秒)
     */
    private Integer maxExecutionTime;

    /**
     * IP地址过滤器
     */
    private List<String> ipAddresses;

    /**
     * 请求ID过滤器
     */
    private List<String> requestIds;

    /**
     * 会话ID过滤器
     */
    private List<String> sessionIds;

    /**
     * 是否包含查询条件
     */
    private Boolean includeQueryConditions = true;

    /**
     * 是否包含选择字段
     */
    private Boolean includeSelectFields = true;

    /**
     * 是否包含排序字段
     */
    private Boolean includeSortFields = true;

    /**
     * 是否包含分页信息
     */
    private Boolean includePaginationInfo = true;

    /**
     * 是否包含错误信息
     */
    private Boolean includeErrorInfo = true;

    /**
     * 是否包含请求信息
     */
    private Boolean includeRequestInfo = true;

    /**
     * 是否包含性能信息
     */
    private Boolean includePerformanceInfo = true;

    /**
     * 导出字段列表
     */
    private List<String> exportFields;

    /**
     * 排序字段
     */
    private String sortField = "createdAt";

    /**
     * 排序方向
     */
    private String sortDirection = "desc";

    /**
     * 最大导出记录数
     */
    private Integer maxRecords = 100000;

    /**
     * 分页大小
     */
    private Integer pageSize = 1000;

    /**
     * 是否压缩文件
     */
    private Boolean compressFile = false;

    /**
     * 压缩格式
     */
    private String compressionFormat = "zip";

    /**
     * 文件编码
     */
    private String fileEncoding = "UTF-8";

    /**
     * CSV分隔符
     */
    private String csvDelimiter = ",";

    /**
     * CSV引用字符
     */
    private String csvQuoteChar = "\"";

    /**
     * 是否包含表头
     */
    private Boolean includeHeader = true;

    /**
     * 自定义过滤条件
     */
    private Map<String, Object> customFilters;

    /**
     * 导出模板ID
     */
    private String templateId;

    /**
     * 是否异步导出
     */
    private Boolean asyncExport = false;

    /**
     * 导出完成通知邮箱
     */
    private List<String> notificationEmails;

    /**
     * 导出描述
     */
    private String description;

    /**
     * 自定义参数
     */
    private Map<String, Object> customParameters;
}
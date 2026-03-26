package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 安全查询请求DTO
 * 用于安全查询执行的参数封装
 */
@Data
public class SecureQueryRequest {

    /**
     * 模型编码
     */
    @NotBlank(message = "模型编码不能为空")
    private String modelCode;

    /**
     * 查询类型
     */
    @NotNull(message = "查询类型不能为空")
    private QueryType queryType;

    /**
     * 查询条件
     */
    private List<QueryCondition> conditions;

    /**
     * 排序字段
     */
    private List<SortField> sortFields;

    /**
     * 分页请求
     */
    private PaginationRequest pagination;

    /**
     * 聚合请求
     */
    private AggregateRequest aggregateRequest;

    /**
     * 选择字段（为空则选择所有字段）
     */
    private List<String> selectFields;

    /**
     * 关联查询配置
     */
    private List<RelationQueryConfig> relationConfigs;

    /**
     * 用户ID
     */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /**
     * 租户ID
     */
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

    /**
     * 查询上下文
     */
    private Map<String, Object> queryContext;

    /**
     * 是否启用缓存
     */
    private Boolean enableCache;

    /**
     * 缓存过期时间（秒）
     */
    private Integer cacheExpireSeconds;

    /**
     * 查询超时时间（毫秒）
     */
    private Integer timeoutMs;

    /**
     * 最大返回记录数
     */
    private Integer maxRecords;

    /**
     * 是否启用数据脱敏
     */
    private Boolean enableDataMasking;

    /**
     * 是否记录审计日志
     */
    private Boolean enableAudit;

    /**
     * 查询标识（用于审计和缓存）
     */
    private String queryId;

    /**
     * 构造函数
     */
    public SecureQueryRequest() {
        this.queryType = QueryType.SELECT_ALL;
        this.enableCache = false;
        this.cacheExpireSeconds = 300; // 5分钟
        this.timeoutMs = 30000; // 30秒
        this.maxRecords = 10000;
        this.enableDataMasking = true;
        this.enableAudit = true;
    }
}
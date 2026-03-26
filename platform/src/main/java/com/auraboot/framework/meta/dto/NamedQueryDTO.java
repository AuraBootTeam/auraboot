package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.NamedQueryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 命名查询DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryDTO {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 业务主键
     */
    private String pid;

    /**
     * 租户ID
     */
    private Long tenantId;

      

    

    /**
     * 查询唯一标识码
     */
    private String code;

    /**
     * 查询标题
     */
    private String title;

    /**
     * 查询描述
     */
    private String description;

    /**
     * FROM子句SQL — null when connector_pid is set
     */
    private String fromSql;

    /**
     * External API connector PID. When set, this query delegates to the connector
     * instead of running SQL.
     */
    private String connectorPid;

    /**
     * Endpoint code within the external connector to invoke.
     */
    private String connectorEndpointCode;

    /**
     * 基础WHERE条件
     */
    private JsonNode baseWhere;

    /**
     * 默认排序
     */
    private JsonNode defaultOrder;

    /**
     * Lifecycle status: DRAFT, TESTING, PUBLISHED, DEPRECATED, ARCHIVED
     */
    private String status;

    private LocalDateTime publishedAt;

    private Long publishedBy;

    private LocalDateTime deprecatedAt;

    private Integer currentVersion;

    /**
     * Execution policy (maxRows, timeoutMs, rateLimitPerMinute, etc.)
     */
    private NamedQueryPolicy policy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ==================== 计算属性 ====================

    /**
     * 查询类型
     */
    private String queryType;

    /**
     * 是否复杂查询
     */
    private Boolean isComplexQuery;

    /**
     * 完整编码
     */
    private String fullCode;

    /**
     * 显示名称
     */
    private String displayName;

    /**
     * Whether query can be executed
     */
    private Boolean executable;

    /**
     * Whether query definition can be edited (from_sql, fields)
     */
    private Boolean editable;

    /**
     * Whether query definition is frozen
     */
    private Boolean frozen;

    /**
     * Backward compat: whether query is executable
     */
    private Boolean enabled;

    private Boolean hasBaseWhere;

    /**
     * 是否有默认排序
     */
    private Boolean hasDefaultOrder;

    /**
     * 摘要信息
     */
    private String summary;

    /**
     * 字段数量
     */
    private Integer fieldCount;

    /**
     * 字段列表
     */
    private List<NamedQueryFieldDTO> fields;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 扩展属性
     */
    private JsonNode metadata;

    /**
     * 版本号
     */
    private Long version;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 更新者
     */
    private String updatedBy;

    /**
     * 最后执行时间
     */
    private LocalDateTime lastExecutedAt;

    /**
     * 执行次数
     */
    private Long executionCount;

    /**
     * 平均执行时间（毫秒）
     */
    private Double averageExecutionTime;
}
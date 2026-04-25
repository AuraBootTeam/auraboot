package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.NamedQueryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 命名查询创建请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryCreateRequest {





    /**
     * 查询唯一标识码
     */
    @NotBlank(message = "查询编码不能为空")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]*$", message = "查询编码必须以字母开头，只能包含字母、数字和下划线")
    @Size(max = 100, message = "查询编码长度不能超过100个字符")
    private String code;

    /**
     * 查询标题
     */
    @NotBlank(message = "查询标题不能为空")
    @Size(max = 200, message = "查询标题长度不能超过200个字符")
    private String title;

    /**
     * 查询描述
     */
    @Size(max = 1000, message = "查询描述长度不能超过1000个字符")
    private String description;

    /**
     * FROM子句SQL — required unless connector_pid is set
     */
    @Size(max = 5000, message = "FROM子句SQL长度不能超过5000个字符")
    private String fromSql;

    /**
     * External API connector PID. When set, query delegates to the connector instead of SQL.
     * Mutually exclusive with fromSql.
     */
    private String connectorPid;

    /**
     * Endpoint code within the external connector.
     * Required when connectorPid is set.
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
     * 查询状态
     */
    @Pattern(regexp = "^(?i)(draft|testing|published|deprecated|archived)$", message = "Status must be one of: draft, testing, published, deprecated, archived")
    private String status = "draft";

    /**
     * 查询字段列表
     */
    private List<NamedQueryFieldRequest> fields;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 扩展属性
     */
    private JsonNode metadata;

    /**
     * Execution policy (maxRows, timeoutMs, rateLimitPerMinute, etc.)
     */
    private NamedQueryPolicy policy;

    /**
     * 是否自动创建字段
     */
    private Boolean autoCreateFields = false;

    /**
     * 字段创建策略
     */
    private String fieldCreationStrategy = "manual";

    /**
     * 是否验证SQL语法
     */
    private Boolean validateSql = true;

    /**
     * 是否检查权限
     */
    private Boolean checkPermissions = true;

    /**
     * 创建者备注
     */
    @Size(max = 500, message = "创建者备注长度不能超过500个字符")
    private String creatorNotes;
}

package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.NamedQueryPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 命名查询更新请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryUpdateRequest {

    /**
     * 查询标题
     */
    @Size(max = 200, message = "查询标题长度不能超过200个字符")
    private String title;

    /**
     * 查询描述
     */
    @Size(max = 1000, message = "查询描述长度不能超过1000个字符")
    private String description;

    /**
     * FROM子句SQL
     */
    @Size(max = 5000, message = "FROM子句SQL长度不能超过5000个字符")
    private String fromSql;

    /**
     * 基础WHERE条件
     */
    private JsonNode baseWhere;

    /**
     * 默认排序
     */
    private JsonNode defaultOrder;

    /**
     * Query lifecycle status
     */
    @Pattern(regexp = "^(?i)(draft|testing|published|deprecated|archived)$", message = "Status must be one of: draft, testing, published, deprecated, archived")
    private String status;

    /**
     * Execution policy (maxRows, timeoutMs, rateLimitPerMinute, etc.)
     */
    private NamedQueryPolicy policy;

    /**
     * 标签列表
     */
    private List<String> tags;

    /**
     * 扩展属性
     */
    private JsonNode metadata;

    /**
     * 期望版本号（乐观锁）
     */
    private Long expectedVersion;

    /**
     * 是否创建快照
     */
    private Boolean createSnapshot = false;

    /**
     * 版本备注
     */
    @Size(max = 500, message = "版本备注长度不能超过500个字符")
    private String versionNotes;

    /**
     * 是否验证SQL语法
     */
    private Boolean validateSql = true;

    /**
     * 是否检查权限
     */
    private Boolean checkPermissions = true;

    /**
     * 更新者备注
     */
    @Size(max = 500, message = "更新者备注长度不能超过500个字符")
    private String updaterNotes;
}
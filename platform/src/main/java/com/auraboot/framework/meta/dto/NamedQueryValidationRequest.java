package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 命名查询验证请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryValidationRequest {

    /**
     * FROM子句SQL
     */
    @NotBlank(message = "FROM子句SQL不能为空")
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
     * 查询字段列表
     */
    private List<NamedQueryFieldRequest> fields;

    /**
     * 验证类型
     */
    private String validationType = "full";

    /**
     * 是否验证SQL语法
     */
    private Boolean validateSql = true;

    /**
     * 是否验证字段
     */
    private Boolean validateFields = true;

    /**
     * 是否验证权限
     */
    private Boolean validatePermissions = true;

    /**
     * 是否验证性能
     */
    private Boolean validatePerformance = false;

    /**
     * 目标数据库类型
     */
    private String databaseType = "postgresql";

    /**
     * 验证上下文
     */
    private JsonNode validationContext;

    /**
     * 验证选项
     */
    private JsonNode validationOptions;
}
package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 命名查询语法验证请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQuerySyntaxRequest {

    /**
     * FROM子句SQL
     */
    @NotBlank(message = "FROM子句SQL不能为空")
    @Size(max = 5000, message = "FROM子句SQL长度不能超过5000个字符")
    private String fromSql;

    /**
     * 数据库类型
     */
    private String databaseType = "postgresql";

    /**
     * 验证级别
     */
    private String validationLevel = "strict"; // STRICT, NORMAL, LOOSE

    /**
     * 是否检查表存在性
     */
    private Boolean checkTableExists = false;

    /**
     * 是否检查字段存在性
     */
    private Boolean checkFieldExists = false;

    /**
     * 是否检查语法规范
     */
    private Boolean checkSyntaxRules = true;

    /**
     * 是否检查性能风险
     */
    private Boolean checkPerformanceRisks = true;

    /**
     * 是否检查安全风险
     */
    private Boolean checkSecurityRisks = true;

    /**
     * 验证上下文
     */
    private String validationContext;
}
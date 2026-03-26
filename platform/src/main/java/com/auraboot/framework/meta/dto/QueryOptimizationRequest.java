package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.List;

/**
 * 查询优化请求DTO
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryOptimizationRequest {

    /**
     * SQL语句
     */
    @NotBlank(message = "SQL语句不能为空")
    private String sql;

    /**
     * 查询参数
     */
    private Map<String, Object> parameters;

    /**
     * 优化级别（BASIC, STANDARD, AGGRESSIVE）
     */
    private String optimizationLevel;

    /**
     * 目标数据库类型
     */
    private String databaseType;

    /**
     * 最大执行时间（毫秒）
     */
    private Long maxExecutionTime;

    /**
     * 是否启用索引提示
     */
    private Boolean enableIndexHints;

    /**
     * 是否重写子查询
     */
    private Boolean rewriteSubqueries;

    /**
     * 是否优化JOIN顺序
     */
    private Boolean optimizeJoinOrder;

    /**
     * 预期结果集大小
     */
    private Long expectedResultSize;

    /**
     * 表统计信息
     */
    private Map<String, Object> tableStatistics;

    /**
     * 自定义优化规则
     */
    private List<String> customRules;
}
package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 查询验证请求DTO
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryValidationRequest {

    /**
     * 查询模板
     */
    @NotBlank(message = "查询模板不能为空")
    private String queryTemplate;

    /**
     * 查询参数
     */
    private Map<String, Object> parameters;

    /**
     * 验证级别（BASIC, STANDARD, STRICT）
     */
    private String validationLevel;

    /**
     * 是否检查SQL注入
     */
    private Boolean checkSqlInjection;

    /**
     * 是否检查性能风险
     */
    private Boolean checkPerformanceRisk;

    /**
     * 是否检查权限
     */
    private Boolean checkPermissions;

    /**
     * 最大复杂度阈值
     */
    private Integer maxComplexity;

    /**
     * 超时时间（毫秒）
     */
    private Long timeoutMs;
}
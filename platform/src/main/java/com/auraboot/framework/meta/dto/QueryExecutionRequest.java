package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.List;

/**
 * 查询执行请求DTO
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryExecutionRequest {

    /**
     * 查询名称
     */
    @NotBlank(message = "查询名称不能为空")
    private String queryName;

    /**
     * 查询参数
     */
    private Map<String, Object> parameters;

    /**
     * 查询字段列表（为空则返回所有字段）
     */
    private List<String> selectFields;

    /**
     * 排序字段
     */
    private List<SortField> sortFields;

    /**
     * 查询提示
     */
    private List<String> queryHints;

    /**
     * 是否需要脱敏
     */
    private Boolean needMasking;

    /**
     * 执行超时时间（秒）
     */
    private Integer timeoutSeconds;

    /**
     * 租户ID
     */
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;

    /**
     * 用户ID
     */
    @NotNull(message = "用户ID不能为空")
    private Long userId;

    /**
     * 排序字段
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SortField {
        /**
         * 字段名
         */
        @NotBlank(message = "排序字段名不能为空")
        private String fieldName;

        /**
         * 排序方向（ASC/DESC）
         */
        @Builder.Default
        private String direction = "asc";
    }
}
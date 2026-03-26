package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.util.Map;
import java.util.List;

/**
 * 动态数据请求DTO
 * 用于动态CRUD操作的请求参数
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicDataRequest {

    /**
     * 页面编码
     */
    @NotBlank(message = "页面编码不能为空")
    private String pageCode;

    /**
     * 操作类型：CREATE, UPDATE, DELETE, QUERY
     */
    @NotBlank(message = "操作类型不能为空")
    private String operation;

    /**
     * 数据内容
     */
    private Map<String, Object> data;

    /**
     * 查询条件
     */
    private Map<String, Object> conditions;

    /**
     * 排序字段
     */
    private List<SortField> sorts;

    /**
     * 分页页码
     */
    @Min(value = 1, message = "页码必须大于0")
    private Integer page = 1;

    /**
     * 分页大小
     */
    @Min(value = 1, message = "分页大小必须大于0")
    @Max(value = 1000, message = "分页大小不能超过1000")
    private Integer size = 20;

    /**
     * 关键字搜索
     */
    private String keyword;

    /**
     * 是否包含已删除数据
     */
    private Boolean includeDeleted = false;

    /**
     * 额外参数
     */
    private Map<String, Object> extra;

    /**
     * 排序字段内部类
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
        private String field;

        /**
         * 排序方向：ASC, DESC
         */
        @NotBlank(message = "排序方向不能为空")
        private String direction = "asc";
    }
}
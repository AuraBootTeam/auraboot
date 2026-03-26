package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 查询提示请求DTO
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryHintsRequest {

    /**
     * SQL语句
     */
    @NotBlank(message = "SQL语句不能为空")
    private String sql;

    /**
     * 查询提示列表
     */
    @NotEmpty(message = "查询提示列表不能为空")
    private List<String> hints;

    /**
     * 提示位置（BEFORE_SELECT, AFTER_SELECT, BEFORE_FROM等）
     */
    private String hintPosition;

    /**
     * 提示格式（ORACLE, MYSQL, POSTGRESQL等）
     */
    private String hintFormat;

    /**
     * 是否验证提示语法
     */
    private Boolean validateSyntax;

    /**
     * 是否合并重复提示
     */
    private Boolean mergeDuplicates;

    /**
     * 提示优先级
     */
    private Integer priority;
}
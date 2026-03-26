package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

/**
 * 字段排序更新请求
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldSortUpdateRequest {
    
    /**
     * 字段PID
     */
    @NotBlank(message = "字段PID不能为空")
    private String fieldPid;
    
    /**
     * 新的排序顺序
     */
    @NotNull(message = "排序顺序不能为空")
    @Min(value = 0, message = "排序顺序不能小于0")
    @Max(value = 9999, message = "排序顺序不能大于9999")
    private Integer sortOrder;
    
    /**
     * 字段名称（用于显示）
     */
    private String fieldName;
    
    /**
     * 更新人
     */
    private String updatedBy;
}
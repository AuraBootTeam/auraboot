package com.auraboot.framework.meta.dto;

import lombok.Data;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 批量字段排序调整请求DTO
 * 
 * @author AuraBoot Framework
 * @since 2.0.0
 */
@Data
public class FieldOrderBatchRequest {

    /**
     * 模型ID
     */
    @NotNull(message = "模型ID不能为空")
    private Long modelId;

    /**
     * 排序调整请求列表
     */
    @NotEmpty(message = "排序调整列表不能为空")
    private List<FieldOrderRequest> orderRequests;

    /**
     * 是否自动调整其他字段的排序
     */
    private Boolean autoAdjust;

    /**
     * 扩展信息
     */
    private Object extension;

    /**
     * 构造函数
     */
    public FieldOrderBatchRequest() {
        this.autoAdjust = true;
    }
}